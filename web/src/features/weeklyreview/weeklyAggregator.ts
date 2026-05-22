import type { Task } from '@/types/task';
import type { WeeklyTaskSummary } from '@/api/ai';

/**
 * Local-first weekly review aggregator. Mirrors the Android
 * WeeklyReviewAggregator: given a list of tasks and a week window, compute
 * completed / slipped lists plus counts and a simple rule-based narrative.
 *
 * This is the Free-tier experience AND the fallback when the backend call
 * fails, so it has to stand on its own without any AI assistance.
 */

export interface WeeklyWindow {
  /** Epoch ms at Monday 00:00 local time. */
  weekStartMs: number;
  /** Epoch ms at end-of-Sunday 23:59:59.999 local time. Inclusive. */
  weekEndMs: number;
  /** ISO date (YYYY-MM-DD) for Monday. */
  weekStartIso: string;
  /** ISO date for Sunday. */
  weekEndIso: string;
}

export interface WeeklyReviewLocal {
  window: WeeklyWindow;
  completedTasks: Task[];
  slippedTasks: Task[];
  completedCount: number;
  slippedCount: number;
  narrative: {
    wins: string[];
    slips: string[];
    suggestions: string[];
  };
}

/**
 * Compute the Monday 00:00 / Sunday 23:59:59.999 epoch-ms bounds for the
 * week containing the given reference date, in the **user's local
 * timezone**. Firestore stores timestamps as UTC epoch ms, so comparisons
 * against user-perceived days need to be done against local-time bounds
 * converted to ms (which is what `Date` gives us — its getTime() is UTC
 * ms but the year/month/day setters operate on local-time components).
 */
export function computeWeekWindow(reference: Date = new Date()): WeeklyWindow {
  const ref = new Date(reference);
  const dayOfWeek = ref.getDay(); // Sun=0, Mon=1, ... Sat=6
  // Distance back to Monday; if today is Sunday, that's 6 days back.
  const daysBackToMonday = dayOfWeek === 0 ? 6 : dayOfWeek - 1;

  const monday = new Date(ref);
  monday.setDate(ref.getDate() - daysBackToMonday);
  monday.setHours(0, 0, 0, 0);

  const sunday = new Date(monday);
  sunday.setDate(monday.getDate() + 6);
  sunday.setHours(23, 59, 59, 999);

  return {
    weekStartMs: monday.getTime(),
    weekEndMs: sunday.getTime(),
    weekStartIso: toIsoDate(monday),
    weekEndIso: toIsoDate(sunday),
  };
}

/** Shift the reference week by the given number of weeks (positive = forward). */
export function shiftWeekWindow(window: WeeklyWindow, weeks: number): WeeklyWindow {
  const shifted = new Date(window.weekStartMs);
  shifted.setDate(shifted.getDate() + weeks * 7);
  return computeWeekWindow(shifted);
}

function toIsoDate(d: Date): string {
  // Use local-time components so Monday stays Monday in the user's zone.
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/**
 * Aggregate tasks into completed / slipped lists for the given week
 * window. Mirrors WeeklyReviewAggregator.aggregate() on Android.
 *
 * Slipped logic — read carefully; this is the kind of rule that gets
 * silently wrong in refactors:
 *   A task is "slipped" iff
 *     (dueDate OR plannedDate falls within the week window) AND
 *     isCompleted === false AND
 *     (no completedAt OR completedAt > weekEnd).
 *
 * The last clause matters: a task completed AFTER the week ended is
 * "late but done" for the retrospective view of that week, not a slip.
 * When viewing the current ongoing week, it counts as completed (its
 * completedAt falls inside the window). When viewing a past week, the
 * current-week completion excludes it from slipped because the
 * completedAt > weekEnd check kicks in.
 */
export function aggregateWeek(
  tasks: Task[],
  window: WeeklyWindow = computeWeekWindow(),
): WeeklyReviewLocal {
  const { weekStartMs, weekEndMs } = window;

  const completedTasks: Task[] = [];
  const slippedTasks: Task[] = [];

  for (const task of tasks) {
    const completedAtMs = task.completed_at
      ? new Date(task.completed_at).getTime()
      : null;
    const dueDateMs = task.due_date
      ? isoDateMidnightMs(task.due_date)
      : null;
    const plannedDateMs = task.planned_date
      ? isoDateMidnightMs(task.planned_date)
      : null;

    // Completed within the window.
    if (
      completedAtMs !== null &&
      completedAtMs >= weekStartMs &&
      completedAtMs <= weekEndMs
    ) {
      completedTasks.push(task);
      continue;
    }

    // Slipped: due/planned in window, still open (or completed after the
    // window ended).
    const dueInWindow =
      dueDateMs !== null && dueDateMs >= weekStartMs && dueDateMs <= weekEndMs;
    const plannedInWindow =
      plannedDateMs !== null &&
      plannedDateMs >= weekStartMs &&
      plannedDateMs <= weekEndMs;
    if (dueInWindow || plannedInWindow) {
      const isOpen = task.status !== 'done' && task.status !== 'cancelled';
      const completedAfterWindow =
        completedAtMs !== null && completedAtMs > weekEndMs;
      if (isOpen || completedAfterWindow) {
        slippedTasks.push(task);
      }
    }
  }

  return {
    window,
    completedTasks,
    slippedTasks,
    completedCount: completedTasks.length,
    slippedCount: slippedTasks.length,
    narrative: buildLocalNarrative(completedTasks.length, slippedTasks.length),
  };
}

function isoDateMidnightMs(iso: string): number {
  // ISO date YYYY-MM-DD -> local midnight ms
  const [y, m, d] = iso.split('-').map((s) => parseInt(s, 10));
  const dt = new Date(y, m - 1, d, 0, 0, 0, 0);
  return dt.getTime();
}

/**
 * Simple rule-based narrative. Deliberately shallow — this is the
 * fallback, not the main event. Users get the richer Sonnet-backed
 * narrative when they're Pro and the backend is reachable.
 */
function buildLocalNarrative(
  completed: number,
  slipped: number,
): { wins: string[]; slips: string[]; suggestions: string[] } {
  const wins: string[] = [];
  const slips: string[] = [];
  const suggestions: string[] = [];

  if (completed > 0) {
    wins.push(
      `Completed ${completed} task${completed === 1 ? '' : 's'} this week.`,
    );
  } else {
    wins.push('You showed up this week — that counts.');
  }

  if (slipped > 0) {
    slips.push(
      `${slipped} task${slipped === 1 ? '' : 's'} slipped — they're ready to carry forward.`,
    );
  }

  if (slipped > completed && completed + slipped > 0) {
    suggestions.push(
      'Consider a lighter plan next week — more tasks slipped than landed.',
    );
  } else if (completed > 0) {
    suggestions.push('Keep the rhythm going. Pick one win to repeat next week.');
  }

  return { wins, slips, suggestions };
}

/** Map a task to the WeeklyTaskSummary shape the backend expects. */
export function taskToSummary(
  task: Task,
  options: { completed: boolean },
): WeeklyTaskSummary {
  return {
    task_id: task.id,
    title: task.title,
    completed_at: options.completed ? task.completed_at ?? null : null,
    priority: task.priority,
    eisenhower_quadrant: task.eisenhower_quadrant,
    life_category: null, // Web Task type doesn't carry life_category yet.
    project_id: task.project_id || null,
  };
}
