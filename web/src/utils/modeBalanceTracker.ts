import type { Task, TaskMode } from '@/types/task';

/**
 * Pure-function Task-Mode balance computations. Mirrors Android's
 * `ModeBalanceTracker.kt` (parity audit Pillar 3 — DEFERRED items
 * reclassified PROCEED 2026-05-15).
 *
 * Mode answers *what does the user want this task to produce?*
 *  - WORK produces output
 *  - PLAY produces enjoyment
 *  - RELAX produces restored energy
 *
 * Mode is orthogonal to `life_category` (the existing balanceTracker).
 * See `docs/WORK_PLAY_RELAX.md` for the philosophy doc, including the
 * descriptive-not-prescriptive copy rules and the SoD-aware window
 * semantics this tracker honors.
 */

/** Modes that participate in balance ratio computation. */
export const TRACKED_MODES: Exclude<TaskMode, 'UNCATEGORIZED'>[] = [
  'WORK',
  'PLAY',
  'RELAX',
];

export interface ModeBalanceState {
  /** Last 7 days of mode-tagged tasks, normalized 0..1. */
  currentRatios: Record<TaskMode, number>;
  /** Rolling 28-day average. */
  rollingRatios: Record<TaskMode, number>;
  /**
   * Configured targets per mode. Defaults to an even 1/3 split. Mode is
   * descriptive-only — targets do NOT drive overload notifications; they
   * only colour optional reference lines if a future UI surfaces them.
   */
  targetRatios: Record<TaskMode, number>;
  /**
   * Mode with the highest current ratio. `UNCATEGORIZED` when no data
   * (sentinel — never a "real" dominant value).
   */
  dominantMode: TaskMode;
  /** Number of tracked tasks contributing to currentRatios. */
  totalTracked: number;
}

/**
 * Optional user-set target ratios for the mode dimension. Defaults to an
 * even split across the three tracked modes. Mode is descriptive-only,
 * so targets are not user-tunable from Settings today — kept as a struct
 * to mirror Android's shape and to leave room for a future opt-in.
 */
export interface ModeBalanceConfig {
  workTarget: number;
  playTarget: number;
  relaxTarget: number;
}

export const DEFAULT_MODE_BALANCE_CONFIG: ModeBalanceConfig = {
  workTarget: 1 / 3,
  playTarget: 1 / 3,
  relaxTarget: 1 / 3,
};

function zeroRatios(): Record<TaskMode, number> {
  return {
    WORK: 0,
    PLAY: 0,
    RELAX: 0,
    UNCATEGORIZED: 0,
  };
}

export function modeConfigToMap(config: ModeBalanceConfig): Record<TaskMode, number> {
  return {
    WORK: config.workTarget,
    PLAY: config.playTarget,
    RELAX: config.relaxTarget,
    UNCATEGORIZED: 0,
  };
}

export const EMPTY_MODE_BALANCE_STATE: ModeBalanceState = {
  currentRatios: zeroRatios(),
  rollingRatios: zeroRatios(),
  targetRatios: modeConfigToMap(DEFAULT_MODE_BALANCE_CONFIG),
  dominantMode: 'UNCATEGORIZED',
  totalTracked: 0,
};

/** Returns true when the three mode targets sum to ~1.0 (within 0.01). */
export function isValidModeBalanceConfig(config: ModeBalanceConfig): boolean {
  const sum = config.workTarget + config.playTarget + config.relaxTarget;
  return Math.abs(sum - 1) < 0.01;
}

/**
 * Compute a {@link ModeBalanceState} from a pool of tasks.
 *
 * Window cutoffs respect the user-configured Start-of-Day so the "this
 * week" window matches the Today filter, habit streaks, and the
 * LifeCategory balance bar. Uncategorized tasks are excluded — they
 * never inflate or deflate a ratio.
 */
export function computeModeBalanceState(
  allTasks: Task[],
  config: ModeBalanceConfig = DEFAULT_MODE_BALANCE_CONFIG,
  options: { nowMs?: number; dayStartHour?: number; dayStartMinute?: number } = {},
): ModeBalanceState {
  const { nowMs = Date.now(), dayStartHour = 0, dayStartMinute = 0 } = options;
  const weekCutoff = cutoffMs(nowMs, 7, dayStartHour, dayStartMinute);
  const monthCutoff = cutoffMs(nowMs, 28, dayStartHour, dayStartMinute);

  const current = computeRatios(allTasks, weekCutoff);
  const rolling = computeRatios(allTasks, monthCutoff);
  const total = countTracked(allTasks, weekCutoff);

  let dominant: TaskMode = 'UNCATEGORIZED';
  if (total > 0) {
    let best = -1;
    for (const mode of TRACKED_MODES) {
      const r = current[mode] ?? 0;
      if (r > best) {
        best = r;
        dominant = mode;
      }
    }
  }

  return {
    currentRatios: current,
    rollingRatios: rolling,
    targetRatios: modeConfigToMap(config),
    dominantMode: dominant,
    totalTracked: total,
  };
}

function computeRatios(tasks: Task[], cutoff: number): Record<TaskMode, number> {
  const counts = zeroRatios();
  let total = 0;
  for (const t of tasks) {
    const ts = timestampFor(t);
    if (ts < cutoff) continue;
    const raw = (t.task_mode ?? 'UNCATEGORIZED') as TaskMode;
    if (raw === 'UNCATEGORIZED') continue;
    counts[raw] = (counts[raw] ?? 0) + 1;
    total++;
  }
  if (total === 0) return zeroRatios();
  for (const mode of TRACKED_MODES) {
    counts[mode] = (counts[mode] ?? 0) / total;
  }
  return counts;
}

function countTracked(tasks: Task[], cutoff: number): number {
  let total = 0;
  for (const t of tasks) {
    const ts = timestampFor(t);
    if (ts < cutoff) continue;
    const raw = (t.task_mode ?? 'UNCATEGORIZED') as TaskMode;
    if (raw === 'UNCATEGORIZED') continue;
    total++;
  }
  return total;
}

/**
 * Choose the most relevant timestamp for a task:
 *  - Completed tasks use completedAt.
 *  - Otherwise dueDate (parsed from ISO) if set, else createdAt.
 *
 * Mirrors `balanceTracker.timestampFor` so the mode + life-category
 * windows align day-for-day.
 */
function timestampFor(task: Task): number {
  const completed = toMs(task.completed_at);
  if (completed != null) return completed;
  const due = toMs(task.due_date);
  if (due != null) return due;
  const created = toMs(task.created_at);
  return created ?? 0;
}

function toMs(v: string | number | null | undefined): number | null {
  if (v == null) return null;
  if (typeof v === 'number') return v;
  const parsed = Date.parse(v);
  return Number.isNaN(parsed) ? null : parsed;
}

/**
 * Lower bound of the balance window. Snaps `now` back to the most recent
 * day-start, then walks back `days - 1` days so the window covers `days`
 * logical days inclusive of today.
 */
function cutoffMs(
  now: number,
  days: number,
  dayStartHour: number,
  dayStartMinute: number,
): number {
  const d = new Date(now);
  const minutesNow = d.getHours() * 60 + d.getMinutes();
  const sodMinutes = dayStartHour * 60 + dayStartMinute;
  d.setHours(dayStartHour, dayStartMinute, 0, 0);
  if (minutesNow < sodMinutes) {
    d.setDate(d.getDate() - 1);
  }
  d.setDate(d.getDate() - (days - 1));
  return d.getTime();
}
