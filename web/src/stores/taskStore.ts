import { create } from 'zustand';
import type { Task, TaskCreate, TaskUpdate, SubtaskCreate } from '@/types/task';
import * as firestoreTasks from '@/api/firestore/tasks';
import {
  recordTaskCompletion,
  removeTaskCompletion,
  subscribeToTaskCompletions,
  type TaskCompletion,
} from '@/api/firestore/taskCompletions';
import { logicalToday } from '@/utils/dayBoundary';
import { useSettingsStore } from '@/stores/settingsStore';
import {
  calculateNextOccurrence,
  parseRecurrenceRule,
} from '@/utils/recurrence';
import type { Unsubscribe } from 'firebase/firestore';

interface TaskState {
  tasks: Task[];
  todayTasks: Task[];
  overdueTasks: Task[];
  upcomingTasks: Task[];
  selectedTask: Task | null;
  isLoading: boolean;
  error: string | null;

  // Selection
  selectedTaskIds: Set<string>;
  toggleTaskSelection: (id: string) => void;
  clearSelection: () => void;
  selectAll: (ids: string[]) => void;

  // Fetch
  fetchByProject: (projectId: string) => Promise<void>;
  fetchToday: () => Promise<void>;
  fetchOverdue: () => Promise<void>;
  fetchUpcoming: (days?: number) => Promise<void>;
  fetchTask: (id: string) => Promise<Task>;

  // CRUD
  createTask: (projectId: string, data: TaskCreate) => Promise<Task>;
  updateTask: (taskId: string, data: TaskUpdate) => Promise<Task>;
  deleteTask: (taskId: string) => Promise<void>;
  completeTask: (taskId: string) => Promise<Task>;
  uncompleteTask: (taskId: string) => Promise<Task>;
  /**
   * Mirrors Android `TaskRepository.logAdditionalCompletion`. Records
   * another completion entry for a recurring task that is already done
   * for the current cycle, without flipping the row back to incomplete
   * or spawning another next-occurrence (the first `completeTask` call
   * already did the spawn). No-op for non-recurring tasks or tasks not
   * currently in `done` state.
   */
  logAdditionalCompletion: (taskId: string) => Promise<void>;

  // Subtasks
  createSubtask: (parentId: string, data: SubtaskCreate) => Promise<Task>;

  // Bulk
  bulkComplete: (ids: string[]) => Promise<void>;
  bulkDelete: (ids: string[]) => Promise<void>;
  bulkMove: (ids: string[], projectId: string) => Promise<void>;
  bulkUpdatePriority: (ids: string[], priority: number) => Promise<void>;
  bulkUpdateDueDate: (ids: string[], dueDate: string) => Promise<void>;

  // Real-time
  subscribeToTasks: (uid: string) => Unsubscribe;
  subscribeToTaskCompletions: (uid: string) => Unsubscribe;

  // Analytics history mirror (`task_completions`, parity audit B.6)
  taskCompletions: TaskCompletion[];

  // Local
  setSelectedTask: (task: Task | null) => void;
  clearError: () => void;
  removeTaskFromLists: (taskId: string) => void;
  updateTaskInLists: (task: Task) => void;
}

import { getFirebaseUid } from '@/stores/firebaseUid';

function getUid(): string {
  return getFirebaseUid();
}

/**
 * Read the user's Start-of-Day hour from the settings store at call
 * time so the today/overdue/upcoming windows refresh when the user
 * changes the pref or the value loads from Firestore after sign-in.
 */
function getStartOfDayHour(): number {
  return useSettingsStore.getState().startOfDayHour;
}

function updateInArray(arr: Task[], id: string, updated: Task): Task[] {
  return arr.map((t) => (t.id === id ? updated : t));
}

function removeFromArray(arr: Task[], id: string): Task[] {
  return arr.filter((t) => t.id !== id);
}

/**
 * Push a `task_completions` history row to Firestore so analytics
 * (completion grid, on-time rate, day-of-week distribution, etc.)
 * picks up the web toggle the same way it picks up Android toggles.
 *
 * Failures are swallowed because the user-visible `tasks.status`
 * update already succeeded; an analytics-history miss must not flip
 * the toggle back. Parity audit B.6.
 */
async function writeTaskCompletionRow(uid: string, task: Task): Promise<void> {
  try {
    const completedAt = Date.now();
    const completedDateLocal = logicalToday(completedAt, 0);
    const wasOverdue = computeWasOverdue(task, completedDateLocal);
    const daysToComplete = computeDaysToComplete(task.created_at, completedAt);
    const tagNames = (task.tags ?? [])
      .map((t) => t.name)
      .filter((n): n is string => typeof n === 'string' && n.length > 0);
    await recordTaskCompletion(uid, {
      taskCloudId: task.id,
      completedDateLocal,
      completedAt,
      priority: task.priority,
      wasOverdue,
      daysToComplete: daysToComplete ?? undefined,
      tags: tagNames.length > 0 ? tagNames.join(',') : undefined,
    });
  } catch {
    // Silently fail — analytics-history row is best-effort.
  }
}

