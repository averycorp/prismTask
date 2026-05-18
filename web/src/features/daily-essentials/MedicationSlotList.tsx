import { useCallback, useEffect, useMemo, useState } from 'react';
import { Pill } from 'lucide-react';
import { toast } from 'sonner';
import { getMedications } from '@/api/firestore/medications';
import {
  deleteDose,
  getDosesForDay,
  logDose,
  medicationDoseId,
  type MedicationDoseDoc,
} from '@/api/firestore/medicationDoses';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { Checkbox } from '@/components/ui/Checkbox';
import { EmptyState } from '@/components/ui/EmptyState';
import { deriveVirtualSlots } from '@/features/medication/virtualSlots';
import type { MedicationSlot } from '@/types/dailyEssentials';
import { MedicationSlotDetailModal } from './MedicationSlotDetailModal';

const ANYTIME_KEY = 'anytime';
const MED_PREFIX = 'med:';
const ROW_VISIBLE_LIMIT = 3;

function medCloudIdOf(raw: string): string | null {
  return raw.startsWith(MED_PREFIX) ? raw.slice(MED_PREFIX.length) : null;
}

function slotKeyComparator(a: string, b: string): number {
  const aAny = a === ANYTIME_KEY;
  const bAny = b === ANYTIME_KEY;
  if (aAny && bAny) return 0;
  if (aAny) return 1;
  if (bAny) return -1;
  return a.localeCompare(b);
}

function rowLabel(slot: MedicationSlot, limit = ROW_VISIBLE_LIMIT): string {
  const head = slot.medLabels.slice(0, limit).join(', ');
  const extra = slot.medLabels.length - limit;
  const tail = extra > 0 ? `, +${extra} more` : '';
  return `${slot.displayTime} meds: ${head}${tail}`;
}

