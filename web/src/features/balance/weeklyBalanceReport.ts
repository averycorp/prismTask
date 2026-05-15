import type { LifeCategory, Task } from '@/types/task';
import {
  TRACKED_CATEGORIES,
  type BalanceConfig,
  configToMap,
} from '@/utils/balanceTracker';
import { computeWeekWindow as computeAggregatorWindow } from '@/features/weeklyreview/weeklyAggregator';

/**
 * Pure aggregator for the Weekly Balance Report (parity audit C.2c).
 *
 * Mirrors the relevant pieces of Android's `WeeklyBalanceReportViewModel`
 * (4-week trend + this-vs-last delta + per-day breakdown + overload-day
 * count). Kept as pure functions so the screen stays thin and the math
 * is easy to test in isolation.
 */

/** Sunday=0 .. Saturday=6 — matches `Date.getDay()`. */
export const DAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

export interface WeeklyWindow {
  /** Epoch ms at Monday 00:00 local time. */
  startMs: number;
  /** Epoch ms at Sunday 23:59:59.999 local time. Inclusive. */
  endMs: number;
}

export interface WeeklyBalanceReport {
  /** Selected week. */
  window: WeeklyWindow;
  /** Tasks counted (completed, with a tracked life_category, inside the window). */
  totalTracked: number;
  /** Ratio per category for the selected week. */
  currentRatios: Record<LifeCategory, number>;
  /** Absolute counts per category for the selected week. */
  currentCounts: Record<LifeCategory, number>;
  /** Ratio per category for the prior week (for the delta panel). */
  priorRatios: Record<LifeCategory, number>;
  /** Absolute counts per category for the prior week. */
  priorCounts: Record<LifeCategory, number>;
  /** Per-day breakdown for the selected week, indexed Mon..Sun. */
  perDay: PerDayBreakdown[];
  /**
   * Number of days in the selected week where the WORK ratio exceeded
   * `workTarget + overloadThreshold`. Days with no tracked work-or-
   * other completions don't count.
   */
  overloadDays: number;
  /** 4-week ratio trend per category, oldest → newest (length 4). */
  fourWeekTrend: Record<LifeCategory, number[]>;
  /** 4-week absolute count trend per category, oldest → newest (length 4). */
  fourWeekCounts: Record<LifeCategory, number[]>;
  /** Configured targets (ratios 0..1) for the comparison panel. */
  targetRatios: Record<LifeCategory, number>;
  /** Overload threshold from config (ratio 0..1). */
  overloadThreshold: number;
}

export interface PerDayBreakdown {
  /** Day label, Mon..Sun. */
  label: string;
  /** Date of this day at local midnight. */
  date: Date;
  /** Per-category count for this day. */
  counts: Record<LifeCategory, number>;
  /** Total tracked completions this day. */
  total: number;
  /** True when WORK ratio exceeded `workTarget + overloadThreshold` and total > 0. */
  isOverloaded: boolean;
}

/**
 * Monday-of-week window for the given reference date, in the user's local
 * timezone. Delegates to `weeklyAggregator.computeWeekWindow` so navigation
 * matches Weekly Review (chevrons walk the same Monday/Sunday boundaries).
 */
export function computeWeekWindow(reference: Date | number = new Date()): WeeklyWindow {
  const ref = typeof reference === 'number' ? new Date(reference) : reference;
  const agg = computeAggregatorWindow(ref);
  return { startMs: agg.weekStartMs, endMs: agg.weekEndMs };
}

/** Shift a week window by N weeks (positive = forward). */
export function shiftWeek(window: WeeklyWindow, weeks: number): WeeklyWindow {
  const ref = new Date(window.startMs);
  ref.setDate(ref.getDate() + weeks * 7);
  return computeWeekWindow(ref);
}

/** Format the week window header. e.g. "May 13 – 19, 2026". */
export function formatWeekLabel(window: WeeklyWindow): string {
  const start = new Date(window.startMs);
  const end = new Date(window.endMs);
  const sameMonth = start.getMonth() === end.getMonth();
  const startStr = start.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
  });
  const endStr = sameMonth
    ? String(end.getDate())
    : end.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  return `${startStr} – ${endStr}, ${end.getFullYear()}`;
}

function emptyCounts(): Record<LifeCategory, number> {
  return { WORK: 0, PERSONAL: 0, SELF_CARE: 0, HEALTH: 0, UNCATEGORIZED: 0 };
}

/**
 * Pure aggregator. Walks the task list once per window we need (current
 * week, prior week, four weeks) — O(N · 6) which is fine at user scale.
 * Caller passes the resolved `BalanceConfig` so the threshold computation
 * matches what the Today bar uses.
 */