/** Mirror of Android's `TaskCompletionRepository.wasOverdue` check. */
function computeWasOverdue(task: Task, completedDateLocal: string): boolean {
  if (!task.due_date) return false;
  return task.due_date < completedDateLocal;
}

/** Mirror of Android's `TaskCompletionRepository.computeDaysToComplete`. */
function computeDaysToComplete(
  createdAtIso: string,
  completedAtMs: number,
): number | null {
  const createdMs = Date.parse(createdAtIso);
  if (!Number.isFinite(createdMs)) return null;
  const dayMs = 24 * 60 * 60 * 1000;
  return Math.max(0, Math.floor((completedAtMs - createdMs) / dayMs));
}

async function deleteTaskCompletionRow(
  uid: string,
  taskId: string,
): Promise<void> {
  try {
    // We only know the task id, not which logical day the prior
    // completion was filed under. Sweep today + the previous 2 days
    // since the toggle-off UX is "I just toggled it off, undo me" —
    // a completion filed yesterday or earlier by the same user is
    // either the row we want to remove (same task) or stale enough
    // that retaining it is the wrong answer anyway. Bound the sweep
    // so a single uncomplete doesn't paginate the whole collection.
    const now = Date.now();
    const dayMs = 24 * 60 * 60 * 1000;
    const dates = [
      logicalToday(now, 0),
      logicalToday(now - dayMs, 0),
      logicalToday(now - 2 * dayMs, 0),
    ];
    await Promise.all(
      dates.map((d) => removeTaskCompletion(uid, taskId, d)),
    );
  } catch {
    // Silently fail — uncomplete UX already succeeded on the task row.
  }
}

