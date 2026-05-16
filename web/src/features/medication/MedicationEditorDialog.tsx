import { useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import { Clock, Plus, X } from 'lucide-react';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Select } from '@/components/ui/Select';
import { Checkbox } from '@/components/ui/Checkbox';
import {
  AnalogClockPicker,
  useAnalogClockState,
} from '@/components/AnalogClockPicker';
import {
  createMedication,
  updateMedication,
  type MedicationCreateInput,
  type MedicationDoc,
  type MedicationScheduleMode,
} from '@/api/firestore/medications';

/**
 * Add / edit medication editor for the web Medication management screen
 * (parity Batch 5 PR-1). The field set is a deliberate subset of
 * Android's `AddEditMedicationScreen` — the schedule-shaping core that
 * cross-syncs cleanly through `MedicationSyncMapper`. Slot linking,
 * dose history, refill projections, and reminder routing remain
 * Android-owned for this milestone.
 *
 * Schedule semantics mirror Android `MedicationEntity`:
 *   - TIMES_OF_DAY: `times_of_day` is the populated CSV (the picker
 *     surfaces morning/afternoon/evening/night chips).
 *   - SPECIFIC_TIMES: `specific_times` is the populated CSV
 *     (HH:mm strings, free-text input — repo lint forbids native
 *     time pickers because Safari coerces).
 *   - INTERVAL: `interval_millis` is populated (UI shows minutes;
 *     converted to ms on save).
 *   - AS_NEEDED: all three remain null.
 */

const TIME_OF_DAY_OPTIONS = [
  { key: 'morning', label: 'Morning' },
  { key: 'afternoon', label: 'Afternoon' },
  { key: 'evening', label: 'Evening' },
  { key: 'night', label: 'Night' },
] as const;

const SCHEDULE_MODE_OPTIONS: { value: MedicationScheduleMode; label: string }[] =
  [
    { value: 'TIMES_OF_DAY', label: 'Times of day (morning, evening…)' },
    { value: 'SPECIFIC_TIMES', label: 'Specific times (HH:mm)' },
    { value: 'INTERVAL', label: 'Every N hours' },
    { value: 'AS_NEEDED', label: 'As needed (PRN)' },
  ];

const TIER_OPTIONS = [
  { value: 'essential', label: 'Essential' },
  { value: 'prescription', label: 'Prescription' },
  { value: 'optional', label: 'Optional' },
  { value: 'as_needed', label: 'As needed' },
];

const REMINDER_MODE_OPTIONS = [
  { value: '__inherit__', label: 'Inherit from slot / global default' },
  { value: 'CLOCK', label: 'Fixed clock times' },
  { value: 'INTERVAL', label: 'Repeating interval' },
];

interface MedicationEditorDialogProps {
  isOpen: boolean;
  uid: string;
  /** Existing row to edit; `null` puts the dialog into create mode. */
  initial: MedicationDoc | null;
  onClose: () => void;
  /** Fires after a successful save so the parent screen can refresh. */
  onSaved: (medication: MedicationDoc) => void;
}

interface FormState {
  name: string;
  displayLabel: string;
  notes: string;
  tier: string;
  scheduleMode: MedicationScheduleMode;
  timesOfDay: Set<string>;
  /**
   * Sorted list of `HH:mm` strings for SPECIFIC_TIMES. Each entry is
   * the output of the per-time AnalogClockPicker — never free-form
   * text. We keep the list in canonical order so re-renders and saves
   * are deterministic.
   */
  specificTimes: string[];
  intervalHours: string;
  dosesPerDay: string;
  pillCount: string;
  pillsPerDose: string;
  pharmacyName: string;
  pharmacyPhone: string;
  reminderDaysBefore: string;
  reminderMode: string;
  reminderIntervalMinutes: string;
  promptDoseAtLog: boolean;
}

const TIME_REGEX = /^([01]?\d|2[0-3]):[0-5]\d$/;

/**
 * Parse the medication's stored `specific_times` CSV into a sorted,
 * de-duped list of `HH:mm` strings. Invalid entries are dropped
 * silently — this is the *read* path, not user input.
 */
