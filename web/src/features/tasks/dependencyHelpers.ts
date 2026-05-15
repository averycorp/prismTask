import type { Task } from '@/types/task';
import type { TaskDependency } from '@/types/taskDependency';

/**
 * Returns the list of blocker task ids whose status is not `done` /
 * `cancelled` for the given blocked task. Empty array means "not
 * blocked" — callers can decide whether to surface a chip / mute the
 * card. Shared by the Today surface + `TaskRow` + `DependencyEditor`
 * so the gating rule stays in one place.
 *
 * Fail-open semantics: if a blocker references a task that's no longer
 * in the local task list (e.g. just-deleted on another device), it is
 * treated as *met*. Otherwise stale edges would permanently mute a
 * card until the user manually removed the dependency.
 */
export function selectUnmetBlockerIds(
  dependencies: TaskDependency[],
  tasks: Task[],
  taskId: string,
): string[] {
  const taskById = new Map(tasks.map((t) => [t.id, t]));
  return dependencies
    .filter((d) => d.blocked_task_id === taskId)
    .map((d) => d.blocker_task_id)
    .filter((bid) => {
      const t = taskById.get(bid);
      if (!t) return false;
      return t.status !== 'done' && t.status !== 'cancelled';
    });
}

/**
 * One-pass build of `blocked_task_id → unmet-blocker count`. Use this
 * when computing counts for many tasks in the same render: it does a
 * single O(deps) sweep plus O(tasks) to build the task map, vs.
 * `selectUnmetBlockerIds` called per row which is O(tasks * rows).
 */
export function buildUnmetBlockerCountMap(
  dependencies: TaskDependency[],
  tasks: Task[],
): Map<string, number> {
  const taskById = new Map(tasks.map((t) => [t.id, t]));
  const counts = new Map<string, number>();
  for (const d of dependencies) {
    const blocker = taskById.get(d.blocker_task_id);
    // Fail-open: unknown blocker (deleted task) counts as met.
    if (!blocker) continue;
    if (blocker.status === 'done' || blocker.status === 'cancelled') continue;
    counts.set(d.blocked_task_id, (counts.get(d.blocked_task_id) ?? 0) + 1);
  }
  return counts;
}
