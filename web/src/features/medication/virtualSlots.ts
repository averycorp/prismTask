import type { MedicationDoc } from '@/api/firestore/medications';
import type { MedicationSlot } from '@/types/dailyEssentials';

/**
 * Derive medication slots from the per-medication schedule fields
 * (`scheduleMode`, `timesOfDay`, `specificTimes`). This is the only
 * source of slot scaffolding on web; taken-state is filled in by the
 * caller from Firestore `medication_doses` so Android writes surface
 * here automatically.
 *
 * Bucketing rules (must match the Android slot-key vocabulary used by
 * `MedicationDoseEntity.slotKey` on Firestore):
 *   - `TIMES_OF_DAY`: one slot per `morning|afternoon|evening|night`
 *     entry in `times_of_day`.
 *   - `SPECIFIC_TIMES`: one slot per `HH:mm` entry in `specific_times`.
 *   - `INTERVAL`: a single `anytime` slot (true interval scheduling
 *     is Android-only — web shows the bucket so the user can still
 *     mark a dose).
 *   - `AS_NEEDED`: a single `anytime` slot (PRN).
 *   - Archived or empty-name medications are skipped entirely.
 *
 * The returned slots have `takenAt = null`; the caller flips it to a
 * real timestamp once every linked med has a Firestore dose for the
 * day.
 */
export function deriveVirtualSlots(
  medications: readonly MedicationDoc[],
): MedicationSlot[] {
  const byKey = new Map<string, { labels: string[]; medIds: string[] }>();

  const push = (slotKey: string, med: MedicationDoc) => {
    const entry = byKey.get(slotKey) ?? { labels: [], medIds: [] };
    const medCloudKey = `med:${med.id}`;
    if (!entry.medIds.includes(medCloudKey)) {
      entry.medIds.push(medCloudKey);
      entry.labels.push(med.display_label ?? med.name);
    }
    byKey.set(slotKey, entry);
  };

  for (const med of medications) {
    if (med.is_archived || med.name.length === 0) continue;
    switch (med.schedule_mode) {
      case 'TIMES_OF_DAY': {
        const buckets = (med.times_of_day ?? '')
          .split(',')
          .map((s) => s.trim().toLowerCase())
          .filter((s) => s.length > 0);
        if (buckets.length === 0) {
          // Schedule says times-of-day but no buckets configured —
          // surface as anytime so the user can still tick it.
          push('anytime', med);
        } else {
          for (const bucket of buckets) push(bucket, med);
        }
        break;
      }
      case 'SPECIFIC_TIMES': {
        const times = (med.specific_times ?? '')
          .split(',')
          .map((s) => s.trim())
          .filter((s) => s.length > 0);
        if (times.length === 0) push('anytime', med);
        else for (const t of times) push(t, med);
        break;
      }
      case 'INTERVAL':
      case 'AS_NEEDED':
      default:
        push('anytime', med);
        break;
    }
  }

  const slots: MedicationSlot[] = [];
  for (const [slotKey, entry] of byKey.entries()) {
    slots.push({
      slotKey,
      displayTime: slotKey === 'anytime' ? 'Anytime' : prettifyBucket(slotKey),
      medLabels: entry.labels,
      medIds: entry.medIds,
      takenAt: null,
    });
  }
  return slots.sort(compareSlotKeys);
}

function prettifyBucket(key: string): string {
  // morning|afternoon|evening|night → Title Case; HH:mm passes through.
  if (/^\d{1,2}:\d{2}$/.test(key)) return key;
  return key.charAt(0).toUpperCase() + key.slice(1);
}

function compareSlotKeys(a: MedicationSlot, b: MedicationSlot): number {
  // Anytime always sinks to the bottom — matches MedicationSlotList sort.
  if (a.slotKey === 'anytime') return 1;
  if (b.slotKey === 'anytime') return -1;
  return a.slotKey.localeCompare(b.slotKey);
}
