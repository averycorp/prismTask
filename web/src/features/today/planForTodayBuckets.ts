import { format, parseISO, addDays } from 'date-fns';
import type { Task } from '@/types/task';

/**
 * Pure-function helpers backing `PlanForTodaySheet`. Lives in a sibling
 * module instead of inside the component file so:
 *  - Vite's react-refresh stays happy (component files must only export
 *    components for fast-refresh to work).
 *  - Unit tests can import the math without dragging Modal / Firestore /
 *    Zustand into the test environment.
 *
 * Mirrors the bucketing window math in
 * `app/src/main/java/com/averycorp/prismtask/ui/screens/today/components/PlanForTodaySheet.kt`
 * lines 134-148. Closes Parity Batch 2 § C.1b.
 */

/**
 * Web priority is `1=urgent, 2=high, 3=medium, 4=low` (see
 * `TaskPriority` in `web/src/types/task.ts`). Higher *rank* = more
 * urgent for sort comparators in the sheet.
 */
export function priorityRank(p: number): number {
  if (p === 1) return 4;
  if (p === 2) return 3;
  if (p === 3) return 2;
  if (p === 4) return 1;
  return 0;
}

/**
 * Numeric sortable key for a task's due date. Tasks with no due_date
 * sort last (max-safe-int) so they fall to the bottom of date-sorted
 * lists.
 */
export function dueRank(t: Task): number {
  if (!t.due_date) return Number.MAX_SAFE_INTEGER;
  return parseISO(t.due_date).getTime();
}

/**
 * Split a pre-filtered upcoming pool into named buckets relative to a
 * reference "today" ISO date.
 *
 *  - tomorrow         = [T+1, T+2)
 *  - this week        = [T+2, T+7)
 *  - next week        = [T+7, T+14)
 *  - later            = [T+14, ∞)
 *  - no date          = due_date == null
 *
 * Comparison is on ISO `YYYY-MM-DD` strings (lexically sortable for
 * dates in the same calendar system, which all `due_date` fields are).
 */
export function bucketUpcoming(
  tasks: Task[],
  todayIso: string,
): {
  tomorrow: Task[];
  thisWeek: Task[];
  nextWeek: Task[];
  later: Task[];
  noDate: Task[];
} {
  const todayDate = parseISO(todayIso);
  const tomorrowIso = format(addDays(todayDate, 1), 'yyyy-MM-dd');
  const dayAfterIso = format(addDays(todayDate, 2), 'yyyy-MM-dd');
  const sevenIso = format(addDays(todayDate, 7), 'yyyy-MM-dd');
  const fourteenIso = format(addDays(todayDate, 14), 'yyyy-MM-dd');

  const tomorrow: Task[] = [];
  const thisWeek: Task[] = [];
  const nextWeek: Task[] = [];
  const later: Task[] = [];
  const noDate: Task[] = [];
  for (const t of tasks) {
    if (!t.due_date) {
      noDate.push(t);
      continue;
    }
    if (t.due_date >= tomorrowIso && t.due_date < dayAfterIso) tomorrow.push(t);
    else if (t.due_date >= dayAfterIso && t.due_date < sevenIso) thisWeek.push(t);
    else if (t.due_date >= sevenIso && t.due_date < fourteenIso) nextWeek.push(t);
    else if (t.due_date >= fourteenIso) later.push(t);
    else noDate.push(t); // pre-tomorrow but not overdue (rare; safety net)
  }
  return { tomorrow, thisWeek, nextWeek, later, noDate };
}
