import { useMemo, useState } from 'react';
import { format } from 'date-fns';
import { toast } from 'sonner';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import {
  AnalogClockPicker,
  useAnalogClockState,
} from '@/components/AnalogClockPicker';
import { useHabitLogStore } from '@/stores/habitLogStore';
import { useSettingsStore } from '@/stores/settingsStore';
import type { Habit } from '@/types/habit';

interface HabitLogDialogProps {
  habit: Habit;
  onClose: () => void;
  /**
   * Optional success callback fired after the log persists. Today's
   * Habits card uses it to refresh the in-place counter without an
   * extra round-trip; the booking surface in `HabitListScreen` just
   * relies on the Firestore listener to reconcile.
   */
  onLogged?: () => void;
}

/**
 * Quick-log dialog for habit completions. Mirrors Android's
 * `HabitLogDialog` (`ui/screens/habits/components/HabitLogDialog.kt`) —
 * captures an optional note + completion time and writes a `habit_logs`
 * row via the habit-log store. Time is captured with the canonical
 * `AnalogClockPicker` per the `feedback-time-input-use-clock-not-slider`
 * memory; the date defaults to today's logical-date and is not
 * user-editable here (matches Android's "log right now" flow). For
 * back-filling activity on a non-today date, callers route through the
 * activity-history screen + booking dialog.
 *
 * Parity audit § B.3b — unit 14 of the 23-unit web parity batch.
 */
export function HabitLogDialog({
  habit,
  onClose,
  onLogged,
}: HabitLogDialogProps) {
  const logActivity = useHabitLogStore((s) => s.logActivity);
  // Read a primitive off the store so the selector returns a stable ref
  // (no React #185, see `feedback-zustand-selector-must-return-stable-ref`).
  const timeFormat = useSettingsStore((s) => s.timeFormat);
  const is24Hour = timeFormat === '24h';

  // Stable initial seed for the clock (memo so re-renders don't reset it).
  const now = useMemo(() => new Date(), []);
  const api = useAnalogClockState({
    initialHour: now.getHours(),
    initialMinute: now.getMinutes(),
    initialSecond: 0,
    is24Hour,
  });

  const [notes, setNotes] = useState('');
  const [saving, setSaving] = useState(false);

  const handleSubmit = async () => {
    setSaving(true);
    try {
      // Compose today's date with the picked hour+minute. Second is
      // visual-only — drop it so the persisted ms matches Android's
      // `HabitLogEntity.date` (hour, minute) shape.
      const composed = new Date(now);
      composed.setHours(api.state.hour, api.state.minute, 0, 0);
      const dateMs = composed.getTime();
      await logActivity({
        habit_id: habit.id,
        date: dateMs,
        notes: notes.trim() || null,
      });
      toast.success('Logged');
      onLogged?.();
      onClose();
    } catch {
      toast.error('Failed to log');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={`Log Entry — ${habit.name}`}
      size="sm"
      footer={
        <div className="flex justify-end gap-3">
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} loading={saving}>
            Log
          </Button>
        </div>
      }
    >
      <div className="flex flex-col items-center gap-5">
        <p className="text-center text-xs text-[var(--color-text-secondary)]">
          {format(now, 'EEEE, MMMM d, yyyy')}
        </p>
        <AnalogClockPicker api={api} />

        <div className="flex w-full flex-col gap-1.5">
          <label
            htmlFor="habit-log-notes"
            className="text-sm font-medium text-[var(--color-text-primary)]"
          >
            Notes
          </label>
          <textarea
            id="habit-log-notes"
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] placeholder-[var(--color-text-secondary)] outline-none transition-colors focus:border-[var(--color-accent)] focus:ring-1 focus:ring-[var(--color-accent)]"
            placeholder="How did it go? Optional."
            rows={3}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />
        </div>
      </div>
    </Modal>
  );
}
