import { useCallback, useEffect, useMemo, useState } from 'react';
import { format } from 'date-fns';
import { AlertTriangle, Pill, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { Modal } from '@/components/ui/Modal';
import { Input } from '@/components/ui/Input';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import {
  createRefill,
  deleteRefill,
  forecastRefill,
  getRefills,
  updateRefill,
  type MedicationRefillCreateInput,
  type MedicationRefillDoc,
  type RefillForecast,
  type RefillUrgency,
} from '@/api/firestore/medicationRefills';
import { getFirebaseUid } from '@/stores/firebaseUid';

/**
 * Web port of Android `MedicationRefillScreen`. Surfaces refill
 * forecasts (days remaining, refill date, reminder date, urgency) for
 * every medication tracked in `users/{uid}/medication_refills`.
 *
 * Urgency thresholds mirror Android `RefillUrgencyConfig`'s defaults:
 *   - HEALTHY: > 7 days remaining
 *   - UPCOMING: 3-7 days
 *   - URGENT: < 3 days
 *   - OUT_OF_STOCK: pill_count <= 0
 *
 * Pre-existing `MedicationRefillEntity` rows from Android keep flowing
 * in via Firestore sync — this screen reads + writes the same
 * collection so both writers stay consistent.
 */

const URGENCY_STYLES: Record<RefillUrgency, { label: string; cls: string }> = {
  HEALTHY: {
    label: 'Healthy',
    cls: 'border-emerald-500/40 bg-emerald-500/5 text-emerald-600',
  },
  UPCOMING: {
    label: 'Upcoming',
    cls: 'border-amber-500/40 bg-amber-500/5 text-amber-600',
  },
  URGENT: {
    label: 'Refill now',
    cls: 'border-rose-500/50 bg-rose-500/5 text-rose-600',
  },
  OUT_OF_STOCK: {
    label: 'Out of stock',
    cls: 'border-rose-600 bg-rose-500/10 text-rose-700',
  },
};

export function MedicationRefillScreen() {
  const [refills, setRefills] = useState<MedicationRefillDoc[]>([]);
  const [loading, setLoading] = useState(true);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<MedicationRefillDoc | null>(null);
  const [refillTopUpFor, setRefillTopUpFor] =
    useState<MedicationRefillDoc | null>(null);
  const [topUpAmount, setTopUpAmount] = useState('');
  const [confirmDelete, setConfirmDelete] =
    useState<MedicationRefillDoc | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const uid = getFirebaseUid();
      const list = await getRefills(uid);
      setRefills(list);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to load refills');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load on mount
    load();
  }, [load]);

  const forecasts = useMemo(() => {
    const now = Date.now();
    const map = new Map<string, RefillForecast>();
    for (const refill of refills) {
      map.set(refill.id, forecastRefill(refill, now));
    }
    return map;
  }, [refills]);

  const sortedRefills = useMemo(() => {
    const urgencyOrder: Record<RefillUrgency, number> = {
      OUT_OF_STOCK: 0,
      URGENT: 1,
      UPCOMING: 2,
      HEALTHY: 3,
    };
    return [...refills].sort((a, b) => {
      const fa = forecasts.get(a.id);
      const fb = forecasts.get(b.id);
      const ua = fa ? urgencyOrder[fa.urgency] : 99;
      const ub = fb ? urgencyOrder[fb.urgency] : 99;
      if (ua !== ub) return ua - ub;
      return a.medication_name.localeCompare(b.medication_name);
    });
  }, [refills, forecasts]);

  const handleAdd = () => {
    setEditing(null);
    setEditorOpen(true);
  };

  const handleEdit = (refill: MedicationRefillDoc) => {
    setEditing(refill);
    setEditorOpen(true);
  };

  const handleTopUp = (refill: MedicationRefillDoc) => {
    setRefillTopUpFor(refill);
    setTopUpAmount(String(refill.pill_count));
  };

  const submitTopUp = async () => {
    if (refillTopUpFor === null) return;
    const next = parseInt(topUpAmount, 10);
    if (!Number.isFinite(next) || next < 0) {
      toast.error('Enter a non-negative count.');
      return;
    }
    try {
      const uid = getFirebaseUid();
      await updateRefill(uid, refillTopUpFor.id, {
        pill_count: next,
        last_refill_date: Date.now(),
      });
      setRefillTopUpFor(null);
      await load();
      toast.success(`Refilled ${refillTopUpFor.medication_name}`);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to refill');
    }
  };

  const handleDelete = async (refill: MedicationRefillDoc) => {
    try {
      const uid = getFirebaseUid();
      await deleteRefill(uid, refill.id);
      await load();
      toast.success(`Removed ${refill.medication_name}`);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to delete');
    } finally {
      setConfirmDelete(null);
    }
  };

  return (
    <div className="mx-auto max-w-3xl pb-16">
      <header className="mb-6 flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Pill className="h-5 w-5 text-[var(--color-accent)]" />
          <div>
            <h1 className="text-xl font-semibold text-[var(--color-text-primary)]">
              Medication Refills
            </h1>
            <p className="text-sm text-[var(--color-text-secondary)]">
              Track pill counts and project refill dates.
            </p>
          </div>
        </div>
        <Button variant="primary" size="sm" onClick={handleAdd}>
          <Plus className="mr-1 h-4 w-4" />
          Add
        </Button>
      </header>

      {loading && refills.length === 0 ? (
        <p className="py-8 text-center text-sm text-[var(--color-text-secondary)]">
          Loading refills…
        </p>
      ) : sortedRefills.length === 0 ? (
        <EmptyState
          icon={<Pill className="h-8 w-8" />}
          title="No refills tracked"
          description="Add a medication to start projecting refill dates and reminders."
        />
      ) : (
        <ul className="flex flex-col gap-3">
          {sortedRefills.map((refill) => {
            const forecast = forecasts.get(refill.id);
            const style = forecast
              ? URGENCY_STYLES[forecast.urgency]
              : URGENCY_STYLES.HEALTHY;
            return (
              <li
                key={refill.id}
                className={`rounded-xl border p-4 ${style.cls}`}
              >
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-base font-semibold text-[var(--color-text-primary)]">
                        {refill.medication_name}
                      </span>
                      <span className="rounded-full bg-[var(--color-bg-card)]/80 px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide">
                        {style.label}
                      </span>
                    </div>
                    <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
                      {refill.pill_count} pills on hand · {refill.pills_per_dose}{' '}
                      per dose × {refill.doses_per_day}/day
                    </p>
                    {forecast && (
                      <p className="mt-2 text-xs text-[var(--color-text-primary)]">
                        ~{forecast.daysRemaining} day
                        {forecast.daysRemaining === 1 ? '' : 's'} remaining ·
                        runs out{' '}
                        {format(forecast.refillDateMillis, 'MMM d, yyyy')}
                      </p>
                    )}
                    {forecast &&
                      (forecast.urgency === 'URGENT' ||
                        forecast.urgency === 'OUT_OF_STOCK') && (
                        <p className="mt-1 inline-flex items-center gap-1 text-xs font-medium text-rose-600">
                          <AlertTriangle className="h-3 w-3" />
                          {forecast.urgency === 'OUT_OF_STOCK'
                            ? 'Refill immediately'
                            : `Reminder: ${format(forecast.reminderDateMillis, 'MMM d')}`}
                        </p>
                      )}
                    {refill.pharmacy_name && (
                      <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
                        {refill.pharmacy_name}
                        {refill.pharmacy_phone && ` · ${refill.pharmacy_phone}`}
                      </p>
                    )}
                  </div>
                  <div className="flex gap-1.5">
                    <Button
                      variant="primary"
                      size="sm"
                      onClick={() => handleTopUp(refill)}
                      aria-label={`Mark refilled for ${refill.medication_name}`}
                    >
                      <RefreshCw className="mr-1 h-3.5 w-3.5" />
                      Refilled
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleEdit(refill)}
                    >
                      Edit
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setConfirmDelete(refill)}
                      aria-label={`Delete ${refill.medication_name}`}
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      )}

      <RefillEditorDialog
        isOpen={editorOpen}
        initial={editing}
        onClose={() => setEditorOpen(false)}
        onSaved={async () => {
          await load();
          setEditorOpen(false);
        }}
      />

      {refillTopUpFor && (
        <Modal
          isOpen={true}
          onClose={() => setRefillTopUpFor(null)}
          title={`Refilled ${refillTopUpFor.medication_name}`}
          footer={
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setRefillTopUpFor(null)}>
                Cancel
              </Button>
              <Button variant="primary" onClick={submitTopUp}>
                Save
              </Button>
            </div>
          }
        >
          <Input
            label="New pill count"
            type="number"
            inputMode="numeric"
            value={topUpAmount}
            onChange={(e) => setTopUpAmount(e.target.value)}
            autoFocus
          />
        </Modal>
      )}

      {confirmDelete && (
        <ConfirmDialog
          isOpen={true}
          title="Remove refill tracking?"
          message={`This stops projecting refill dates for ${confirmDelete.medication_name}.`}
          confirmLabel="Remove"
          variant="danger"
          onConfirm={() => handleDelete(confirmDelete)}
          onClose={() => setConfirmDelete(null)}
        />
      )}
    </div>
  );
}

