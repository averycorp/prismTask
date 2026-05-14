import type { LifeCategory } from '@/types/task';
import type { Task } from '@/types/task';

/**
 * Pure-function Work-Life Balance computations. Mirrors Android's
 * `BalanceTracker.kt` (parity audit item C.2b).
 *
 * Used by the Today balance bar (C.1a), the weekly balance report
 * (future C.2c), and the burnout/self-care nudge pipeline (C.1e).
 */

/** Categories we track (excludes UNCATEGORIZED). */
export const TRACKED_CATEGORIES: Exclude<LifeCategory, 'UNCATEGORIZED'>[] = [
  'WORK',
  'PERSONAL',
  'SELF_CARE',
  'HEALTH',
];

export interface BalanceState {
  /** Last 7 days of categorized tasks, normalized 0..1. */
  currentRatios: Record<LifeCategory, number>;
  /** Rolling 28-day average. */
  rollingRatios: Record<LifeCategory, number>;
  /** Configured targets for each tracked category. */
  targetRatios: Record<LifeCategory, number>;
  /** True when WORK ratio exceeds `workTarget + overloadThreshold`. */
  isOverloaded: boolean;
  /** Category with the highest current ratio (UNCATEGORIZED when no data). */
  dominantCategory: LifeCategory;
  /** Number of tracked tasks contributing to currentRatios. */
  totalTracked: number;
}

export interface BalanceConfig {
  workTarget: number;
  personalTarget: number;
  selfCareTarget: number;
  healthTarget: number;
  overloadThreshold: number;
}

export const DEFAULT_BALANCE_CONFIG: BalanceConfig = {
  workTarget: 0.4,
  personalTarget: 0.25,
  selfCareTarget: 0.2,
  healthTarget: 0.15,
  overloadThreshold: 0.1,
};

export const EMPTY_BALANCE_STATE: BalanceState = {
  currentRatios: zeroRatios(),
  rollingRatios: zeroRatios(),
  targetRatios: configToMap(DEFAULT_BALANCE_CONFIG),
  isOverloaded: false,
  dominantCategory: 'UNCATEGORIZED',
  totalTracked: 0,
};

function zeroRatios(): Record<LifeCategory, number> {
  return {
    WORK: 0,
    PERSONAL: 0,
    SELF_CARE: 0,
    HEALTH: 0,
    UNCATEGORIZED: 0,
  };
}

export function configToMap(config: BalanceConfig): Record<LifeCategory, number> {
  return {
    WORK: config.workTarget,
    PERSONAL: config.personalTarget,
    SELF_CARE: config.selfCareTarget,
    HEALTH: config.healthTarget,
    UNCATEGORIZED: 0,
  };
}

/** Returns true if the four target weights sum to ~1.0 (within 0.01 rounding). */
export function isValidBalanceConfig(config: BalanceConfig): boolean {
  const sum =
    config.workTarget +
    config.personalTarget +
    config.selfCareTarget +
    config.healthTarget;
  return Math.abs(sum - 1) < 0.01;
}

/**
 * Compute a BalanceState from a pool of tasks. Window cutoffs respect the
 * user-configured Start-of-Day so the bar's "this week" matches the Today
 * filter, habit streaks, etc.
 */
export function computeBalanceState(
  allTasks: Task[],
  config: BalanceConfig = DEFAULT_BALANCE_CONFIG,
  options: { nowMs?: number; dayStartHour?: number; dayStartMinute?: number } = {},
): BalanceState {
  const { nowMs = Date.now(), dayStartHour = 0, dayStartMinute = 0 } = options;
  const weekCutoff = cutoffMs(nowMs, 7, dayStartHour, dayStartMinute);
  const monthCutoff = cutoffMs(nowMs, 28, dayStartHour, dayStartMinute);

  const current = computeRatios(allTasks, weekCutoff);
  const rolling = computeRatios(allTasks, monthCutoff);
  const total = countTracked(allTasks, weekCutoff);

  const workRatio = current.WORK ?? 0;
  const overloaded =
    total > 0 && workRatio > config.workTarget + config.overloadThreshold;

  let dominant: LifeCategory = 'UNCATEGORIZED';
  if (total > 0) {
    let best = -1;
    for (const cat of TRACKED_CATEGORIES) {
      const r = current[cat] ?? 0;
      if (r > best) {
        best = r;
        dominant = cat;
      }
    }
  }

  return {
    currentRatios: current,
    rollingRatios: rolling,
    targetRatios: configToMap(config),
    isOverloaded: overloaded,
    dominantCategory: dominant,
    totalTracked: total,
  };
}

function computeRatios(tasks: Task[], cutoff: number): Record<LifeCategory, number> {
  const counts = zeroRatios();
  let total = 0;
  for (const t of tasks) {
    const ts = timestampFor(t);
    if (ts < cutoff) continue;
    const raw = (t.life_category ?? 'UNCATEGORIZED') as LifeCategory;
    if (raw === 'UNCATEGORIZED') continue;
    counts[raw] = (counts[raw] ?? 0) + 1;
    total++;
  }
  if (total === 0) return zeroRatios();
  for (const cat of TRACKED_CATEGORIES) {
    counts[cat] = (counts[cat] ?? 0) / total;
  }
  return counts;
}

function countTracked(tasks: Task[], cutoff: number): number {
  let total = 0;
  for (const t of tasks) {
    const ts = timestampFor(t);
    if (ts < cutoff) continue;
    const raw = (t.life_category ?? 'UNCATEGORIZED') as LifeCategory;
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
 * Returns 0 when no timestamp is available so the row falls outside any
 * reasonable window. Dates may arrive as ISO strings or epoch-ms numbers
 * depending on the call site (Firestore mappers emit ISO; in-memory stores
 * sometimes use ms).
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