export const useTaskStore = create<TaskState>((set, get) => ({
  tasks: [],
  todayTasks: [],
  overdueTasks: [],
  upcomingTasks: [],
  selectedTask: null,
  isLoading: false,
  error: null,
  selectedTaskIds: new Set(),
  taskCompletions: [],

  toggleTaskSelection: (id) =>
    set((state) => {
      const next = new Set(state.selectedTaskIds);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return { selectedTaskIds: next };
    }),

  clearSelection: () => set({ selectedTaskIds: new Set() }),

  selectAll: (ids) => set({ selectedTaskIds: new Set(ids) }),

  fetchByProject: async (projectId) => {
    set({ isLoading: true, error: null });
    try {
      const uid = getUid();
      const tasks = await firestoreTasks.getTasksByProject(uid, projectId);
      set({ tasks, isLoading: false });
    } catch (e) {
      set({ error: (e as Error).message, isLoading: false });
    }
  },

  fetchToday: async () => {
    try {
      const uid = getUid();
      const todayTasks = await firestoreTasks.getTodayTasks(
        uid,
        getStartOfDayHour(),
      );
      set({ todayTasks });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },

  fetchOverdue: async () => {
    try {
      const uid = getUid();
      const overdueTasks = await firestoreTasks.getOverdueTasks(
        uid,
        getStartOfDayHour(),
      );
      set({ overdueTasks });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },

  fetchUpcoming: async (days = 7) => {
    try {
      const uid = getUid();
      const upcomingTasks = await firestoreTasks.getUpcomingTasks(
        uid,
        getStartOfDayHour(),
        days,
      );
      set({ upcomingTasks });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },

  fetchTask: async (id) => {
    const uid = getUid();
    const task = await firestoreTasks.getTask(uid, id);
    if (!task) throw new Error('Task not found');
    // Fetch subtasks
    const subtasks = await firestoreTasks.getSubtasks(uid, id);
    const taskWithSubs = { ...task, subtasks };
    set({ selectedTask: taskWithSubs });
    return taskWithSubs;
  },

  createTask: async (projectId, data) => {
    const uid = getUid();
    const task = await firestoreTasks.createTask(uid, {
      ...data,
      project_id: projectId,
    } as Partial<Task> & { title: string });
    set((state) => ({ tasks: [...state.tasks, task] }));
    return task;
  },

  updateTask: async (taskId, data) => {
    const uid = getUid();
    const updated = await firestoreTasks.updateTask(uid, taskId, data as Record<string, unknown>);
    get().updateTaskInLists(updated);
    return updated;
  },

  deleteTask: async (taskId) => {
    const uid = getUid();
    await firestoreTasks.deleteTask(uid, taskId);
    get().removeTaskFromLists(taskId);
  },

  completeTask: async (taskId) => {
    const uid = getUid();

    // Find the task before completing to check recurrence
    const existingTask =
      get().tasks.find((t) => t.id === taskId) ??
      get().todayTasks.find((t) => t.id === taskId) ??
      get().overdueTasks.find((t) => t.id === taskId) ??
      get().upcomingTasks.find((t) => t.id === taskId);

    const updated = await firestoreTasks.updateTask(uid, taskId, { status: 'done' });
    get().updateTaskInLists(updated);

    // Parity audit B.6: write a `task_completions` history row so the
    // analytics chart (completion grid, day-of-week distribution,
    // on-time rate) picks up web-completed tasks. Android writes this
    // row in `TaskCompletionRepository.recordCompletion`; web was
    // skipping it, leaving the analytics chart blank for web toggles.
    void writeTaskCompletionRow(uid, updated);

    // Handle recurrence: create next occurrence if applicable
    if (existingTask?.recurrence_json && existingTask.due_date) {
      const rule = parseRecurrenceRule(existingTask.recurrence_json);
      if (rule) {
        const nextDate = calculateNextOccurrence(existingTask.due_date, rule);
        if (nextDate) {
          try {
            const nextTask = await firestoreTasks.createTask(uid, {
              title: existingTask.title,
              description: existingTask.description ?? undefined,
              priority: existingTask.priority,
              due_date: nextDate,
              sort_order: existingTask.sort_order,
              recurrence_json: existingTask.recurrence_json ?? undefined,
              project_id: existingTask.project_id,
            } as Partial<Task> & { title: string });
            set((state) => ({
              tasks: [...state.tasks, nextTask],
            }));
            (updated as Task & { _nextDate?: string })._nextDate = nextDate;
          } catch {
            // Silently fail — the task is still completed
          }
        }
      }
    }

    return updated;
  },

  uncompleteTask: async (taskId) => {
    const uid = getUid();
    const updated = await firestoreTasks.updateTask(uid, taskId, { status: 'todo' });
    get().updateTaskInLists(updated);
    // Parity audit B.6: roll back the `task_completions` history row
    // so the analytics surface stays in sync with the toggle.
    void deleteTaskCompletionRow(uid, taskId);
    return updated;
  },

  /**
   * "Log Again" path for recurring tasks already completed today.
   * Mirrors Android `TaskRepository.logAdditionalCompletion` — refreshes
   * the `task_completions` history row's `completedAtTime` without
   * touching `tasks.status` (still `done`) and without spawning another
   * next-occurrence (that happened on the first `completeTask`).
   *
   * No-op when the task is missing, not recurring, or not currently
   * marked done. Failures are swallowed for the same reason
   * `writeTaskCompletionRow` swallows them: the user toggled an already-
   * complete row, the row is still done after this call, and a missed
   * analytics-history refresh isn't worth surfacing an error toast for.
   */
  logAdditionalCompletion: async (taskId) => {
    const uid = getUid();
    const existing =
      get().tasks.find((t) => t.id === taskId) ??
      get().todayTasks.find((t) => t.id === taskId) ??
      get().overdueTasks.find((t) => t.id === taskId) ??
      get().upcomingTasks.find((t) => t.id === taskId);
    if (!existing) return;
    if (!existing.recurrence_json) return;
    if (existing.status !== 'done') return;
    // `recordTaskCompletion` is merge-semantics on the deterministic
    // `(taskId, completedDateLocal)` doc id, so calling it a second
    // time on the same logical day refreshes `completedAtTime` on the
    // existing row rather than creating a duplicate. That matches the
    // user-visible Android behavior ("the log reflects the more recent
    // time") even though the on-disk shape diverges — Android inserts
    // a separate Room row, web mutates the canonical Firestore doc.
    void writeTaskCompletionRow(uid, existing);
  },

  createSubtask: async (parentId, data) => {
    const uid = getUid();
    const subtask = await firestoreTasks.createTask(uid, {
      ...data,
      parent_id: parentId,
    } as Partial<Task> & { title: string });
    // Re-fetch the parent to get updated subtasks
    const parent = await get().fetchTask(parentId);
    get().updateTaskInLists(parent);
    return subtask;
  },

  bulkComplete: async (ids) => {
    const uid = getUid();
    const completedTasks = await Promise.all(
      ids.map((id) => firestoreTasks.updateTask(uid, id, { status: 'done' })),
    );
    // Parity audit B.6: write history rows for each completed task.
    for (const t of completedTasks) {
      void writeTaskCompletionRow(uid, t);
    }
    set((state) => ({
      tasks: state.tasks.map((t) =>
        ids.includes(t.id) ? { ...t, status: 'done' as const } : t,
      ),
      todayTasks: state.todayTasks.map((t) =>
        ids.includes(t.id) ? { ...t, status: 'done' as const } : t,
      ),
      overdueTasks: state.overdueTasks.map((t) =>
        ids.includes(t.id) ? { ...t, status: 'done' as const } : t,
      ),
      upcomingTasks: state.upcomingTasks.map((t) =>
        ids.includes(t.id) ? { ...t, status: 'done' as const } : t,
      ),
      selectedTaskIds: new Set(),
    }));
  },

  bulkDelete: async (ids) => {
    const uid = getUid();
    await Promise.all(ids.map((id) => firestoreTasks.deleteTask(uid, id)));
    const filterOut = (arr: Task[]) => arr.filter((t) => !ids.includes(t.id));
    set((state) => ({
      tasks: filterOut(state.tasks),
      todayTasks: filterOut(state.todayTasks),
      overdueTasks: filterOut(state.overdueTasks),
      upcomingTasks: filterOut(state.upcomingTasks),
      selectedTaskIds: new Set(),
    }));
  },

  bulkMove: async (ids, projectId) => {
    const uid = getUid();
    await Promise.all(
      ids.map((id) => firestoreTasks.updateTask(uid, id, { project_id: projectId })),
    );
    set({ selectedTaskIds: new Set() });
  },

  bulkUpdatePriority: async (ids, priority) => {
    const uid = getUid();
    await Promise.all(
      ids.map((id) => firestoreTasks.updateTask(uid, id, { priority })),
    );
    const updatePriority = (arr: Task[]) =>
      arr.map((t) =>
        ids.includes(t.id)
          ? { ...t, priority: priority as 1 | 2 | 3 | 4 }
          : t,
      );
    set((state) => ({
      tasks: updatePriority(state.tasks),
      todayTasks: updatePriority(state.todayTasks),
      overdueTasks: updatePriority(state.overdueTasks),
      upcomingTasks: updatePriority(state.upcomingTasks),
      selectedTaskIds: new Set(),
    }));
  },

  bulkUpdateDueDate: async (ids, dueDate) => {
    const uid = getUid();
    await Promise.all(
      ids.map((id) => firestoreTasks.updateTask(uid, id, { due_date: dueDate })),
    );
    const updateDue = (arr: Task[]) =>
      arr.map((t) =>
        ids.includes(t.id) ? { ...t, due_date: dueDate } : t,
      );
    set((state) => ({
      tasks: updateDue(state.tasks),
      todayTasks: updateDue(state.todayTasks),
      overdueTasks: updateDue(state.overdueTasks),
      upcomingTasks: updateDue(state.upcomingTasks),
      selectedTaskIds: new Set(),
    }));
  },

  subscribeToTaskCompletions: (uid: string) => {
    return subscribeToTaskCompletions(uid, (taskCompletions) => {
      set({ taskCompletions });
    });
  },

  subscribeToTasks: (uid: string) => {
    return firestoreTasks.subscribeToTasks(uid, (allTasks) => {
      // Partition tasks into today/overdue/upcoming based on due date
      const now = new Date();
      const todayStr = now.toISOString().slice(0, 10);
      const todayTasks: Task[] = [];
      const overdueTasks: Task[] = [];
      const upcomingTasks: Task[] = [];

      for (const task of allTasks) {
        if (task.status === 'done') continue;
        if (!task.due_date) continue;
        if (task.due_date === todayStr) {
          todayTasks.push(task);
        } else if (task.due_date < todayStr) {
          overdueTasks.push(task);
        } else {
          upcomingTasks.push(task);
        }
      }

      set({ tasks: allTasks, todayTasks, overdueTasks, upcomingTasks });
    });
  },

  setSelectedTask: (task) => set({ selectedTask: task }),
  clearError: () => set({ error: null }),

  removeTaskFromLists: (taskId) =>
    set((state) => ({
      tasks: removeFromArray(state.tasks, taskId),
      todayTasks: removeFromArray(state.todayTasks, taskId),
      overdueTasks: removeFromArray(state.overdueTasks, taskId),
      upcomingTasks: removeFromArray(state.upcomingTasks, taskId),
      selectedTask:
        state.selectedTask?.id === taskId ? null : state.selectedTask,
    })),

  updateTaskInLists: (task) =>
    set((state) => ({
      tasks: updateInArray(state.tasks, task.id, task),
      todayTasks: updateInArray(state.todayTasks, task.id, task),
      overdueTasks: updateInArray(state.overdueTasks, task.id, task),
      upcomingTasks: updateInArray(state.upcomingTasks, task.id, task),
      selectedTask:
        state.selectedTask?.id === task.id ? task : state.selectedTask,
    })),
}));