function todayIso(): string {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/**
 * Renders today's medication slot rows. Reads slot scaffolding from the
 * user's medication library (Firestore `users/{uid}/medications` via
 * `deriveVirtualSlots`) and renders taken-state from per-medication
 * doses (`medication_doses`). Both are Firestore-direct, which means
 * what the user logs on phone shows up here on the next snapshot.
 *
 * (The legacy backend `daily_essential_slot_completions` table is no
 * longer consulted — it never received Android's writes, which is why
 * phone-logged doses didn't surface here. See
 * `features/medication/virtualSlots.ts` for the bucketing rules.)
 */
export function MedicationSlotList() {
  const date = useMemo(() => todayIso(), []);
  const [slots, setSlots] = useState<MedicationSlot[]>([]);
  const [dosesByKey, setDosesByKey] = useState<
    Record<string, MedicationDoseDoc>
  >({});
  const [loading, setLoading] = useState(true);
  const [activeSlot, setActiveSlot] = useState<MedicationSlot | null>(null);

  const loadSlots = useCallback(async () => {
    try {
      const uid = getFirebaseUid();
      const meds = await getMedications(uid).catch(() => []);
      const virtual = deriveVirtualSlots(meds);
      const medCloudIds = meds.map((m) => m.id);
      const allDoses = await Promise.all(
        medCloudIds.map((id) => getDosesForDay(uid, id, date).catch(() => [])),
      );
      const byDoseKey: Record<string, MedicationDoseDoc> = {};
      for (const list of allDoses) {
        for (const dose of list) byDoseKey[dose.id] = dose;
      }
      // Compute slot taken-state from doses — a slot counts as taken
      // when every linked med has a dose for (slot, date).
      const withTaken = virtual
        .map((slot): MedicationSlot => {
          const linked = slot.medIds
            .map(medCloudIdOf)
            .filter((id): id is string => id !== null);
          if (linked.length === 0) return slot;
          const doses = linked.map(
            (id) => byDoseKey[medicationDoseId(id, slot.slotKey, date)],
          );
          const allTaken = doses.every((d) => d !== undefined);
          if (!allTaken) return { ...slot, takenAt: null };
          let latest = 0;
          for (const d of doses) {
            if (d !== undefined && d.taken_at > latest) latest = d.taken_at;
          }
          return { ...slot, takenAt: latest === 0 ? null : latest };
        })
        .sort((a, b) => slotKeyComparator(a.slotKey, b.slotKey));
      setDosesByKey(byDoseKey);
      setSlots(withTaken);
    } catch {
      setSlots([]);
      setDosesByKey({});
    } finally {
      setLoading(false);
    }
  }, [date]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load slots on mount and when date changes
    loadSlots();
  }, [loadSlots]);

  const onToggleSlot = useCallback(
    async (slot: MedicationSlot, checked: boolean) => {
      try {
        const uid = getFirebaseUid();
        const medCloudIds = slot.medIds
          .map(medCloudIdOf)
          .filter((id): id is string => id !== null);
        if (medCloudIds.length === 0) return;
        if (checked) {
          const newDoses: MedicationDoseDoc[] = [];
          for (const medCloudId of medCloudIds) {
            const id = medicationDoseId(medCloudId, slot.slotKey, date);
            if (dosesByKey[id] !== undefined) continue;
            const dose = await logDose(uid, {
              medicationCloudId: medCloudId,
              slotKey: slot.slotKey,
              dateIso: date,
            });
            newDoses.push(dose);
          }
          if (newDoses.length > 0) {
            setDosesByKey((prev) => {
              const next = { ...prev };
              for (const d of newDoses) next[d.id] = d;
              return next;
            });
          }
        } else {
          const removedIds: string[] = [];
          for (const medCloudId of medCloudIds) {
            const id = medicationDoseId(medCloudId, slot.slotKey, date);
            const existing = dosesByKey[id];
            if (existing === undefined) continue;
            await deleteDose(uid, existing.id);
            removedIds.push(id);
          }
          if (removedIds.length > 0) {
            setDosesByKey((prev) => {
              const next = { ...prev };
              for (const id of removedIds) delete next[id];
              return next;
            });
          }
        }
        // Reflect the new dose set in the slot's takenAt locally so the
        // checkbox flips immediately without waiting for a refetch.
        setSlots((prev) =>
          prev.map((s) => {
            if (s.slotKey !== slot.slotKey) return s;
            const linked = s.medIds
              .map(medCloudIdOf)
              .filter((id): id is string => id !== null);
            const taken = checked && linked.length > 0;
            return { ...s, takenAt: taken ? Date.now() : null };
          }),
        );
      } catch {
        toast.error('Failed to update slot.');
      }
    },
    [date, dosesByKey],
  );

  if (loading) {
    return (
      <div className="text-sm text-[color:var(--color-text-muted)]">
        Loading medications…
      </div>
    );
  }

  if (slots.length === 0) {
    return (
      <EmptyState
        icon={<Pill className="h-6 w-6" />}
        title="No Medications Scheduled"
        description="Add a medication on the Medication screen to start tracking it here."
      />
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {slots.map((slot) => (
        <button
          key={slot.slotKey}
          type="button"
          onClick={() => setActiveSlot(slot)}
          className="flex items-center gap-3 rounded-md border border-[color:var(--color-border)] bg-[color:var(--color-surface)] px-3 py-2 text-left transition hover:border-[color:var(--color-accent)]"
        >
          <Pill className="h-4 w-4 text-[color:var(--color-accent)]" />
          <span className="flex-1 text-sm font-medium">{rowLabel(slot)}</span>
          <span
            onClick={(e) => e.stopPropagation()}
            className="inline-flex"
          >
            <Checkbox
              checked={slot.takenAt !== null}
              onChange={(checked) => onToggleSlot(slot, checked)}
            />
          </span>
        </button>
      ))}

      {activeSlot ? (
        <MedicationSlotDetailModal
          slot={activeSlot}
          onClose={() => setActiveSlot(null)}
          onToggleSlot={(checked) => onToggleSlot(activeSlot, checked)}
        />
      ) : null}
    </div>
  );
}
