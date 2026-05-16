import { useMemo, useState } from 'react';
import { format, parseISO, isValid } from 'date-fns';
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

interface HabitBookingDialogProps {
  habit: Habit;
  onClose: () => void;
}

/**
 * Book Habit dialog for `is_bookable` habits. Mirrors Android's
 * `HabitDetailViewModel.onLogActivity` entry point + the `ActivityLogDialog`
 * date+notes shape — captures a date, a time (via the canonical
 * `AnalogClockPicker` per the `feedback-time-input-use-clock-not-slider`
 * memory), and an optional note, then writes a `habit_logs` row.
 *
 * Parity audit § B.3b. Web doesn't model the `isBooked` / `bookedDate` /
 * `bookedNote` flags on the habit row itself yet (the web
 * `habitCreateToDoc` payload intentionally omits them, see
 * `web/src/api/firestore/habits.ts` § "Why omission instead of
 * writing-defaults"), so this dialog only writes the log row. Android
 * keeps the booking-state habit flags as a UI-side cache derived from
 * the latest log; web pulls the same picture from `habitLogStore`.
 */
export function HabitBookingDialog({ habit, onClose }: HabitBookingDialogProps) {
  const logActivity = useHabitLogStore((s) => s.logActivity);
  // Read a primitive off the store so the selector returns a stable ref
  // (no React #185, see `feedback-zustand-selector-must-return-stable-ref`).
  const timeFormat = useSettingsStore((s) => s.timeFormat);
  const is24Hour = timeFormat === '24h';

  const now = useMemo(() => new Date(), []);
  // Date input + AnalogClockPicker mirror Android's `ActivityLogDialog`
  // shape (date chips + time field + notes textarea).
  const [dateIso, setDateIso] = useState(format(now, 'yyyy-MM-dd'));
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
      // Compose the picked date + clock time into a single wall-clock
      // epoch ms — matches Android's `HabitLogEntity.date` shape. Second
      // is visual-only; drop it before persisting.
      const parsedDate = parseISO(dateIso);
      if (!isValid(parsedDate)) {
        toast.error('Please pick a valid date');
        setSaving(false);
        return;
      }
      parsedDate.setHours(api.state.hour, api.state.minute, 0, 0);
      const dateMs = parsedDate.getTime();
      await logActivity({
        habit_id: habit.id,
        date: dateMs,
        notes: notes.trim() || null,
      });
      toast.success('Activity Booked');
      onClose();
    } catch {
      toast.error('Failed to log activity');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={`Book Habit — ${habit.name}`}
      size="sm"
      footer={
        <div className="flex justify-end gap-3">
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} loading={saving}>
            Save
          </Button>
        </div>
      }
    >
      <div className="flex flex-col items-center gap-5">
        <div className="flex w-full flex-col gap-1.5">
          <label
            htmlFor="habit-booking-date"
            className="text-sm font-medium text-[var(--color-text-primary)]"
          >
            Date
          </label>
          <input
            id="habit-booking-date"
            type="date"
            value={dateIso}
            onChange={(e) => setDateIso(e.target.value)}
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none transition-colors focus:border-[var(--color-accent)] focus:ring-1 focus:ring-[var(--color-accent)]"
          />
        </div>

        <AnalogClockPicker api={api} />

        <div className="flex w-full flex-col gap-1.5">
          <label
            htmlFor="habit-booking-notes"
            className="text-sm font-medium text-[var(--color-text-primary)]"
          >
            Notes
          </label>
          <textarea
            id="habit-booking-notes"
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] placeholder-[var(--color-text-secondary)] outline-none transition-colors focus:border-[var(--color-accent)] focus:ring-1 focus:ring-[var(--color-accent)]"
            placeholder="What did you do? Optional."
            rows={3}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />
        </div>
      </div>
    </Modal>
  );
}
