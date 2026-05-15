import type { Task, TaskMode } from '@/types/task';
import { TRACKED_MODES } from '@/utils/modeBalanceTracker';
import {
  computeWeekWindow,
  shiftWeek,
  type WeeklyWindow,
  DAY_LABELS,
} from './weeklyBalanceReport';

/**
 * Pure aggregator for the mode dimension of the Weekly Balance Report.
 *
 * Mirrors the relevant pieces of Android's mode-balance surfaces and the
 * existing LifeCategory aggregator. Mode is descriptive-only — there are
 * no user-set targets and no overload-day count for the mode dimension
 * (see `docs/WORK_PLAY_RELAX.md` § *Descriptive, not prescriptive*).
 *
 * Kept as pure functions so the screen stays thin and the math is easy
 * to test in isolation.
 */

export interface WeeklyModeReport {
  /** Selected week. */
  window: WeeklyWindow;
  /**
   * Tasks counted (completed, with a tracked task_mode, inside the
   * window). Excludes UNCATEGORIZED.
   */
  totalTracked: number;
  /** Ratio per mode for the selected week. */
  currentRatios: Record<TaskMode, number>;
  /** Absolute counts per mode for the selected week. */
  currentCounts: Record<TaskMode, number>;
  /** Ratio per mode for the prior week (for the delta panel). */
  priorRatios: Record<TaskMode, number>;
  /** Absolute counts per mode for the prior week. */
  priorCounts: Record<TaskMode, number>;
  /** Per-day breakdown for the selected week. */
  perDay: PerDayModeBreakdown[];
  /** 4-week ratio trend per mode, oldest → newest (length 4). */
  fourWeekTrend: Record<TaskMode, number[]>;
  /** 4-week absolute count trend per mode, oldest → newest (length 4). */
  fourWeekCounts: Record<TaskMode, number[]>;
  /** Mode with the highest current ratio. `UNCATEGORIZED` when no data. */
  dominantMode: TaskMode;
}

export interface PerDayModeBreakdown {
  /** Day label, Sun..Sat per Date.getDay(). */
  label: string;
  /** Date of this day at local midnight. */
  date: Date;
  /** Per-mode count for this day. */
  counts: Record<TaskMode, number>;
  /** Total tracked completions this day. */
  total: number;
}

function emptyModeCounts(): Record<TaskMode, number> {
  return { WORK: 0, PLAY: 0, RELAX: 0, UNCATEGORIZED: 0 };
}

/**
 * Pure aggregator. Walks the task list once per window we need (current
 * week, prior week, four weeks). Caller passes the same reference
 * timestamp as the LifeCategory report so both sections describe the
 * same week.
 */
export function computeWeeklyModeReport(
  tasks: Task[],
  reference: Date | number = new Date(),
): WeeklyModeReport {
  const window = computeWeekWindow(reference);
  const prior = shiftWeek(window, -1);

  const current = countWindow(tasks, window);
  const previous = countWindow(tasks, prior);

  const currentRatios = toRatios(current.counts);
  const priorRatios = toRatios(previous.counts);

  const perDay = computePerDay(tasks, window);

  const fourWeekTrend: Record<TaskMode, number[]> = {
    WORK: [],
    PLAY: [],
    RELAX: [],
    UNCATEGORIZED: [],
  };
  const fourWeekCounts: Record<TaskMode, number[]> = {
    WORK: [],
    PLAY: [],
    RELAX: [],
    UNCATEGORIZED: [],
  };
  for (let i = 3; i >= 0; i--) {
    const w = shiftWeek(window, -i);
    const c = countWindow(tasks, w);
    const r = toRatios(c.counts);
    for (const mode of TRACKED_MODES) {
      fourWeekTrend[mode].push(r[mode] ?? 0);
      fourWeekCounts[mode].push(c.counts[mode] ?? 0);
    }
  }

  let dominant: TaskMode = 'UNCATEGORIZED';
  if (current.total > 0) {
    let best = -1;
    for (const mode of TRACKED_MODES) {
      const r = currentRatios[mode] ?? 0;
      if (r > best) {
        best = r;
        dominant = mode;
      }
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
    fourWeekTrend,
    fourWeekCounts,
    dominantMode: dominant,
  };
}

function countWindow(
  tasks: Task[],
  window: WeeklyWindow,
): { counts: Record<TaskMode, number>; total: number } {
  const counts = emptyModeCounts();
  let total = 0;
  for (const t of tasks) {
    if (!t.completed_at) continue;
    const ts = Date.parse(t.completed_at);
    if (Number.isNaN(ts) || ts < window.startMs || ts > window.endMs) continue;
    const mode = (t.task_mode ?? 'UNCATEGORIZED') as TaskMode;
    if (mode === 'UNCATEGORIZED') continue;
    counts[mode] = (counts[mode] ?? 0) + 1;
    total++;
  }
  return { counts, total };
}

function toRatios(counts: Record<TaskMode, number>): Record<TaskMode, number> {
  const total = TRACKED_MODES.reduce((acc, m) => acc + (counts[m] ?? 0), 0);
  if (total === 0) return emptyModeCounts();
  const out = emptyModeCounts();
  for (const mode of TRACKED_MODES) {
    out[mode] = (counts[mode] ?? 0) / total;
  }
  return out;
}

function computePerDay(tasks: Task[], window: WeeklyWindow): PerDayModeBreakdown[] {
  const days: PerDayModeBreakdown[] = [];
  for (let i = 0; i < 7; i++) {
    const dayStart = new Date(window.startMs);
    dayStart.setDate(dayStart.getDate() + i);
    dayStart.setHours(0, 0, 0, 0);
    const dayEnd = new Date(dayStart);
    dayEnd.setHours(23, 59, 59, 999);
    const counts = emptyModeCounts();
    let total = 0;
    for (const t of tasks) {
      if (!t.completed_at) continue;
      const ts = Date.parse(t.completed_at);
      if (Number.isNaN(ts) || ts < dayStart.getTime() || ts > dayEnd.getTime()) continue;
      const mode = (t.task_mode ?? 'UNCATEGORIZED') as TaskMode;
      if (mode === 'UNCATEGORIZED') continue;
      counts[mode]++;
      total++;
    }
    days.push({
      label: DAY_LABELS[dayStart.getDay()] ?? '',
      date: dayStart,
      counts,
      total,
    });
  }
  return days;
}

/**
 * Per-mode delta (current − prior) for the comparison panel.
 * Returned as percentage points for ratios, and integer for counts.
 */
export function modeDeltaPercentPoints(
  current: Record<TaskMode, number>,
  prior: Record<TaskMode, number>,
  mode: TaskMode,
): number {
  return Math.round(((current[mode] ?? 0) - (prior[mode] ?? 0)) * 100);
}
