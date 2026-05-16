import { useMemo, useState } from 'react';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import type { MedicationTier } from '@/api/firestore/medicationSlots';
import type { MedicationSlot } from '@/types/dailyEssentials';

const TIERS: MedicationTier[] = ['skipped', 'essential', 'prescription', 'complete'];

type BulkMarkScope = 'slot' | 'full_day';

interface Props {
  isOpen: boolean;
  slots: ReadonlyArray<MedicationSlot>;
  onCancel: () => void;
  onConfirm: (params: {
    scope: BulkMarkScope;
    slotKey: string | null;
    tier: MedicationTier;
  }) => Promise<void> | void;
}

/**
 * Bulk-mark dialog. Mirrors Android's `BulkMarkDialog.kt` UX shape:
 * scope picker → slot picker (only when scope=slot) → tier picker → in-
 * dialog summary line → Cancel / Mark.
 *
 * Web's per-slot-aggregate tier-state shape means "mark slot 8am
 * complete" is one Firestore doc write and "mark today complete (full
 * day)" is N doc writes, where N = active slot count. The full-day
 * path uses `setTierStatesAtomic` (a writeBatch wrapper) so a network
 * blip mid-bulk doesn't leave the user with a torn state.
 *
 * Tier scope (the third option in the original audit prompt) is not
 * modeled — under the uniform-setter interpretation it collapses onto
 * full-day, so the first ship offers two scopes and the dialog stays
 * narrower (Decision 2).
 */
export function BulkMarkDialog({ isOpen, slots, onCancel, onConfirm }: Props) {
  const [scope, setScope] = useState<BulkMarkScope>('slot');
  const [slotKey, setSlotKey] = useState<string | null>(slots[0]?.slotKey ?? null);
  const [tier, setTier] = useState<MedicationTier | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const targetCount = useMemo(() => {
    if (scope === 'slot') {
      const match = slots.find((s) => s.slotKey === slotKey);
      // On web, "mark slot X" is a single tier-state doc write —
      // the count of underlying meds is informational only.
      return match ? Math.max(match.medIds.length, 1) : 0;
    }
    // FULL_DAY = one doc per active slot.
    return slots.length;
  }, [scope, slotKey, slots]);

  const summary = useMemo(() => {
    const tierLabel = tier ?? '…';
    if (scope === 'slot') {
      const match = slots.find((s) => s.slotKey === slotKey);
      const slotLabel = match?.displayTime ?? '—';
      const medCount = match?.medIds.length ?? 0;
      return medCount === 1
        ? `This will mark slot "${slotLabel}" (1 medication) as ${tierLabel}.`
        : `This will mark slot "${slotLabel}" (${medCount} medications) as ${tierLabel}.`;
    }
    return slots.length === 1
      ? `This will mark 1 slot across today as ${tierLabel}.`
      : `This will mark ${slots.length} slots across today as ${tierLabel}.`;
  }, [scope, slotKey, slots, tier]);

  const canSubmit = !submitting && tier !== null && targetCount > 0;

  const handleSubmit = async () => {
    if (!canSubmit || tier === null) return;
    setSubmitting(true);
    try {
      await onConfirm({
        scope,
        slotKey: scope === 'slot' ? slotKey : null,
        tier,
      });
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    if (submitting) return;
    onCancel();
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleCancel}
      title="Bulk Mark Medications"
      size="sm"
      persistent={submitting}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={handleCancel} disabled={submitting}>
            Cancel
          </Button>
          <Button variant="primary" onClick={handleSubmit} disabled={!canSubmit} loading={submitting}>
            Mark
          </Button>
        </div>
      }
    >
      <div className="space-y-4 text-sm">
        <fieldset>
          <legend className="text-xs font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">
            Scope
          </legend>
          <div className="mt-2 space-y-1">
            <label className="flex items-center gap-2">
              <input
                type="radio"
                name="bulk-mark-scope"
                value="slot"
                checked={scope === 'slot'}
                onChange={() => setScope('slot')}
              />
              <span>This slot</span>
            </label>
            <label className="flex items-center gap-2">
              <input
                type="radio"
                name="bulk-mark-scope"
                value="full_day"
                checked={scope === 'full_day'}
                onChange={() => setScope('full_day')}
              />
              <span>Full day (all slots)</span>
            </label>
          </div>
        </fieldset>

        {scope === 'slot' && (
          <fieldset>
            <legend className="text-xs font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">
              Slot
            </legend>
            <div className="mt-2 max-h-40 space-y-1 overflow-y-auto rounded border border-[var(--color-border)] p-2">
              {slots.map((s) => (
                <label key={s.slotKey} className="flex items-center gap-2">
                  <input
                    type="radio"
                    name="bulk-mark-slot"
                    value={s.slotKey}
                    checked={slotKey === s.slotKey}
                    onChange={() => setSlotKey(s.slotKey)}
                  />
                  <span>
                    {s.displayTime}{' '}
                    <span className="text-[var(--color-text-secondary)]">
                      ({s.medIds.length} med{s.medIds.length === 1 ? '' : 's'})
                    </span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
        )}

        <fieldset>
          <legend className="text-xs font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">
            Set to
          </legend>
          <div className="mt-2 flex flex-wrap gap-2">
            {TIERS.map((t) => {
              const selected = tier === t;
              return (
                <button
                  key={t}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => setTier(t)}
                  className={`rounded-full border px-3 py-1 text-sm capitalize transition-colors ${
                    selected
                      ? 'border-[var(--color-accent)] bg-[var(--color-accent)] text-white'
                      : 'border-[var(--color-border)] bg-[var(--color-bg-card)] text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)]'
                  }`}
                >
                  {t}
                </button>
              );
            })}
          </div>
        </fieldset>

        <p className="text-xs text-[var(--color-text-secondary)]">{summary}</p>
      </div>
    </Modal>
  );
}
