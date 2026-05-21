import type { MedicationDoseDoc } from '@/api/firestore/medicationDoses';
import type { MedicationSlot } from '@/types/dailyEssentials';

/**
 * Assign each dose to a specific (slot, med) pair so the per-med time
 * chip and slot-pending pill can read a precise value.
 *
 * The hard problem: Android's UI dose-write path stores `slotKey =
 * slot.id.toString()` — the local Room PK of `MedicationSlotEntity` —
 * which web can't reverse-map. An Android-logged dose for the morning
 * therefore arrives with `slot_key = "5"`, useless to web's `"morning"`
 * /`"08:00"` /`"anytime"` bucket vocabulary. A naive per-(med, day)
 * lookup either bleeds the same time across every slot the med
 * appears in, or hides the time entirely.
 *
 * Algorithm:
 *  1. **Exact pass** — for every (med, slot) the med appears in, if any
 *     dose's `slot_key` equals the slot's bucket key, claim it. Web-
 *     logged doses always match this pass.
 *  2. **Proximity pass** — leftover doses (typically Android-logged)
 *     are matched to leftover slots by minutes-of-day proximity to each
 *     slot's "ideal time" (morning ≈ 08:00, evening ≈ 18:00, an
 *     `"HH:mm"` slot ≈ that exact wall clock). Beyond `PROXIMITY_LIMIT_MIN`
 *     the assignment is suppressed — a 22:00 dose isn't really an
 *     "08:00 dose" no matter how lonely the morning slot looks.
 *  3. `"anytime"` slots accept any leftover dose because they have no
 *     ideal-time anchor.
 *
 * Returns a map keyed by `${slotKey}__${medCloudId}` → the dose
 * assigned to that pair (or absent if no assignment was made).
 */

const PROXIMITY_LIMIT_MIN = 6 * 60;

const TIME_OF_DAY_IDEAL_MIN: Record<string, number> = {
  morning: 8 * 60,
  afternoon: 14 * 60,
  evening: 18 * 60,
  night: 22 * 60,
};

const HHMM = /^(\d{1,2}):(\d{2})$/;

export function assignmentKey(slotKey: string, medCloudId: string): string {
  return `${slotKey}__${medCloudId}`;
}

function idealMinutesFor(slotKey: string): number | null {
  if (slotKey === 'anytime') return null;
  const direct = TIME_OF_DAY_IDEAL_MIN[slotKey];
  if (direct !== undefined) return direct;
  const match = HHMM.exec(slotKey);
  if (match === null) return null;
  const h = Number.parseInt(match[1], 10);
  const m = Number.parseInt(match[2], 10);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return null;
  return h * 60 + m;
}

function minutesOfDay(epochMs: number): number {
  const d = new Date(epochMs);
  return d.getHours() * 60 + d.getMinutes();
}

function medCloudIdsOf(slot: MedicationSlot): string[] {
  const out: string[] = [];
  for (const raw of slot.medIds) {
    if (raw.startsWith('med:')) out.push(raw.slice('med:'.length));
  }
  return out;
}

export function buildDoseAssignment(
  slots: readonly MedicationSlot[],
  dosesByMed: Readonly<Record<string, readonly MedicationDoseDoc[]>>,
): Map<string, MedicationDoseDoc> {
  const result = new Map<string, MedicationDoseDoc>();

  const slotsByMed = new Map<string, MedicationSlot[]>();
  for (const slot of slots) {
    for (const id of medCloudIdsOf(slot)) {
      const list = slotsByMed.get(id);
      if (list === undefined) slotsByMed.set(id, [slot]);
      else list.push(slot);
    }
  }

  for (const [medId, medSlots] of slotsByMed) {
    const remaining = [...(dosesByMed[medId] ?? [])];
    if (remaining.length === 0) continue;

    const unassignedSlots: MedicationSlot[] = [];
    for (const slot of medSlots) {
      let pickIdx = -1;
      let pickTakenAt = -1;
      for (let i = 0; i < remaining.length; i++) {
        const d = remaining[i];
        if (d.slot_key !== slot.slotKey) continue;
        // Prefer the latest exact-match dose so a re-tap shows the
        // most recent timestamp rather than the first.
        if (d.taken_at > pickTakenAt) {
          pickIdx = i;
          pickTakenAt = d.taken_at;
        }
      }
      if (pickIdx >= 0) {
        result.set(assignmentKey(slot.slotKey, medId), remaining[pickIdx]);
        remaining.splice(pickIdx, 1);
      } else {
        unassignedSlots.push(slot);
      }
    }

    if (remaining.length === 0 || unassignedSlots.length === 0) continue;

    // Sort unassigned slots by ideal-time so when two slots tie for the
    // same dose, the earlier-anchored slot picks first. `anytime` is
    // treated as Infinity so it always picks last (whatever is left).
    const slotsWithIdeal = unassignedSlots
      .map((s) => ({ slot: s, ideal: idealMinutesFor(s.slotKey) }))
      .sort((a, b) => (a.ideal ?? Infinity) - (b.ideal ?? Infinity));

    for (const { slot, ideal } of slotsWithIdeal) {
      if (remaining.length === 0) break;
      if (ideal === null) {
        // `anytime` (or unrecognised bucket): take the earliest leftover
        // dose. Doesn't change correctness — anytime has no time anchor
        // — and gives a deterministic order.
        let earliestIdx = 0;
        for (let i = 1; i < remaining.length; i++) {
          if (remaining[i].taken_at < remaining[earliestIdx].taken_at) {
            earliestIdx = i;
          }
        }
        result.set(
          assignmentKey(slot.slotKey, medId),
          remaining[earliestIdx],
        );
        remaining.splice(earliestIdx, 1);
        continue;
      }
      let bestIdx = -1;
      let bestDelta = Infinity;
      for (let i = 0; i < remaining.length; i++) {
        const delta = Math.abs(minutesOfDay(remaining[i].taken_at) - ideal);
        if (delta < bestDelta) {
          bestDelta = delta;
          bestIdx = i;
        }
      }
      if (bestIdx >= 0 && bestDelta <= PROXIMITY_LIMIT_MIN) {
        result.set(assignmentKey(slot.slotKey, medId), remaining[bestIdx]);
        remaining.splice(bestIdx, 1);
      }
    }
  }

  return result;
}
