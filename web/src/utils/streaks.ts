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
 *
 * The optional `byMode` field carries per-`TaskMode` overrides so the
 * streak path can apply a wider grace window to Play / Relax activities
 * than to Work — see `docs/WORK_PLAY_RELAX.md` § *Streak strictness*.
 * When `byMode` is omitted (or the mode is missing from it), callers
 * fall back to the base `gracePeriodDays` + `allowedMisses` fields. The
 * shape stays backward-compatible: every existing caller that passes a
 * flat `{enabled, gracePeriodDays, allowedMisses}` keeps working without
 * change — `byMode` is purely additive.
 */
export interface ForgivenessConfig {
  enabled: boolean;
  gracePeriodDays: number;
  allowedMisses: number;
  /**
   * Optional per-`TaskMode` overrides. Each entry replaces the
   * `gracePeriodDays` + `allowedMisses` pair for the matching mode while
   * still respecting the top-level `enabled` flag (the "forgiveness on
   * / off" master switch is one master switch, not per-mode). Modes not
   * listed here fall back to the base config — so a single override on
   * Play does NOT disturb Work or Relax. See `resolveForgivenessForMode`
   * for the resolution rule callers use.
   */
  byMode?: ForgivenessByMode;
}

/**
 * Per-`TaskMode` override map. Keys are lowercased mode names so callers
 * can write `byMode.play` without coercing the `TaskMode` enum into a
 * specific casing first. Only Work / Play / Relax can be overridden —
 * `UNCATEGORIZED` always reads the base config (the "lean toward the
 * restorative read" tie-break shape from `WORK_PLAY_RELAX.md` § *Inference
 * rules* — an unknown mode never auto-upgrades to wider grace).
 */
export interface ForgivenessByMode {
  work?: ForgivenessKnobs;
  play?: ForgivenessKnobs;
  relax?: ForgivenessKnobs;
}

/**
 * The two scalar knobs that constitute a forgiveness window. Used as
 * the value type inside `ForgivenessByMode` so a per-mode override only
 * has to carry the window + miss count, not the entire config.
 */
export interface ForgivenessKnobs {
  gracePeriodDays: number;
  allowedMisses: number;
}

/**
 * Task mode literal type — mirrors `web/src/types/task.ts`'s `TaskMode`.
 * Duplicated here so `streaks.ts` doesn't have to depend on `@/types` and
 * stay portable as a pure utility module. Keep both in sync.
 */
export type StreakTaskMode = 'WORK' | 'PLAY' | 'RELAX' | 'UNCATEGORIZED';

/**
 * Wider default forgiveness knobs for Play / Relax modes
 * (`docs/WORK_PLAY_RELAX.md` § *Streak strictness*). Doubles both
 * dimensions of the base default (7 days / 1 miss) — Play and Relax get
 * a 14-day rolling window with 2 allowed misses by default.
 *
 * Rationale: Work tasks tend to have external deadlines or contractual
 * shape that survives a single off day intact. Play and Relax tasks are
 * user-chosen and self-paced; tightening their streaks creates
 * guilt-by-streak, which is exactly the failure mode forgiveness-first
 * exists to avoid. Doubling both dimensions (rather than just one) gives
 * a perceptibly wider window without inflating the absorption ratio so
 * far it stops feeling like a streak.
 *
 * The user can override per-mode via Settings → Advanced Tuning.
 */
export const DEFAULT_PLAY_RELAX_FORGIVENESS: ForgivenessKnobs = {
  gracePeriodDays: 14,
  allowedMisses: 2,
};

/**
 * Default forgiveness-first knobs. Used when the caller doesn't supply a
 * user-configured `ForgivenessConfig` (e.g. signed-out users, or a render
 * pass that fires before the `advancedTuningStore` listener hydrates).
 *
 * Exported so consumers can fall back to the same constant when the
 * Advanced Tuning store reports `loaded: false`. See
 * `web/src/stores/advancedTuningStore.ts` for the user-configured path.
 *
 * The `byMode` field defaults to Work using the base knobs and Play /
 * Relax both getting `DEFAULT_PLAY_RELAX_FORGIVENESS`. Existing flat
 * callers that pass `{enabled, gracePeriodDays, allowedMisses}` keep
 * working — `byMode` is read only when a `taskMode` is also threaded
 * through to the streak call.
 */
