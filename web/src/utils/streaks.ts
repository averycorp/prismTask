import {
  format,
  subDays,
  startOfWeek,
  endOfWeek,
  eachDayOfInterval,
  differenceInCalendarWeeks,
  isAfter,
  isBefore,
  parseISO,
  startOfDay,
} from 'date-fns';

export interface StreakData {
  currentStreak: number;
  longestStreak: number;
  completionRate7Day: number;
  completionRate30Day: number;
  completionRate90Day: number;
  bestDay: string;
  worstDay: string;
  totalCompletions: number;
}

interface CompletionEntry {
  date: string; // YYYY-MM-DD
  count: number;
}

/**
 * Forgiveness-first daily streak configuration. Mirrors the Android
 * `ForgivenessConfig` data class in
 * `app/src/main/java/com/averycorp/prismtask/domain/usecase/StreakCalculator.kt`.
 *
 * Defaults: a single missed day inside a rolling 7-day window is forgiven
 * (the streak "bends" instead of breaking). Two missed days in the window
 * — or any miss with the grace already spent — terminates the run.
 */
export interface ForgivenessConfig {
  enabled: boolean;
  gracePeriodDays: number;
  allowedMisses: number;
}

/**
 * Default forgiveness-first knobs. Used when the caller doesn't supply a
 * user-configured `ForgivenessConfig` (e.g. signed-out users, or a render
 * pass that fires before the `advancedTuningStore` listener hydrates).
 *
 * Exported so consumers can fall back to the same constant when the
 * Advanced Tuning store reports `loaded: false`. See
 * `web/src/stores/advancedTuningStore.ts` for the user-configured path.
 */
export const DEFAULT_FORGIVENESS: ForgivenessConfig = {
  enabled: true,
  gracePeriodDays: 7,
  allowedMisses: 1,
};

const SAFETY_CAP = 10_000;

const DAY_NAMES = [
  'Sunday',
  'Monday',
  'Tuesday',
  'Wednesday',
  'Thursday',
  'Friday',
  'Saturday',
];

function toDateStr(d: Date): string {
  return format(d, 'yyyy-MM-dd');
}

function isActiveDayOfWeek(date: Date, activeDays: number[] | null): boolean {
  if (!activeDays || activeDays.length === 0) return true;
  // activeDays uses 1=Mon..7=Sun (ISO), JS getDay() returns 0=Sun..6=Sat
  const jsDay = date.getDay();
  const isoDay = jsDay === 0 ? 7 : jsDay;
  return activeDays.includes(isoDay);
}

function countActiveDaysInRange(
  start: Date,
  end: Date,
  activeDays: number[] | null,
): number {
  if (isAfter(start, end)) return 0;
  const days = eachDayOfInterval({ start, end });
  return days.filter((d) => isActiveDayOfWeek(d, activeDays)).length;
}

/**
 * Skip backwards from `cursor` until we land on an active day-of-week, or
 * give up after a hard cap. Mirrors how Android schedules around an
 * `activeDays` whitelist by treating non-active days as "not expected" —
 * neither a met day nor a miss.
 */
function rewindToActiveDay(cursor: Date, activeDays: number[] | null): Date {
  let c = cursor;
  for (let i = 0; i < 14; i += 1) {
    if (isActiveDayOfWeek(c, activeDays)) return c;
    c = subDays(c, 1);
  }
  return c;
}

interface ForgivenessStreakWalk {
  strictStreak: number;
  resilientStreak: number;
  forgivenDates: string[];
}

/**
 * Forgiveness-first daily streak walk, ported from Android's
 * `DailyForgivenessStreakCore.calculate` in
 * `app/src/main/java/com/averycorp/prismtask/domain/usecase/DailyForgivenessStreakCore.kt`.
 *
 * Step-by-step parity with the Android core:
 *  - Mid-day rule: if `today` isn't met yet, drop the cursor to yesterday
 *    before starting (don't penalize the user for not logging yet).
 *  - Hard reset: if both today AND yesterday (the start cursor) are misses,
 *    the resilient run has already broken — return 0.
 *  - While walking backwards: a met day extends the streak; a miss is
 *    tolerable iff fewer than `allowedMisses` misses already exist in the
 *    rolling `gracePeriodDays` window anchored at the cursor (extending
 *    forward). Once the window is full, the next miss terminates the walk.
 *  - Walk stops at the earliest known activity day so pre-history days
 *    aren't counted as misses.
 *
 * Web addition: when `activeDays` is supplied, non-active weekdays are
 * skipped entirely (not counted as met or missed) — matches the existing
 * behavior of `calculateStreaks` and how the Habits UI treats partial-week
 * habits like "weekdays only".
 */
