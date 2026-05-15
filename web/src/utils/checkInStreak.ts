import { addDays, differenceInCalendarDays, parseISO } from 'date-fns';
import type { CheckInLog } from '@/api/firestore/checkInLogs';
import { DEFAULT_FORGIVENESS, type ForgivenessConfig } from '@/utils/streaks';

/**
 * Forgiveness-first streak compute for morning check-ins. Mirrors
 * the Android `DailyForgivenessStreakCore` semantics:
 *
 *   - A single missed day does NOT break the streak; it "bends".
 *   - Two consecutive missed days DO break it.
 *   - A user who checks in today but missed yesterday has a streak
 *     of (longest run since last double-miss).
 *
 * The caller supplies a `today` ISO so this function stays pure and
 * testable. Logs can come in any order; we sort internally.
 *
 * The `forgivenessConfig` parameter accepts the same per-user
 * `gracePeriodDays` + `allowedMisses` knobs Settings → Advanced Tuning
 * exposes. When the config is missing or `enabled: false` we fall back
 * to a strict "any miss breaks the chain" walk. When `enabled: true`
 * the `allowedMisses` value caps how many bends we'll tolerate inside
 * the rolling chain; the previously hardcoded single-bend behaviour is
 * preserved as the `DEFAULT_FORGIVENESS` default.
 */

export interface StreakResult {
  current: number;
  longest: number;
  /** Whether the user has checked in for `today` yet. */
  logged_today: boolean;
  /** ISO date of the day before `today` where the chain would end
   *  if a missed-day today is allowed. Undefined when no streak. */
  last_chain_end: string | null;
}

function isoOnly(iso: string): string {
  // Normalize away any time components if they sneak in; we key by
  // YYYY-MM-DD only.
  return iso.slice(0, 10);
}

export function computeCheckInStreak(
  logs: CheckInLog[],
  todayIso: string,
  forgivenessConfig: ForgivenessConfig = DEFAULT_FORGIVENESS,
): StreakResult {
  const loggedDays = new Set(logs.map((l) => isoOnly(l.date_iso)));
  const today = parseISO(todayIso);
  const loggedToday = loggedDays.has(todayIso);

  // Resolve the per-user knobs. When forgiveness is disabled we hold the
  // walk to a strict "first miss ends the chain" behaviour by capping
  // `allowedMisses` to 0. When enabled we honour the user's allowance.
  const allowed = forgivenessConfig.enabled
    ? Math.max(0, forgivenessConfig.allowedMisses)
    : 0;

  // Walk backwards from today; allow up to `allowed` bends.
  let cursor = today;
  let streak = 0;
  let bendsUsed = 0;
  let lastChainEnd: string | null = null;

  // If the user hasn't logged today, start scanning from yesterday —
  // today being blank is not itself a break yet.
  if (!loggedToday) {
    cursor = addDays(cursor, -1);
  }

  for (let i = 0; i < 400; i += 1) {
    const iso = cursor.toISOString().slice(0, 10);
    if (loggedDays.has(iso)) {
      streak += 1;
      if (lastChainEnd === null) lastChainEnd = iso;
      cursor = addDays(cursor, -1);
    } else if (bendsUsed < allowed) {
      bendsUsed += 1;
      cursor = addDays(cursor, -1);
    } else {
      break;
    }
  }

  // Longest streak: scan forward across the log set.
  const sorted = Array.from(loggedDays).sort();
  let longest = 0;
  let run = 0;
  let longestBends = 0;
  for (let i = 0; i < sorted.length; i += 1) {
    const currentIso = sorted[i];
    const prevIso = sorted[i - 1];
    if (i === 0) {
      run = 1;
      longestBends = 0;
    } else {
      const gap = differenceInCalendarDays(
        parseISO(currentIso),
        parseISO(prevIso),
      );
      if (gap === 1) {
        run += 1;
      } else if (gap > 1 && longestBends + (gap - 1) <= allowed) {
        // Forgive up to `allowed` consecutive missed days inside the run.
        run += 1;
        longestBends += gap - 1;
      } else {
        run = 1;
        longestBends = 0;
      }
    }
    if (run > longest) longest = run;
  }

  return {
    current: streak,
    longest,
    logged_today: loggedToday,
    last_chain_end: lastChainEnd,
  };
}
