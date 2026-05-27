import type { Task } from '@/types/task';

const MS_PER_DAY = 24 * 60 * 60 * 1000;

/** A dormant task surfaced in the web "Ready to Resume" section. */
export interface DormantTask {
  task: Task;
  daysDormant: number;
}

/** Effective dormancy threshold: per-task override wins over the global default. */
export function effectiveThresholdDays(
  override: number | null | undefined,
  global: number,
): number {
  return override ?? global;
}

/** Whole days elapsed since an epoch-millis instant, floored, never negative. */
export function daysSince(lastEngagementAt: number, now: number): number {
  return Math.floor(Math.max(0, now - lastEngagementAt) / MS_PER_DAY);
}

/**
 * Whether a task is dormant. Mirrors Android `DormancyCalculator.isDormant`:
 * a task with no `last_engagement_at` has never been engaged and is NOT dormant
 * (never-engaged ≠ dormant).
 */
export function isDormant(task: Task, globalThresholdDays: number, now: number): boolean {
  const last = task.last_engagement_at;
  if (last == null) return false;
  return daysSince(last, now) > effectiveThresholdDays(task.dormancy_threshold_days_override, globalThresholdDays);
}

/**
 * Ready-to-Resume ranking, mirroring Android `ReadyToResumeProvider`: dormant,
 * not-done, recurring tasks, longest-dormant first, capped at 5. Read-only on
 * web — no session execution (the sync layer is thin by design).
 */
export const MAX_VISIBLE = 5;

export function readyToResume(
  tasks: Task[],
  globalThresholdDays: number,
  now: number = Date.now(),
): DormantTask[] {
  return tasks
    .filter((t) => !!t.recurrence_json)
    .filter((t) => t.status !== 'done' && t.status !== 'cancelled')
    .filter((t) => isDormant(t, globalThresholdDays, now))
    .map((t) => ({ task: t, daysDormant: daysSince(t.last_engagement_at as number, now) }))
    .sort((a, b) => b.daysDormant - a.daysDormant)
    .slice(0, MAX_VISIBLE);
}
