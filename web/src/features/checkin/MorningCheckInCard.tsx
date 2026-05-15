import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Check, ChevronRight, Flame, Loader2, Sun } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';
import {
  getCheckIn,
  getRecentCheckIns,
  setCheckIn,
  type CheckInLog,
} from '@/api/firestore/checkInLogs';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { useSettingsStore } from '@/stores/settingsStore';
import { useLogicalToday } from '@/utils/useLogicalToday';
import { computeCheckInStreak } from '@/utils/checkInStreak';

/**
 * Today-screen card that prompts the user for a morning check-in and
 * displays a forgiveness-first streak. The card hides itself when the
 * `showMorningCheckIn` preference is off.
 */
export function MorningCheckInCard() {
  const show = useSettingsStore((s) => s.showMorningCheckIn);
  const setSetting = useSettingsStore((s) => s.setSetting);
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const todayIso = useLogicalToday(startOfDayHour);
  const navigate = useNavigate();

  const [log, setLog] = useState<CheckInLog | null>(null);
  const [recent, setRecent] = useState<CheckInLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);

  const load = useCallback(async () => {
    try {
      const uid = getFirebaseUid();
      setLoading(true);
      const [today, recentLogs] = await Promise.all([
        getCheckIn(uid, todayIso),
        getRecentCheckIns(uid, 90),
      ]);
      setLog(today);
      setRecent(recentLogs);
    } catch {
      // Non-fatal — card will render in its "no data" state.
    } finally {
      setLoading(false);
    }
  }, [todayIso]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load check-in state on mount and when card visibility changes
    if (show) load();
  }, [show, load]);

  if (!show) return null;

  const streak = computeCheckInStreak(recent, todayIso);

  return (
    <div className="mb-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <div className="flex items-start gap-3">
        <Sun
          className="mt-0.5 h-5 w-5 shrink-0 text-[var(--color-accent)]"
          aria-hidden="true"
        />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">
              Morning check-in
            </h3>
            {streak.current > 0 && (
              <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/10 px-2 py-0.5 text-[11px] font-medium text-amber-500">
                <Flame className="h-3 w-3" aria-hidden="true" />
                {streak.current}d streak
              </span>
            )}
            <button
              type="button"
              onClick={() => navigate('/checkin/history')}
              className="ml-auto inline-flex items-center gap-0.5 rounded text-[11px] font-medium text-[var(--color-text-secondary)] hover:text-[var(--color-accent)]"
              aria-label="View 90-day check-in history"
            >
              History
              <ChevronRight className="h-3 w-3" aria-hidden="true" />
            </button>
          </div>
          <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
            {log
              ? 'Logged today. Tap to update or add notes.'
              : "Two-minute grounding — confirm you're set, then dive in."}
          </p>
        </div>
        <div className="flex flex-col items-end gap-1">
          <Button
            size="sm"
            variant={log ? 'secondary' : 'primary'}
            onClick={() => setModalOpen(true)}
            disabled={loading}
          >
            {loading ? (
              <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />
            ) : log ? (
              <Check className="mr-1 h-3.5 w-3.5" />
            ) : null}
            {log ? 'Update' : 'Check in'}
          </Button>
          <button
            onClick={() => setSetting('showMorningCheckIn', false)}
            className="text-[10px] text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
          >
            Hide
          </button>
        </div>
      </div>

      <CheckInModal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        initial={log}
        dateIso={todayIso}
        onSaved={() => load()}
      />
    </div>
  );
}

function CheckInModal({
  isOpen,
  onClose,
  initial,
  dateIso,
  onSaved,
}: {
  isOpen: boolean;
  onClose: () => void;
  initial: CheckInLog | null;
  dateIso: string;
  onSaved: () => void;
}) {
  const [steps, setSteps] = useState(initial?.steps_completed_csv ?? '');
  const [meds, setMeds] = useState(initial?.medications_confirmed ?? false);
  const [tasks, setTasks] = useState(initial?.tasks_reviewed ?? false);
  const [habits, setHabits] = useState(initial?.habits_completed ?? false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- form-init: re-seed edit-buffer state from `initial` prop when the modal opens or the persisted record changes
    setSteps(initial?.steps_completed_csv ?? '');
    setMeds(initial?.medications_confirmed ?? false);
    setTasks(initial?.tasks_reviewed ?? false);
    setHabits(initial?.habits_completed ?? false);
  }, [initial, isOpen]);

  const handleSave = async () => {
    setSaving(true);
    try {
      const uid = getFirebaseUid();
      await setCheckIn(uid, {
        date_iso: dateIso,
        steps_completed_csv: steps.trim(),
        medications_confirmed: meds,
        tasks_reviewed: tasks,
        habits_completed: habits,
      });
      toast.success(initial ? 'Check-in updated' : 'Checked in');
      onSaved();
      onClose();
    } catch (e) {
      toast.error((e as Error).message || 'Check-in failed');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Morning check-in"
      size="sm"
    >
      <div className="flex flex-col gap-3 text-sm">
        <ToggleRow
          label="Medications confirmed"
          description="Took (or am about to take) everything due this morning."
          checked={meds}
          onChange={setMeds}
        />
        <ToggleRow
          label="Tasks reviewed"
          description="Scanned Today and picked what to tackle first."
          checked={tasks}
          onChange={setTasks}
        />
        <ToggleRow
          label="Habits planned"
          description="Know which daily habits I'll hit today."
          checked={habits}
          onChange={setHabits}
        />
        <label>
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
            Notes
          </span>
          <textarea
            value={steps}
            onChange={(e) => setSteps(e.target.value)}
            rows={3}
            placeholder="Free-form — e.g. 'hydrated, stretched, coffee brewing'"
            className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
          />
        </label>
        <div className="mt-2 flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={saving}>
            {saving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            {initial ? 'Update' : 'Check in'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

function ToggleRow({
  label,
  description,
  checked,
  onChange,
}: {
  label: string;
  description: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label className="flex items-start gap-3 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="mt-0.5 h-4 w-4 shrink-0 rounded border-[var(--color-border)] text-[var(--color-accent)]"
      />
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-medium text-[var(--color-text-primary)]">
          {label}
        </span>
        <span className="block text-xs text-[var(--color-text-secondary)]">
          {description}
        </span>
      </span>
    </label>
  );
}