export function computeWeeklyBalanceReport(
  tasks: Task[],
  config: BalanceConfig,
  reference: Date | number = new Date(),
): WeeklyBalanceReport {
  const window = computeWeekWindow(reference);
  const prior = shiftWeek(window, -1);

  const current = countWindow(tasks, window);
  const previous = countWindow(tasks, prior);

  const currentRatios = toRatios(current.counts);
  const priorRatios = toRatios(previous.counts);

  const perDay = computePerDay(tasks, window, config);
  const overloadDays = perDay.reduce((acc, d) => acc + (d.isOverloaded ? 1 : 0), 0);

  const fourWeekTrend: Record<LifeCategory, number[]> = {
    WORK: [],
    PERSONAL: [],
    SELF_CARE: [],
    HEALTH: [],
    UNCATEGORIZED: [],
  };
  const fourWeekCounts: Record<LifeCategory, number[]> = {
    WORK: [],
    PERSONAL: [],
    SELF_CARE: [],
    HEALTH: [],
    UNCATEGORIZED: [],
  };
  for (let i = 3; i >= 0; i--) {
    const w = shiftWeek(window, -i);
    const c = countWindow(tasks, w);
    const r = toRatios(c.counts);
    for (const cat of TRACKED_CATEGORIES) {
      fourWeekTrend[cat].push(r[cat] ?? 0);
      fourWeekCounts[cat].push(c.counts[cat] ?? 0);
    }
  }

  return {
    window,
    totalTracked: current.total,
    currentRatios,
    currentCounts: current.counts,
    priorRatios,
    priorCounts: previous.counts,
    perDay,
    overloadDays,
    fourWeekTrend,
    fourWeekCounts,
    targetRatios: configToMap(config),
    overloadThreshold: config.overloadThreshold,
  };
}

function countWindow(
  tasks: Task[],
  window: WeeklyWindow,
): { counts: Record<LifeCategory, number>; total: number } {
  const counts = emptyCounts();
  let total = 0;
  for (const t of tasks) {
    if (!t.completed_at) continue;
    const ts = Date.parse(t.completed_at);
    if (Number.isNaN(ts) || ts < window.startMs || ts > window.endMs) continue;
    const cat = (t.life_category ?? 'UNCATEGORIZED') as LifeCategory;
    if (cat === 'UNCATEGORIZED') continue;
    counts[cat] = (counts[cat] ?? 0) + 1;
    total++;
  }
  return { counts, total };
}

function toRatios(counts: Record<LifeCategory, number>): Record<LifeCategory, number> {
  const total = TRACKED_CATEGORIES.reduce((acc, c) => acc + (counts[c] ?? 0), 0);
  if (total === 0) return emptyCounts();
  const out = emptyCounts();
  for (const cat of TRACKED_CATEGORIES) {
    out[cat] = (counts[cat] ?? 0) / total;
  }
  return out;
}

function computePerDay(
  tasks: Task[],
  window: WeeklyWindow,
  config: BalanceConfig,
): PerDayBreakdown[] {
  const days: PerDayBreakdown[] = [];
  const overloadCutoff = config.workTarget + config.overloadThreshold;
  for (let i = 0; i < 7; i++) {
    const dayStart = new Date(window.startMs);
    dayStart.setDate(dayStart.getDate() + i);
    dayStart.setHours(0, 0, 0, 0);
    const dayEnd = new Date(dayStart);
    dayEnd.setHours(23, 59, 59, 999);
    const counts = emptyCounts();
    let total = 0;
    for (const t of tasks) {
      if (!t.completed_at) continue;
      const ts = Date.parse(t.completed_at);
      if (Number.isNaN(ts) || ts < dayStart.getTime() || ts > dayEnd.getTime()) continue;
      const cat = (t.life_category ?? 'UNCATEGORIZED') as LifeCategory;
      if (cat === 'UNCATEGORIZED') continue;
      counts[cat]++;
      total++;
    }
    const workRatio = total > 0 ? (counts.WORK ?? 0) / total : 0;
    days.push({
      label: DAY_LABELS[dayStart.getDay()] ?? '',
      date: dayStart,
      counts,
      total,
      isOverloaded: total > 0 && workRatio > overloadCutoff,
    });
  }
  return days;
}

/**
 * Per-category delta (current − prior) for the comparison panel.
 * Returned as percentage points for ratios, and integer for counts.
 */
export function deltaPercentPoints(
  current: Record<LifeCategory, number>,
  prior: Record<LifeCategory, number>,
  category: LifeCategory,
): number {
  return Math.round(((current[category] ?? 0) - (prior[category] ?? 0)) * 100);
}
