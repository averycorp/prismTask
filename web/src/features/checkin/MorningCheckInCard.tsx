import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Check, ChevronRight, Flame, Loader2, Sun } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';
import {
  getCheckIn,
  getRecentCheckIns,
  type CheckInLog,
} from '@/api/firestore/checkInLogs';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { useSettingsStore } from '@/stores/settingsStore';
import { useRestDayStore } from '@/stores/restDayStore';
import { useLogicalToday } from '@/utils/useLogicalToday';
import { computeCheckInStreak } from '@/utils/checkInStreak';
import {
  selectForgivenessConfig,
  useAdvancedTuningStore,
} from '@/stores/advancedTuningStore';
import { MorningCheckInStepper } from './MorningCheckInStepper';

/**
 * Today-screen card that prompts the user for a morning check-in and
 * displays a forgiveness-first streak. The card hides itself when the
 * `showMorningCheckIn` preference is off. Tap to open the stepper
 * modal (MOOD_ENERGY → BALANCE → CALENDAR; parity audit C.5a).
 */
export function MorningCheckInCard() {
  const show = useSettingsStore((s) => s.showMorningCheckIn);
  const setSetting = useSettingsStore((s) => s.setSetting);
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const todayIso = useLogicalToday(startOfDayHour);
  // Subscribe to the rest-day set so a fresh mark/unmark re-renders the
  // streak count without remount. The set is folded into
  // `computeCheckInStreak` as kept-by-definition — see
  // `docs/REST_DAY.md` § *The core rule*.
  const restDays = useRestDayStore((s) => s.restDates);
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

  const forgiveness = useAdvancedTuningStore((s) =>
    selectForgivenessConfig(s.prefs),
  );

  if (!show) return null;

  const streak = computeCheckInStreak(recent, todayIso, forgiveness, restDays);

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
              ? 'Logged today. Tap to update mood, balance, or calendar notes.'
              : "Two-minute grounding — mood, balance, then today's plan."}
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

      <Modal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        title="Morning check-in"
        size="md"
      >
        <MorningCheckInStepper
          dateIso={todayIso}
          initial={log}
          onSaved={() => load()}
          onClose={() => setModalOpen(false)}
        />
      </Modal>
    </div>
  );
}
