import { useCallback, useEffect, useMemo, useState } from 'react';
import { Pill } from 'lucide-react';
import { toast } from 'sonner';
import { dailyEssentialsApi } from '@/api/dailyEssentials';
import { getMedications } from '@/api/firestore/medications';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { Checkbox } from '@/components/ui/Checkbox';
import { EmptyState } from '@/components/ui/EmptyState';
import {
  deriveVirtualSlots,
  mergeVirtualWithMaterialized,
} from '@/features/medication/virtualSlots';
import type {
  MedicationSlot,
  MedicationSlotCompletion,
} from '@/types/dailyEssentials';
import { MedicationSlotDetailModal } from './MedicationSlotDetailModal';

const ANYTIME_KEY = 'anytime';
const ANYTIME_DISPLAY = 'Anytime';
const ROW_VISIBLE_LIMIT = 3;

function displayTimeFor(slotKey: string): string {
  return slotKey === ANYTIME_KEY ? ANYTIME_DISPLAY : slotKey;
}

/**
 * Format a completion row as a client-side slot. Med labels are a
 * prettified split of the synthetic dose key ("self_care_step:lipitor" →
 * "Lipitor"). The backend stores only the key snapshot, so web must
 * reconstruct a display label from it.
 */
function toSlot(row: MedicationSlotCompletion): MedicationSlot {
  const medLabels = row.med_ids.map((key) => {
    const idx = key.indexOf(':');
    const name = idx >= 0 ? key.slice(idx + 1) : key;
    return name
      .split(/[_\s]+/)
      .map((w) => (w.length ? w[0].toUpperCase() + w.slice(1) : w))
      .join(' ');
  });
  return {
    slotKey: row.slot_key,
    displayTime: displayTimeFor(row.slot_key),
    medLabels,
    medIds: row.med_ids,
    takenAt: row.taken_at,
  };
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
 * Renders today's medication slot rows. Materialized backend rows (from
 * `dailyEssentialsApi.listSlots`) win on the (slotKey) natural key —
 * they hold the canonical taken-state. Web also derives **virtual
 * slots** from each medication's `scheduleMode` / `timesOfDay` /
 * `specificTimes` so the user sees their full schedule even before any
 * toggles land (parity Batch 5 PR-3). See
 * `features/medication/virtualSlots.ts` for the bucketing rules.
 */
export function MedicationSlotList() {
  const date = useMemo(() => todayIso(), []);
  const [slots, setSlots] = useState<MedicationSlot[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeSlot, setActiveSlot] = useState<MedicationSlot | null>(null);

  const loadSlots = useCallback(async () => {
    try {
      const [rows, meds] = await Promise.all([
        dailyEssentialsApi.listSlots(date),
        getMedications(getFirebaseUid()).catch(() => []),
      ]);
      const materialized = rows
        .map(toSlot)
        .sort((a, b) => slotKeyComparator(a.slotKey, b.slotKey));
      const virtual = deriveVirtualSlots(meds);
      const merged = mergeVirtualWithMaterialized(materialized, virtual);
      setSlots(merged);
    } catch {
      // Network errors already surface a toast via the axios interceptor;
      // keep the list empty so the caller falls back to the empty state.
      setSlots([]);
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
        const row = await dailyEssentialsApi.toggleSlot({
          date,
          slot_key: slot.slotKey,
          med_ids: slot.medIds,
          taken: checked,
        });
        setSlots((prev) =>
          prev.map((s) => (s.slotKey === slot.slotKey ? toSlot(row) : s)),
        );
      } catch {
        toast.error('Failed to update slot.');
      }
    },
    [date],
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