function parseStoredSpecificTimes(raw: string | null): string[] {
  if (raw === null) return [];
  const tokens = raw
    .split(/[,\s]+/)
    .map((t) => t.trim())
    .filter((t) => t.length > 0);
  const ok = new Set<string>();
  for (const t of tokens) {
    if (TIME_REGEX.test(t)) {
      const [h, m] = t.split(':');
      ok.add(`${h.padStart(2, '0')}:${m}`);
    }
  }
  return [...ok].sort();
}

function formatHm(hour: number, minute: number): string {
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

function initialState(initial: MedicationDoc | null): FormState {
  if (initial === null) {
    return {
      name: '',
      displayLabel: '',
      notes: '',
      tier: 'essential',
      scheduleMode: 'TIMES_OF_DAY',
      timesOfDay: new Set(['morning']),
      specificTimes: [],
      intervalHours: '8',
      dosesPerDay: '1',
      pillCount: '',
      pillsPerDose: '1',
      pharmacyName: '',
      pharmacyPhone: '',
      reminderDaysBefore: '3',
      reminderMode: '__inherit__',
      reminderIntervalMinutes: '',
      promptDoseAtLog: false,
    };
  }
  const timeSet = new Set<string>(
    (initial.times_of_day ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0),
  );
  return {
    name: initial.name,
    displayLabel: initial.display_label ?? '',
    notes: initial.notes,
    tier: initial.tier,
    scheduleMode: initial.schedule_mode,
    timesOfDay: timeSet,
    specificTimes: parseStoredSpecificTimes(initial.specific_times),
    intervalHours:
      initial.interval_millis !== null
        ? String(Math.round(initial.interval_millis / (60 * 60 * 1000)))
        : '8',
    dosesPerDay: String(initial.doses_per_day),
    pillCount: initial.pill_count !== null ? String(initial.pill_count) : '',
    pillsPerDose: String(initial.pills_per_dose),
    pharmacyName: initial.pharmacy_name ?? '',
    pharmacyPhone: initial.pharmacy_phone ?? '',
    reminderDaysBefore: String(initial.reminder_days_before),
    reminderMode: initial.reminder_mode ?? '__inherit__',
    reminderIntervalMinutes:
      initial.reminder_interval_minutes !== null
        ? String(initial.reminder_interval_minutes)
        : '',
    promptDoseAtLog: initial.prompt_dose_at_log,
  };
}

export function MedicationEditorDialog({
  isOpen,
  uid,
  initial,
  onClose,
  onSaved,
}: MedicationEditorDialogProps) {
  const [form, setForm] = useState<FormState>(() => initialState(initial));
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  // Reset state whenever the dialog opens / the row we're editing changes.
  // Otherwise switching from "Add" → "Edit" → "Add" carries the prior
  // medication's fields into the create form.
  useEffect(() => {
    if (isOpen) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- form-init: re-seed form when modal opens / editing row changes
      setForm(initialState(initial));
      setErrors({});
    }
  }, [isOpen, initial]);

  const title = useMemo(
    () => (initial !== null ? `Edit ${initial.name}` : 'Add Medication'),
    [initial],
  );

  const setField = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  const toggleTimeOfDay = (key: string) => {
    setForm((prev) => {
      const next = new Set(prev.timesOfDay);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return { ...prev, timesOfDay: next };
    });
  };

  const validate = (): { ok: boolean; payload?: MedicationCreateInput } => {
    const nextErrors: Record<string, string> = {};
    const name = form.name.trim();
    if (name.length === 0) nextErrors.name = 'Name is required.';

    const dosesPerDay = parseInt(form.dosesPerDay, 10);
    if (!Number.isFinite(dosesPerDay) || dosesPerDay < 1) {
      nextErrors.dosesPerDay = 'Doses per day must be at least 1.';
    }

    const pillsPerDose = parseInt(form.pillsPerDose, 10);
    if (!Number.isFinite(pillsPerDose) || pillsPerDose < 1) {
      nextErrors.pillsPerDose = 'Pills per dose must be at least 1.';
    }

    const pillCount =
      form.pillCount.trim().length === 0 ? null : parseInt(form.pillCount, 10);
    if (pillCount !== null && (!Number.isFinite(pillCount) || pillCount < 0)) {
      nextErrors.pillCount = 'Pill count must be a non-negative number.';
    }

    const reminderDaysBefore = parseInt(form.reminderDaysBefore, 10);
    if (!Number.isFinite(reminderDaysBefore) || reminderDaysBefore < 0) {
      nextErrors.reminderDaysBefore = 'Reminder days must be 0 or higher.';
    }

    let timesOfDay: string | null = null;
    let specificTimes: string | null = null;
    let intervalMillis: number | null = null;

    if (form.scheduleMode === 'TIMES_OF_DAY') {
      const tods = [...form.timesOfDay];
      if (tods.length === 0) {
        nextErrors.scheduleMode = 'Pick at least one time of day.';
      } else {
        timesOfDay = TIME_OF_DAY_OPTIONS.filter((o) => form.timesOfDay.has(o.key))
          .map((o) => o.key)
          .join(',');
      }
    } else if (form.scheduleMode === 'SPECIFIC_TIMES') {
      if (form.specificTimes.length === 0) {
        nextErrors.scheduleMode = 'Add at least one specific time.';
      } else {
        // FormState already holds normalised HH:mm strings; join sorted
        // so cross-device diffs stay deterministic.
        specificTimes = [...form.specificTimes].sort().join(',');
      }
    } else if (form.scheduleMode === 'INTERVAL') {
      const hours = parseFloat(form.intervalHours);
      if (!Number.isFinite(hours) || hours <= 0) {
        nextErrors.scheduleMode = 'Interval (hours) must be positive.';
      } else {
        intervalMillis = Math.round(hours * 60 * 60 * 1000);
      }
    }

    const reminderMode =
      form.reminderMode === '__inherit__'
        ? null
        : (form.reminderMode as 'CLOCK' | 'INTERVAL');

    let reminderIntervalMinutes: number | null = null;
    if (form.reminderIntervalMinutes.trim().length > 0) {
      const v = parseInt(form.reminderIntervalMinutes, 10);
      if (!Number.isFinite(v) || v <= 0) {
        nextErrors.reminderIntervalMinutes =
          'Reminder interval must be positive.';
      } else {
        reminderIntervalMinutes = v;
      }
    }

    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return { ok: false };

    return {
      ok: true,
      payload: {
        name,
        display_label:
          form.displayLabel.trim().length > 0 ? form.displayLabel.trim() : null,
        notes: form.notes,
        tier: form.tier,
        schedule_mode: form.scheduleMode,
        times_of_day: timesOfDay,
        specific_times: specificTimes,
        interval_millis: intervalMillis,
        doses_per_day: dosesPerDay,
        pill_count: pillCount,
        pills_per_dose: pillsPerDose,
        pharmacy_name:
          form.pharmacyName.trim().length > 0 ? form.pharmacyName.trim() : null,
        pharmacy_phone:
          form.pharmacyPhone.trim().length > 0
            ? form.pharmacyPhone.trim()
            : null,
        reminder_days_before: reminderDaysBefore,
        reminder_mode: reminderMode,
        reminder_interval_minutes: reminderIntervalMinutes,
        prompt_dose_at_log: form.promptDoseAtLog,
      },
    };
  };

  const handleSave = async () => {
    const result = validate();
    if (!result.ok || !result.payload) return;
    setSaving(true);
    try {
      const saved =
        initial !== null
          ? await updateMedication(uid, initial.id, result.payload)
          : await createMedication(uid, result.payload);
      onSaved(saved);
      onClose();
    } catch (e) {
      toast.error((e as Error).message || 'Failed to save medication');
    } finally {
      setSaving(false);
    }
  };

  const footer = (
    <div className="flex justify-end gap-2">
      <Button variant="ghost" onClick={onClose} disabled={saving}>
        Cancel
      </Button>
      <Button variant="primary" onClick={handleSave} disabled={saving}>
        {saving ? 'Saving…' : initial !== null ? 'Save Changes' : 'Add Medication'}
      </Button>
    </div>
  );

  return (
    <Modal
      isOpen={isOpen}
      onClose={saving ? () => undefined : onClose}
      title={title}
      footer={footer}
      size="md"
      persistent={saving}
    >
      <div className="flex flex-col gap-4">
        <Input
          label="Name"
          value={form.name}
          onChange={(e) => setField('name', e.target.value)}
          error={errors.name}
          placeholder="e.g. Sertraline"
          autoFocus
        />
        <Input
          label="Display label (optional)"
          value={form.displayLabel}
          onChange={(e) => setField('displayLabel', e.target.value)}
          helperText="Friendly name shown in the slot row. Defaults to the name above."
        />
        <Select
          label="Tier"
          options={TIER_OPTIONS}
          value={form.tier}
          onChange={(v) => setField('tier', v ?? 'essential')}
        />
        <Select
          label="Schedule"
          options={SCHEDULE_MODE_OPTIONS}
          value={form.scheduleMode}
          onChange={(v) =>
            setField(
              'scheduleMode',
              (v ?? 'TIMES_OF_DAY') as MedicationScheduleMode,
            )
          }
          error={errors.scheduleMode}
        />

        {form.scheduleMode === 'TIMES_OF_DAY' && (
          <div className="flex flex-wrap gap-2">
            {TIME_OF_DAY_OPTIONS.map((o) => {
              const active = form.timesOfDay.has(o.key);
              return (
                <button
                  key={o.key}
                  type="button"
                  onClick={() => toggleTimeOfDay(o.key)}
                  className={`rounded-full border px-3 py-1 text-xs transition-colors ${
                    active
                      ? 'border-[var(--color-accent)] bg-[var(--color-accent)] text-white'
                      : 'border-[var(--color-border)] bg-[var(--color-bg-card)] text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)]'
                  }`}
                >
                  {o.label}
                </button>
              );
            })}
          </div>
        )}

        {form.scheduleMode === 'SPECIFIC_TIMES' && (
          <SpecificTimesPicker
            times={form.specificTimes}
            onChange={(next) => setField('specificTimes', next)}
            error={errors.scheduleMode}
          />
        )}

        {form.scheduleMode === 'INTERVAL' && (
          <Input
            label="Interval (hours)"
            type="number"
            inputMode="decimal"
            value={form.intervalHours}
            onChange={(e) => setField('intervalHours', e.target.value)}
          />
        )}

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input
            label="Doses per day"
            type="number"
            inputMode="numeric"
            value={form.dosesPerDay}
            onChange={(e) => setField('dosesPerDay', e.target.value)}
            error={errors.dosesPerDay}
          />
          <Input
            label="Pills per dose"
            type="number"
            inputMode="numeric"
            value={form.pillsPerDose}
            onChange={(e) => setField('pillsPerDose', e.target.value)}
            error={errors.pillsPerDose}
          />
        </div>

        <Input
          label="Pill count on hand (optional)"
          type="number"
          inputMode="numeric"
          value={form.pillCount}
          onChange={(e) => setField('pillCount', e.target.value)}
          error={errors.pillCount}
          helperText="Used by refill projections on Android."
        />

        <Input
          label="Pharmacy name (optional)"
          value={form.pharmacyName}
          onChange={(e) => setField('pharmacyName', e.target.value)}
        />
        <Input
          label="Pharmacy phone (optional)"
          value={form.pharmacyPhone}
          onChange={(e) => setField('pharmacyPhone', e.target.value)}
        />
        <Input
          label="Refill reminder (days before empty)"
          type="number"
          inputMode="numeric"
          value={form.reminderDaysBefore}
          onChange={(e) => setField('reminderDaysBefore', e.target.value)}
          error={errors.reminderDaysBefore}
        />

        <Select
          label="Reminder mode"
          options={REMINDER_MODE_OPTIONS}
          value={form.reminderMode}
          onChange={(v) => setField('reminderMode', v ?? '__inherit__')}
        />
        {form.reminderMode === 'INTERVAL' && (
          <Input
            label="Reminder interval (minutes)"
            type="number"
            inputMode="numeric"
            value={form.reminderIntervalMinutes}
            onChange={(e) =>
              setField('reminderIntervalMinutes', e.target.value)
            }
            error={errors.reminderIntervalMinutes}
          />
        )}

        <Checkbox
          checked={form.promptDoseAtLog}
          onChange={(v) => setField('promptDoseAtLog', v)}
          label="Prompt for dose amount when logging"
        />

        <Input
          label="Notes (optional)"
          value={form.notes}
          onChange={(e) => setField('notes', e.target.value)}
        />
      </div>
    </Modal>
  );
}