export const DEFAULT_FORGIVENESS: ForgivenessConfig = {
  enabled: true,
  gracePeriodDays: 7,
  allowedMisses: 1,
  byMode: {
    work: { gracePeriodDays: 7, allowedMisses: 1 },
    play: { ...DEFAULT_PLAY_RELAX_FORGIVENESS },
    relax: { ...DEFAULT_PLAY_RELAX_FORGIVENESS },
  },
};

/**
 * Resolve the effective forgiveness knobs for a given task mode. Reads
 * `byMode[mode]` when present; falls back to the base
 * `{gracePeriodDays, allowedMisses}` fields otherwise. The top-level
 * `enabled` flag is always honored — a per-mode override can NOT turn
 * forgiveness on for one mode while it's off globally.
 *
 * `UNCATEGORIZED` (and any falsy / unknown mode) always reads the base
 * config. The shame-avoidance lean from `WORK_PLAY_RELAX.md` § *Inference
 * rules* applies here: an unknown mode never auto-upgrades to wider
 * grace — the system never assumes Play/Relax on the user's behalf.
 *
 * Returns a `ForgivenessConfig` (not just knobs) so callers can pass
 * the result straight into `forgivenessDailyWalk` without further
 * unwrapping. The `byMode` field on the returned value is intentionally
 * dropped — once the mode is resolved there's nothing left to walk.
 */
