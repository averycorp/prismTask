import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CalendarCheck, ChevronLeft, Loader2, Sun } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import {
  getCheckIn,
  type CheckInLog,
} from '@/api/firestore/checkInLogs';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { useSettingsStore } from '@/stores/settingsStore';
import { useLogicalToday } from '@/utils/useLogicalToday';
import { MorningCheckInStepper } from './MorningCheckInStepper';

/**
 * Routed standalone Morning Check-In screen (parity unit 11).
 *
 * Web previously only exposed the stepper inside a modal on the Today
 * card. Android opens the equivalent flow as its own full-screen
 * navigation destination (`MorningCheckInScreen.kt`), so this screen
 * wraps the existing `MorningCheckInStepper` in a route-friendly shell
 * with a top bar, History link, and "back to Today" exit.
 *
 * The stepper itself is unchanged — both the Today-card modal *and*
 * this routed screen call into it so draft persistence (the
 * `checkin:draft:${dateIso}` localStorage key) is shared.
 */
export function MorningCheckInScreen() {
  const navigate = useNavigate();
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const todayIso = useLogicalToday(startOfDayHour);

  const [log, setLog] = useState<CheckInLog | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const uid = getFirebaseUid();
      const today = await getCheckIn(uid, todayIso);
      setLog(today);
    } catch {
      // Non-fatal — render the stepper anyway; submit will retry the
      // network round-trip.
    } finally {
      setLoading(false);
    }
  }, [todayIso]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: hydrate the existing same-day log if any
    void load();
  }, [load]);

  const handleClose = useCallback(() => {
    navigate('/');
  }, [navigate]);

  return (
    <div
      className="mx-auto max-w-2xl pb-12"
      data-testid="morning-checkin-screen"
    >
      <header className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => navigate('/')}
            aria-label="Back to Today"
          >
            <ChevronLeft className="mr-1 h-4 w-4" />
            Back
          </Button>
          <div className="flex items-center gap-2">
            <Sun
              className="h-5 w-5 text-[var(--color-accent)]"
              aria-hidden="true"
            />
            <h1 className="text-xl font-semibold text-[var(--color-text-primary)]">
              Morning Check-In
            </h1>
          </div>
        </div>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => navigate('/checkin/history')}
        >
          <CalendarCheck className="mr-1 h-4 w-4" aria-hidden="true" />
          History
        </Button>
      </header>

      <p className="mb-4 text-sm text-[var(--color-text-secondary)]">
        Three quick steps — mood, balance, then today's plan. Each is
        optional; tap Skip to bow out.
      </p>

      {loading ? (
        <div className="flex items-center justify-center gap-2 py-12 text-sm text-[var(--color-text-secondary)]">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
          Loading…
        </div>
      ) : (
        <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
          <MorningCheckInStepper
            dateIso={todayIso}
            initial={log}
            onSaved={() => void load()}
            onClose={handleClose}
          />
        </div>
      )}
    </div>
  );
}