/**
 * Per-medication times list using {@link AnalogClockPicker} for adds /
 * edits. Mirrors the "every time field renders a 3-hand dial" rule
 * (memory: `feedback-time-input-use-clock-not-slider`). The data model
 * only stores hour + minute — the second hand is purely visual.
 */
function SpecificTimesPicker({
  times,
  onChange,
  error,
}: {
  times: readonly string[];
  onChange: (next: string[]) => void;
  error?: string;
}) {
  const [pickerOpen, setPickerOpen] = useState(false);
  const [editingIndex, setEditingIndex] = useState<number | null>(null);

  const handleAdd = () => {
    setEditingIndex(null);
    setPickerOpen(true);
  };

  const handleEdit = (index: number) => {
    setEditingIndex(index);
    setPickerOpen(true);
  };

  const handleRemove = (index: number) => {
    const next = times.filter((_, i) => i !== index);
    onChange(next);
  };

  const handlePicked = (hm: string) => {
    let next: string[];
    if (editingIndex !== null) {
      next = times.map((t, i) => (i === editingIndex ? hm : t));
    } else {
      next = [...times, hm];
    }
    // De-dupe + sort canonical so the chip row reads chronologically.
    next = [...new Set(next)].sort();
    onChange(next);
    setPickerOpen(false);
    setEditingIndex(null);
  };

  const initialSeed = useMemo(() => {
    if (editingIndex !== null && times[editingIndex]) {
      const [h, m] = times[editingIndex].split(':').map((n) => parseInt(n, 10));
      return { hour: h, minute: m };
    }
    return { hour: 8, minute: 0 };
  }, [editingIndex, times]);

  return (
    <div className="flex flex-col gap-2">
      <span className="text-xs font-medium text-[var(--color-text-primary)]">
        Specific Times
      </span>
      {error && <span className="text-xs text-rose-600">{error}</span>}
      <div className="flex flex-wrap gap-2">
        {times.map((t, i) => (
          <span
            key={`${t}-${i}`}
            className="inline-flex items-center gap-1 rounded-full border border-[var(--color-border)] bg-[var(--color-bg-card)] px-2.5 py-1 text-xs text-[var(--color-text-primary)]"
          >
            <button
              type="button"
              onClick={() => handleEdit(i)}
              className="inline-flex items-center gap-1 hover:text-[var(--color-accent)]"
              aria-label={`Edit ${t}`}
            >
              <Clock className="h-3 w-3" />
              {t}
            </button>
            <button
              type="button"
              onClick={() => handleRemove(i)}
              aria-label={`Remove ${t}`}
              className="ml-1 text-[var(--color-text-secondary)] hover:text-rose-600"
            >
              <X className="h-3 w-3" />
            </button>
          </span>
        ))}
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={handleAdd}
        >
          <Plus className="mr-1 h-3.5 w-3.5" />
          Add Time
        </Button>
      </div>
      <p className="text-xs text-[var(--color-text-secondary)]">
        Tap a chip to edit, or Add Time to pick a new one with the
        analog clock.
      </p>
      {pickerOpen && (
        <SpecificTimePickerModal
          initialHour={initialSeed.hour}
          initialMinute={initialSeed.minute}
          onCancel={() => {
            setPickerOpen(false);
            setEditingIndex(null);
          }}
          onSave={handlePicked}
        />
      )}
    </div>
  );
}

function SpecificTimePickerModal({
  initialHour,
  initialMinute,
  onCancel,
  onSave,
}: {
  initialHour: number;
  initialMinute: number;
  onCancel: () => void;
  onSave: (hm: string) => void;
}) {
  const api = useAnalogClockState({
    initialHour,
    initialMinute,
    initialSecond: 0,
    is24Hour: true,
  });
  return (
    <Modal
      isOpen={true}
      onClose={onCancel}
      title="Pick Time"
      size="sm"
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
          <Button
            variant="primary"
            onClick={() => onSave(formatHm(api.state.hour, api.state.minute))}
          >
            Save Time
          </Button>
        </div>
      }
    >
      <div className="flex flex-col items-center gap-2">
        <p className="text-center text-xs text-[var(--color-text-secondary)]">
          Hour and minute are persisted. Second is visual only.
        </p>
        <AnalogClockPicker api={api} />
      </div>
    </Modal>
  );
}