interface EditorState {
  medication_name: string;
  pill_count: string;
  pills_per_dose: string;
  doses_per_day: string;
  reminder_days_before: string;
  pharmacy_name: string;
  pharmacy_phone: string;
}

function initialEditorState(
  initial: MedicationRefillDoc | null,
): EditorState {
  if (initial === null) {
    return {
      medication_name: '',
      pill_count: '',
      pills_per_dose: '1',
      doses_per_day: '1',
      reminder_days_before: '3',
      pharmacy_name: '',
      pharmacy_phone: '',
    };
  }
  return {
    medication_name: initial.medication_name,
    pill_count: String(initial.pill_count),
    pills_per_dose: String(initial.pills_per_dose),
    doses_per_day: String(initial.doses_per_day),
    reminder_days_before: String(initial.reminder_days_before),
    pharmacy_name: initial.pharmacy_name ?? '',
    pharmacy_phone: initial.pharmacy_phone ?? '',
  };
}

function RefillEditorDialog({
  isOpen,
  initial,
  onClose,
  onSaved,
}: {
  isOpen: boolean;
  initial: MedicationRefillDoc | null;
  onClose: () => void;
  onSaved: () => Promise<void> | void;
}) {
  const [form, setForm] = useState<EditorState>(() =>
    initialEditorState(initial),
  );
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (isOpen) {
      setForm(initialEditorState(initial));
      setErrors({});
    }
  }, [isOpen, initial]);

  const setField = <K extends keyof EditorState>(k: K, v: EditorState[K]) =>
    setForm((prev) => ({ ...prev, [k]: v }));

  const validate = (): {
    ok: boolean;
    payload?: MedicationRefillCreateInput;
  } => {
    const nextErrors: Record<string, string> = {};
    const name = form.medication_name.trim();
    if (name.length === 0) nextErrors.medication_name = 'Name is required.';
    const pc = parseInt(form.pill_count, 10);
    if (!Number.isFinite(pc) || pc < 0)
      nextErrors.pill_count = 'Pill count must be a non-negative integer.';
    const ppd = parseInt(form.pills_per_dose, 10);
    if (!Number.isFinite(ppd) || ppd < 1)
      nextErrors.pills_per_dose = 'Pills per dose must be at least 1.';
    const dpd = parseInt(form.doses_per_day, 10);
    if (!Number.isFinite(dpd) || dpd < 1)
      nextErrors.doses_per_day = 'Doses per day must be at least 1.';
    const rdb = parseInt(form.reminder_days_before, 10);
    if (!Number.isFinite(rdb) || rdb < 0)
      nextErrors.reminder_days_before = 'Reminder days must be 0 or higher.';
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return { ok: false };
    return {
      ok: true,
      payload: {
        medication_name: name,
        pill_count: pc,
        pills_per_dose: ppd,
        doses_per_day: dpd,
        reminder_days_before: rdb,
        pharmacy_name:
          form.pharmacy_name.trim().length > 0
            ? form.pharmacy_name.trim()
            : null,
        pharmacy_phone:
          form.pharmacy_phone.trim().length > 0
            ? form.pharmacy_phone.trim()
            : null,
      },
    };
  };

  const handleSave = async () => {
    const result = validate();
    if (!result.ok || !result.payload) return;
    setSaving(true);
    try {
      const uid = getFirebaseUid();
      if (initial !== null) {
        await updateRefill(uid, initial.id, result.payload);
      } else {
        await createRefill(uid, result.payload);
      }
      await onSaved();
    } catch (e) {
      toast.error((e as Error).message || 'Failed to save refill');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={saving ? () => undefined : onClose}
      title={initial !== null ? `Edit ${initial.medication_name}` : 'Add Refill'}
      persistent={saving}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button variant="primary" onClick={handleSave} disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </div>
      }
    >
      <div className="flex flex-col gap-4">
        <Input
          label="Medication name"
          value={form.medication_name}
          onChange={(e) => setField('medication_name', e.target.value)}
          error={errors.medication_name}
          autoFocus
        />
        <Input
          label="Pill count on hand"
          type="number"
          inputMode="numeric"
          value={form.pill_count}
          onChange={(e) => setField('pill_count', e.target.value)}
          error={errors.pill_count}
        />
        <div className="grid grid-cols-2 gap-4">
          <Input
            label="Pills per dose"
            type="number"
            inputMode="numeric"
            value={form.pills_per_dose}
            onChange={(e) => setField('pills_per_dose', e.target.value)}
            error={errors.pills_per_dose}
          />
          <Input
            label="Doses per day"
            type="number"
            inputMode="numeric"
            value={form.doses_per_day}
            onChange={(e) => setField('doses_per_day', e.target.value)}
            error={errors.doses_per_day}
          />
        </div>
        <Input
          label="Reminder days before"
          type="number"
          inputMode="numeric"
          value={form.reminder_days_before}
          onChange={(e) => setField('reminder_days_before', e.target.value)}
          error={errors.reminder_days_before}
        />
        <Input
          label="Pharmacy name (optional)"
          value={form.pharmacy_name}
          onChange={(e) => setField('pharmacy_name', e.target.value)}
        />
        <Input
          label="Pharmacy phone (optional)"
          value={form.pharmacy_phone}
          onChange={(e) => setField('pharmacy_phone', e.target.value)}
        />
      </div>
    </Modal>
  );
}