export function resolveForgivenessForMode(
  config: ForgivenessConfig,
  mode: StreakTaskMode | null | undefined,
): ForgivenessConfig {
  if (!mode || mode === 'UNCATEGORIZED' || !config.byMode) {
    return {
      enabled: config.enabled,
      gracePeriodDays: config.gracePeriodDays,
      allowedMisses: config.allowedMisses,
    };
  }
  const key = mode.toLowerCase() as 'work' | 'play' | 'relax';
  const override = config.byMode[key];
  if (!override) {
    return {
      enabled: config.enabled,
      gracePeriodDays: config.gracePeriodDays,
      allowedMisses: config.allowedMisses,
    };
  }
  return {
    enabled: config.enabled,
    gracePeriodDays: override.gracePeriodDays,
    allowedMisses: override.allowedMisses,
  };
}

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
 * Rest-day fold (`docs/REST_DAY.md` § *The core rule*, mirror of
 * `DailyForgivenessStreakCore.calculate(restDays = ...)`): days the user
 * explicitly marked as a rest day are treated as "kept by definition" —
 * they extend the run, do NOT consume the grace window, and do NOT count
 * as misses. The seam is the cleanest possible: fold `restDays` into the
 * effective met-day set before the walk starts, so every existing branch
 * (mid-day rule, hard reset, rolling-window check, earliest-activity
 * halt) behaves identically. Empty `restDays` is the default so existing
 * call sites see no behaviour change.
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
  restDays: Set<string> = new Set<string>(),
): ForgivenessStreakWalk {
  // Strict walk first — also doubles as the early-out value for the
  // "forgiveness disabled" config. Rest days count as met here too —
  // strict-walk also sees rest days as kept, matching Android's
  // `effectiveActivity` fold (resting still counts).
  const strictStreak = strictDailyWalk(
    completionMap,
    today,
    targetCount,
    activeDays,
    restDays,
  );

  if (!config.enabled) {
    return { strictStreak, resilientStreak: strictStreak, forgivenDates: [] };
  }

  // No completions at all and no rest days → no run, no grace to spend.
  if (completionMap.size === 0 && restDays.size === 0) {
    return { strictStreak: 0, resilientStreak: 0, forgivenDates: [] };
  }

  const allowed = Math.max(0, config.allowedMisses);
  const window = Math.max(1, config.gracePeriodDays);

  // A day is "met" iff the user logged enough completions OR the user
  // explicitly marked it as a rest day. Rest-day-as-met is the seam that
  // makes rest days "kept by definition" without consuming the grace
  // window — the walk below never reaches the miss branch on a rest day.
  const isMet = (d: Date): boolean => {
    const iso = toDateStr(d);
    if (restDays.has(iso)) return true;
    return (completionMap.get(iso) || 0) >= targetCount;
  };

  // Earliest known activity — walk halts here so pre-history isn't punished.
  // Rest days are folded in too: a rest day before the earliest completion
  // is still "earliest known", matching Android's
  // `effectiveActivity.minOrNull()` semantics (resting counts as being there).
  const metDates: string[] = [];
  for (const [iso, count] of completionMap.entries()) {
    if (count >= targetCount) metDates.push(iso);
  }
  for (const iso of restDays) metDates.push(iso);
  metDates.sort();
  if (metDates.length === 0) {
    return { strictStreak, resilientStreak: 0, forgivenDates: [] };
  }
  const earliest = parseISO(metDates[0]);

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
 *
 * Rest-day fold (`docs/REST_DAY.md`): a day in `restDays` is treated as
 * met here too — same as Android's `effectiveActivity` fold inside
 * `DailyForgivenessStreakCore.calculate`. "Resting still counts" applies
 * to the strict walk as well as the resilient walk.
 */
function strictDailyWalk(
  completionMap: Map<string, number>,
  today: Date,
  targetCount: number,
  activeDays: number[] | null,
  restDays: Set<string> = new Set<string>(),
): number {
  if (completionMap.size === 0 && restDays.size === 0) return 0;

  const isMet = (iso: string): boolean => {
    if (restDays.has(iso)) return true;
    return (completionMap.get(iso) || 0) >= targetCount;
  };

  let cursor = today;
  if (isActiveDayOfWeek(cursor, activeDays)) {
    if (!isMet(toDateStr(cursor))) {
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
    if (isMet(toDateStr(cursor))) {
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

/**
 * Compute streak stats for a habit-like activity.
 *
 * @param forgivenessConfig Optional per-user forgiveness knobs
 *   (Settings → Advanced Tuning). When omitted, the function falls
 *   back to the same defaults Android's `DailyForgivenessStreakCore`
 *   ships with, so signed-out users and mid-bootstrap renders behave
 *   identically.
 * @param restDays Optional ISO `yyyy-MM-dd` set of dates the user
 *   explicitly marked as rest days. Treated as kept-by-definition for
 *   the daily forgiveness walk — they extend the run without consuming
 *   the rolling grace cap. Empty by default so existing call sites
 *   behave identically. See `docs/REST_DAY.md` § *The core rule*.
 * @param taskMode Optional `TaskMode` for the activity. When provided
 *   and `forgivenessConfig.byMode` carries an override for that mode,
 *   the streak walk applies the wider window (`docs/WORK_PLAY_RELAX.md`
 *   § *Streak strictness*). When omitted, missing, or `UNCATEGORIZED`,
 *   the walk uses the base config — never auto-upgrades to wider grace.
 *   Habits don't carry a mode in either platform's schema today, so
 *   habit callers leave this undefined; task-completion streak surfaces
 *   that ship later will pass the task's mode through.
 */
export function calculateStreaks(
  completions: CompletionEntry[],
  frequency: 'daily' | 'weekly',
  activeDays: number[] | null,
  targetCount: number,
  forgivenessConfig: ForgivenessConfig = DEFAULT_FORGIVENESS,
  restDays: Set<string> = new Set<string>(),
  taskMode?: StreakTaskMode | null,
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
  // maxMissedDays = 1, which breaks the run on the first miss). Rest
  // days fold in as kept-by-definition — see the walk's docstring.
  // `forgivenessConfig` carries the per-user grace knobs from
  // Settings → Advanced Tuning; the default mirrors Android.
  //
  // Mode-aware leniency (`docs/WORK_PLAY_RELAX.md` § *Streak strictness*):
  // resolve the config against `taskMode` before walking — Work falls
  // through to the base knobs, Play / Relax pick up `byMode` overrides
  // when present. Habit callers (no mode) pass `taskMode=undefined`,
  // which short-circuits to the base config — habit streak strictness is
  // unchanged.
  const resolvedConfig = resolveForgivenessForMode(forgivenessConfig, taskMode);
  const walk = forgivenessDailyWalk(
    completionMap,
    today,
    targetCount,
    activeDays,
    resolvedConfig,
    restDays,
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
