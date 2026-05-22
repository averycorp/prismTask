import { useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useLeisureStore } from '@/stores/leisureStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { useLogicalToday } from '@/utils/useLogicalToday';

/**
 * Web port of `app/.../ui/screens/today/components/TodayLeisureMinimumRow.kt`.
 * Shows a compact progress row toward the user's daily leisure minimum.
 * Hides when no target is configured. Tapping routes to `/leisure`.
 *
 * Mirrors PR #1313's behavior: tap switches the active route rather than
 * overlaying — `useNavigate` is the web analog.
 */
export function TodayLeisureMinimumRow() {
  const navigate = useNavigate();
  const fetchAll = useLeisureStore((s) => s.fetchAll);
  const settings = useLeisureStore((s) => s.settings);
  const sessions = useLeisureStore((s) => s.sessions);
  const getMinutesLoggedToday = useLeisureStore((s) => s.getMinutesLoggedToday);
  const getTargetMinutesToday = useLeisureStore((s) => s.getTargetMinutesToday);
  // Subscribed for re-render so the per-day window recomputes when a
  // cross-device Start-of-Day update lands (matches Android's
  // `DayBoundary`-driven `LeisureBudgetTracker`).
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  // Reactive day-boundary crossing: todayIso flips when the wall clock
  // passes startOfDayHour, forcing the useMemo below to recompute so
  // yesterday's leisure minutes don't stick around.
  const todayIso = useLogicalToday(startOfDayHour);

  useEffect(() => {
    // Cheap fan-out: only fetch on mount if we haven't seen any state yet.
    // The Leisure screen does its own full refresh on entry, so re-mount on
    // the Today screen doesn't need to hammer the API.
    if (!settings) fetchAll();
  }, [settings, fetchAll]);

  const minutesLogged = useMemo(
    () => getMinutesLoggedToday(),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [getMinutesLoggedToday, sessions, startOfDayHour, todayIso],
  );
  const targetMinutes = getTargetMinutesToday();

  if (targetMinutes <= 0) return null;

  const fraction = Math.min(1, minutesLogged / targetMinutes);
  const percent = Math.round(fraction * 100);
  const targetHit = minutesLogged >= targetMinutes;

  return (
    <button
      type="button"
      onClick={() => navigate('/leisure')}
      className="flex w-full flex-col gap-1.5 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3 text-left transition-colors hover:border-[var(--color-accent)]/50"
      aria-label={`Leisure minimum: ${percent}% of ${targetMinutes} min today`}
    >
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-[var(--color-text-primary)]">
          Leisure Minimum
        </span>
        <span
          className={
            targetHit
              ? 'text-sm font-semibold text-[var(--color-accent)]'
              : 'text-sm font-semibold text-[var(--color-text-primary)]'
          }
        >
          {percent}%
        </span>
      </div>
      <div className="h-1.5 overflow-hidden rounded bg-[var(--color-bg-tertiary)]">
        <div
          className="h-full rounded bg-[var(--color-accent)] transition-all"
          style={{ width: `${percent}%` }}
        />
      </div>
      <span className="text-xs text-[var(--color-text-secondary)]">
        {targetHit
          ? `${minutesLogged} / ${targetMinutes} min · Minimum Hit`
          : `${minutesLogged} / ${targetMinutes} min`}
      </span>
    </button>
  );
}

export default TodayLeisureMinimumRow;