function forgivenessDailyWalk(
  completionMap: Map<string, number>,
  today: Date,
  targetCount: number,
  activeDays: number[] | null,
  config: ForgivenessConfig,
): ForgivenessStreakWalk {
  // Strict walk first — also doubles as the early-out value for the
  // "forgiveness disabled" config.
  const strictStreak = strictDailyWalk(
    completionMap,
    today,
    targetCount,
    activeDays,
  );

  if (!config.enabled) {
    return { strictStreak, resilientStreak: strictStreak, forgivenDates: [] };
  }

  // No completions at all → no run, no grace to spend.
  if (completionMap.size === 0) {
    return { strictStreak: 0, resilientStreak: 0, forgivenDates: [] };
  }

  const allowed = Math.max(0, config.allowedMisses);
  const window = Math.max(1, config.gracePeriodDays);

  const isMet = (d: Date): boolean =>
    (completionMap.get(toDateStr(d)) || 0) >= targetCount;

  // Earliest known activity — walk halts here so pre-history isn't punished.
  const allDates = Array.from(completionMap.keys())
    .filter((iso) => (completionMap.get(iso) || 0) >= targetCount)
    .sort();
  if (allDates.length === 0) {
    return { strictStreak, resilientStreak: 0, forgivenDates: [] };
  }
  const earliest = parseISO(allDates[0]);

  // Mid-day rule: if today isn't met (or today is not an active day), step
  // back to the most recent active day before evaluating.
  let start = today;
  if (isActiveDayOfWeek(start, activeDays) && !isMet(start)) {
    start = subDays(start, 1);
  }
  start = rewindToActiveDay(start, activeDays);

  // Hard reset: if the start cursor itself is a miss, the resilient run is
  // already broken regardless of historical run length. (Strict still
  // reflects whatever the prefix walk found.)
  if (!isMet(start)) {
    return { strictStreak, resilientStreak: 0, forgivenDates: [] };
  }

  let cursor = start;
  let metDays = 0;
  const missDates: Date[] = [];

  for (let i = 0; i < SAFETY_CAP; i += 1) {
    if (!isActiveDayOfWeek(cursor, activeDays)) {
      // Non-active weekday: neither met nor miss — slide past it.
      cursor = subDays(cursor, 1);
      if (isBefore(cursor, earliest)) break;
      continue;
    }
    if (isMet(cursor)) {
      metDays += 1;
    } else {
      // Rolling window (anchored at cursor, extending forward):
      // count misses already inside [cursor, cursor + window-1].
      const windowEnd = subDays(cursor, -(window - 1));
      let priorMissesInWindow = 0;
      for (const m of missDates) {
        if (!isBefore(m, cursor) && !isAfter(m, windowEnd)) {
          priorMissesInWindow += 1;
        }
      }
      if (priorMissesInWindow >= allowed) break;
      missDates.push(cursor);
    }
    if (metDays + missDates.length > SAFETY_CAP) break;
    cursor = subDays(cursor, 1);
    if (isBefore(cursor, earliest)) break;
  }

  const resilient = metDays + missDates.length;
  return {
    strictStreak,
    resilientStreak: resilient,
    forgivenDates: missDates.map(toDateStr),
  };
}

/**
 * Classic "consecutive met days, break on first miss" walk. Anchors at
 * today (or yesterday if today isn't logged yet) and walks backwards,
 * skipping non-active weekdays. Mirrors
 * `DailyForgivenessStreakCore.strictWalk` exactly.
 */
