import { useMemo, useState } from 'react';
import { format } from 'date-fns';
import { toast } from 'sonner';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { useHabitLogStore } from '@/stores/habitLogStore';
import type { Habit } from '@/types/habit';

interface HabitBookingDialogProps {
  habit: Habit;
  onClose: () => void;
}

/**
 * Book Activity dialog for `isBookable` habits. Mirrors Android's
 * `HabitDetailViewModel.onLogActivity` entry point — captures a
 * date-time + optional note and writes a `habit_logs` row.
 *
 * Parity audit § B.3b. Web doesn't model the `isBooked` /
 * `bookedDate` / `bookedNote` flags on the habit row itself yet (the
 * web `habitCreateToDoc` payload intentionally omits them, see
 * `web/src/api/firestore/habits.ts` § "Why omission instead of
 * writing-defaults"), so this dialog only writes the log row. Android
 * keeps the booking-state habit flags as a UI-side cache derived from
 * the latest log; web pulls the same picture from `habitLogStore`.
 */
export function HabitBookingDialog({ habit, onClose }: HabitBookingDialogProps) {
  const logActivity = useHabitLogStore((s) => s.logActivity);

  const now = useMemo(() => new Date(), []);
  // `<input type="datetime-local">` wants `YYYY-MM-DDTHH:mm`.
  const [whenLocal, setWhenLocal] = useState(format(now, "yyyy-MM-dd'T'HH:mm"));
  const [notes, setNotes] = useState('');
  const [saving, setSaving] = useState(false);

  const handleSubmit = async () => {
    setSaving(true);
    try {
      // datetime-local parses as local time — `new Date(value)` in the
      // browser converts to the wall-clock epoch ms we need to match
      // Android's `HabitLogEntity.date` shape.
      const dateMs = new Date(whenLocal).getTime();
      if (!Number.isFinite(dateMs)) {
        toast.error('Please pick a valid date and time');
        setSaving(false);
        return;
      }
      await logActivity({
        habit_id: habit.id,
        date: dateMs,
        notes: notes.trim() || null,
      });
      toast.success('Activity logged');
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
      title={`Book Activity — ${habit.name}`}
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
      <div className="flex flex-col gap-5">
        <Input
          label="When"
          type="datetime-local"
          value={whenLocal}
          onChange={(e) => setWhenLocal(e.target.value)}
          autoFocus
        />

        <div className="flex flex-col gap-1.5">
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