function strictDailyWalk(
  completionMap: Map<string, number>,
  today: Date,
  targetCount: number,
  activeDays: number[] | null,
): number {
  if (completionMap.size === 0) return 0;

  let cursor = today;
  if (isActiveDayOfWeek(cursor, activeDays)) {
    const todayCount = completionMap.get(toDateStr(cursor)) || 0;
    if (todayCount < targetCount) {
      cursor = subDays(cursor, 1);
    }
  }
  cursor = rewindToActiveDay(cursor, activeDays);

  let streak = 0;
  for (let i = 0; i < SAFETY_CAP; i += 1) {
    if (!isActiveDayOfWeek(cursor, activeDays)) {
      cursor = subDays(cursor, 1);
      continue;
    }
    const count = completionMap.get(toDateStr(cursor)) || 0;
    if (count >= targetCount) {
      streak += 1;
      cursor = subDays(cursor, 1);
    } else {
      break;
    }
    // Safety: don't go back more than 2 years
    if (differenceInCalendarWeeks(today, cursor) > 104) break;
  }
  return streak;
}

export function calculateStreaks(
  completions: CompletionEntry[],
  frequency: 'daily' | 'weekly',
  activeDays: number[] | null,
  targetCount: number,
  /**
   * Optional per-user forgiveness knobs (Settings → Advanced Tuning).
   * When omitted the function falls back to the same defaults Android's
   * `DailyForgivenessStreakCore` ships with, so signed-out users and
   * mid-bootstrap renders behave identically.
   */
  forgivenessConfig: ForgivenessConfig = DEFAULT_FORGIVENESS,
): StreakData {
  const today = startOfDay(new Date());
  const completionMap = new Map<string, number>();

  for (const c of completions) {
    const existing = completionMap.get(c.date) || 0;
    completionMap.set(c.date, existing + c.count);
  }

  const totalCompletions = completions.reduce((sum, c) => sum + c.count, 0);

  // Day-of-week totals for best/worst day
  const dayTotals = [0, 0, 0, 0, 0, 0, 0]; // Sun-Sat
  for (const c of completions) {
    const date = parseISO(c.date);
    dayTotals[date.getDay()] += c.count;
  }

  // Filter to only active days for best/worst
  const activeDayIndices: number[] = [];
  if (activeDays && activeDays.length > 0) {
    for (const iso of activeDays) {
      activeDayIndices.push(iso === 7 ? 0 : iso); // ISO 7=Sun → JS 0
    }
  } else {
    for (let i = 0; i < 7; i++) activeDayIndices.push(i);
  }

  let bestDayIdx = activeDayIndices[0];
  let worstDayIdx = activeDayIndices[0];
  for (const idx of activeDayIndices) {
    if (dayTotals[idx] > dayTotals[bestDayIdx]) bestDayIdx = idx;
    if (dayTotals[idx] < dayTotals[worstDayIdx]) worstDayIdx = idx;
  }

  if (frequency === 'weekly') {
    return calculateWeeklyStreaks(
      completionMap,
      targetCount,
      today,
      totalCompletions,
      DAY_NAMES[bestDayIdx],
      DAY_NAMES[worstDayIdx],
    );
  }

  // Daily frequency — forgiveness-first current streak (parity with
  // Android's DailyForgivenessStreakCore). Longest stays strict-consecutive
  // to match Android's StreakCalculator.calculateLongestStreak (default
  // maxMissedDays = 1, which breaks the run on the first miss).
  const walk = forgivenessDailyWalk(
    completionMap,
    today,
    targetCount,
    activeDays,
    forgivenessConfig,
  );
  const currentStreak = walk.resilientStreak;

  let longestStreak = 0;
  let tempStreak = 0;

  // Calculate longest streak by scanning all dates from earliest to latest.
  // Strict consecutive (forgiveness intentionally omitted, same as Android).
  const allDates = Array.from(completionMap.keys()).sort();
  if (allDates.length > 0) {
    const earliest = parseISO(allDates[0]);
    const latest = today;
    const days = eachDayOfInterval({ start: earliest, end: latest });

    for (const day of days) {
      if (!isActiveDayOfWeek(day, activeDays)) continue;
      const dateStr = toDateStr(day);
      const count = completionMap.get(dateStr) || 0;
      if (count >= targetCount) {
        tempStreak++;
        longestStreak = Math.max(longestStreak, tempStreak);
      } else {
        tempStreak = 0;
      }
    }
  }

  longestStreak = Math.max(longestStreak, currentStreak);

  // Completion rates
  const completionRate7Day = calcCompletionRate(completionMap, today, 7, activeDays, targetCount);
  const completionRate30Day = calcCompletionRate(completionMap, today, 30, activeDays, targetCount);
  const completionRate90Day = calcCompletionRate(completionMap, today, 90, activeDays, targetCount);

  return {
    currentStreak,
    longestStreak,
    completionRate7Day,
    completionRate30Day,
    completionRate90Day,
    bestDay: DAY_NAMES[bestDayIdx],
    worstDay: DAY_NAMES[worstDayIdx],
    totalCompletions,
  };
}

function calcCompletionRate(
  completionMap: Map<string, number>,
  today: Date,
  days: number,
  activeDays: number[] | null,
  targetCount: number,
): number {
  const start = subDays(today, days - 1);
  const totalActive = countActiveDaysInRange(start, today, activeDays);
  if (totalActive === 0) return 0;

  let completed = 0;
  const interval = eachDayOfInterval({ start, end: today });
  for (const day of interval) {
    if (!isActiveDayOfWeek(day, activeDays)) continue;
    const count = completionMap.get(toDateStr(day)) || 0;
    if (count >= targetCount) completed++;
  }

  return completed / totalActive;
}

function calculateWeeklyStreaks(
  completionMap: Map<string, number>,
  targetCount: number,
  today: Date,
  totalCompletions: number,
  bestDay: string,
  worstDay: string,
): StreakData {
  // For weekly habits, a "streak" is consecutive weeks where
  // total completions >= targetCount
  function weekTotal(weekStart: Date): number {
    const weekEnd = endOfWeek(weekStart, { weekStartsOn: 1 });
    const days = eachDayOfInterval({ start: weekStart, end: weekEnd });
    let total = 0;
    for (const d of days) {
      total += completionMap.get(toDateStr(d)) || 0;
    }
    return total;
  }

  const thisWeekStart = startOfWeek(today, { weekStartsOn: 1 });

  // Current streak
  let currentStreak = 0;
  let checkWeek = thisWeekStart;
  // If current week not met yet, start from previous week
  if (weekTotal(checkWeek) < targetCount) {
    checkWeek = subDays(checkWeek, 7);
  }
  while (true) {
    if (weekTotal(checkWeek) >= targetCount) {
      currentStreak++;
      checkWeek = subDays(checkWeek, 7);
    } else {
      break;
    }
    if (differenceInCalendarWeeks(today, checkWeek) > 104) break;
  }

  // Longest streak
  let longestStreak = 0;
  let tempStreak = 0;
  // Go back 52 weeks
  for (let i = 52; i >= 0; i--) {
    const ws = subDays(thisWeekStart, i * 7);
    if (weekTotal(ws) >= targetCount) {
      tempStreak++;
      longestStreak = Math.max(longestStreak, tempStreak);
    } else {
      tempStreak = 0;
    }
  }
  longestStreak = Math.max(longestStreak, currentStreak);

  // Completion rates for weekly: use weeks
  const completionRate7Day = weekTotal(thisWeekStart) >= targetCount ? 1 : 0;

  let weeksCompleted30 = 0;
  for (let i = 0; i < 4; i++) {
    if (weekTotal(subDays(thisWeekStart, i * 7)) >= targetCount) weeksCompleted30++;
  }
  const completionRate30Day = weeksCompleted30 / 4;

  let weeksCompleted90 = 0;
  for (let i = 0; i < 13; i++) {
    if (weekTotal(subDays(thisWeekStart, i * 7)) >= targetCount) weeksCompleted90++;
  }
  const completionRate90Day = weeksCompleted90 / 13;

  return {
    currentStreak,
    longestStreak,
    completionRate7Day,
    completionRate30Day,
    completionRate90Day,
    bestDay,
    worstDay,
    totalCompletions,
  };
}

/**
 * Build a map of date → completion count for the last N days.
 * Useful for contribution grids and calendars.
 */
export function buildCompletionGrid(
  completions: CompletionEntry[],
  days: number,
): Map<string, number> {
  const grid = new Map<string, number>();
  const today = startOfDay(new Date());
  for (let i = days - 1; i >= 0; i--) {
    grid.set(toDateStr(subDays(today, i)), 0);
  }
  for (const c of completions) {
    if (grid.has(c.date)) {
      grid.set(c.date, (grid.get(c.date) || 0) + c.count);
    }
  }
  return grid;
}
