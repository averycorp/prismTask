import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

// Vitest 4 + jsdom 29 ship a non-functional `localStorage` stub. Some
// transitively imported modules (settingsStore via taskStore) write to
// localStorage on initialization, so we install a minimal in-memory
// shim before any store imports run. Mirrors `habitStore.startOfDayHour.test.ts`.
vi.hoisted(() => {
  const existing = (globalThis as { localStorage?: Storage }).localStorage;
  if (existing && typeof existing.clear === 'function') return;
  const backing = new Map<string, string>();
  const shim: Storage = {
    get length() {
      return backing.size;
    },
    clear: () => backing.clear(),
    getItem: (key: string) => (backing.has(key) ? backing.get(key)! : null),
    key: (index: number) => Array.from(backing.keys())[index] ?? null,
    removeItem: (key: string) => {
      backing.delete(key);
    },
    setItem: (key: string, value: string) => {
      backing.set(key, String(value));
    },
  };
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: shim,
  });
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: shim,
    });
  }
});

const {
  updateTaskMock,
  createTaskMock,
  deleteTaskMock,
  subscribeToTasksMock,
  getTaskMock,
  getSubtasksMock,
  recordCompletionMock,
  removeCompletionMock,
  getAllCompletionsMock,
  subscribeToCompletionsMock,
  getFirebaseUidMock,
} = vi.hoisted(() => ({
  updateTaskMock: vi.fn(),
  createTaskMock: vi.fn(),
  deleteTaskMock: vi.fn(),
  subscribeToTasksMock: vi.fn(),
  getTaskMock: vi.fn(),
  getSubtasksMock: vi.fn(),
  recordCompletionMock: vi.fn(),
  removeCompletionMock: vi.fn(),
  getAllCompletionsMock: vi.fn(),
  subscribeToCompletionsMock: vi.fn(),
  getFirebaseUidMock: vi.fn(() => 'test-uid'),
}));

vi.mock('@/api/firestore/tasks', () => ({
  updateTask: updateTaskMock,
  createTask: createTaskMock,
  deleteTask: deleteTaskMock,
  subscribeToTasks: subscribeToTasksMock,
  getTask: getTaskMock,
  getSubtasks: getSubtasksMock,
  getTasksByProject: vi.fn(),
  getTodayTasks: vi.fn(),
  getOverdueTasks: vi.fn(),
  getUpcomingTasks: vi.fn(),
  getAllTasks: vi.fn(),
  setTagsForTask: vi.fn(),
}));

vi.mock('@/api/firestore/taskCompletions', () => ({
  recordTaskCompletion: recordCompletionMock,
  removeTaskCompletion: removeCompletionMock,
  getAllTaskCompletions: getAllCompletionsMock,
  subscribeToTaskCompletions: subscribeToCompletionsMock,
}));

vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: getFirebaseUidMock,
  setFirebaseUid: vi.fn(),
}));

vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useTaskStore } from '@/stores/taskStore';
import type { Task } from '@/types/task';
import type { TaskCompletion } from '@/api/firestore/taskCompletions';

function makeTask(overrides: Partial<Task> = {}): Task {
  return {
    id: 'task-1',
    project_id: 'project-1',
    user_id: 'test-uid',
    parent_id: null,
    title: 'Water the plants',
    description: null,
    notes: null,
    status: 'todo',
    priority: 3,
    due_date: '2026-05-10', // 8 days before "today" in tests
    due_time: null,
    planned_date: null,
    completed_at: null,
    urgency_score: 0,
    recurrence_json: JSON.stringify({ type: 'daily', interval: 1 }),
    eisenhower_quadrant: null,
    eisenhower_updated_at: null,
    estimated_duration: null,
    actual_duration: null,
    sort_order: 0,
    depth: 0,
    created_at: '2026-05-01T00:00:00Z',
    updated_at: '2026-05-01T00:00:00Z',
    ...overrides,
  };
}

/**
 * Drain pending microtasks so fire-and-forget `void` promises in
 * `completeTask` / `uncompleteTask` settle before the test assertions
 * run. We need multiple ticks because the rollback path chains:
 * `uncompleteTask` → `await updateTask` → `void rollbackSpawnedRecurrence`
 * → `await getAllTaskCompletions` (or skip) → `await deleteTask` →
 * `removeTaskFromLists` (sync). Five flushes is enough headroom.
 */
async function flushMicrotasks(): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
  }
}

function resetStore() {
  useTaskStore.setState({
    tasks: [],
    todayTasks: [],
    overdueTasks: [],
    upcomingTasks: [],
    selectedTask: null,
    isLoading: false,
    error: null,
    selectedTaskIds: new Set(),
    taskCompletions: [],
    spawnedRecurrenceByTaskId: new Map(),
  });
}

describe('taskStore — recurrence parity (audit items 1 + 2)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    // 2026-05-18 14:00 local → recurrence anchored to *today* should
    // produce a next date of 2026-05-19 for a daily recurrence,
    // whereas the original due_date of 2026-05-10 would have produced
    // 2026-05-11 (the regression we're fixing).
    vi.setSystemTime(new Date(2026, 4, 18, 14, 0, 0));
    updateTaskMock.mockReset();
    createTaskMock.mockReset();
    deleteTaskMock.mockReset();
    recordCompletionMock.mockReset();
    removeCompletionMock.mockReset();
    getAllCompletionsMock.mockReset();
    subscribeToTasksMock.mockReset();
    subscribeToCompletionsMock.mockReset();
    getFirebaseUidMock.mockReset();
    getFirebaseUidMock.mockReturnValue('test-uid');
    recordCompletionMock.mockResolvedValue({} as TaskCompletion);
    removeCompletionMock.mockResolvedValue(undefined);
    getAllCompletionsMock.mockResolvedValue([]);
    deleteTaskMock.mockResolvedValue(undefined);
    resetStore();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /**
   * Audit item 1: next-occurrence anchored to completion time, not the
   * original due date. Android `RecurrenceEngine` uses `completedAt` so
   * a task done 8 days late still rolls forward relative to *now*.
   *
   * Pre-fix web behaviour: a daily task with `due_date = 2026-05-10`
   * completed on 2026-05-18 would spawn a next-occurrence with
   * `due_date = 2026-05-11` — still overdue. Post-fix: 2026-05-19.
   */
  it('anchors recurrence next-occurrence to completion time, not the original due_date', async () => {
    const task = makeTask({
      id: 'task-1',
      due_date: '2026-05-10',
      recurrence_json: JSON.stringify({ type: 'daily', interval: 1 }),
    });
    useTaskStore.setState({ tasks: [task] });

    updateTaskMock.mockResolvedValueOnce({ ...task, status: 'done' });
    createTaskMock.mockResolvedValueOnce(
      makeTask({ id: 'task-1-next', due_date: '2026-05-19', status: 'todo' }),
    );

    await useTaskStore.getState().completeTask('task-1');

    expect(createTaskMock).toHaveBeenCalledTimes(1);
    const [, payload] = createTaskMock.mock.calls[0];
    expect((payload as { due_date: string }).due_date).toBe('2026-05-19');
  });

  /**
   * Audit item 2: uncomplete deletes the spawned next-occurrence task.
   *
   * Same-session path — the in-memory `spawnedRecurrenceByTaskId` map
   * holds the spawned task id so we can roll back without a Firestore
   * read.
   */
  it('uncompleteTask deletes the spawned next-occurrence (in-memory same-session path)', async () => {
    const task = makeTask({ id: 'task-1' });
    const spawnedTask = makeTask({
      id: 'task-1-next',
      due_date: '2026-05-19',
      status: 'todo',
    });
    useTaskStore.setState({ tasks: [task] });

    updateTaskMock.mockResolvedValueOnce({ ...task, status: 'done' });
    createTaskMock.mockResolvedValueOnce(spawnedTask);
    await useTaskStore.getState().completeTask('task-1');

    // Sanity: in-memory map populated
    expect(
      useTaskStore.getState().spawnedRecurrenceByTaskId.get('task-1'),
    ).toBe('task-1-next');

    updateTaskMock.mockResolvedValueOnce({ ...task, status: 'todo' });
    await useTaskStore.getState().uncompleteTask('task-1');

    // Wait for the fire-and-forget rollback to flush.
    await flushMicrotasks();
    expect(deleteTaskMock).toHaveBeenCalledWith('test-uid', 'task-1-next');

    // Map cleared so a second uncomplete is a no-op.
    expect(
      useTaskStore.getState().spawnedRecurrenceByTaskId.has('task-1'),
    ).toBe(false);
  });

  /**
   * Audit item 2 (continued): cross-reload path.
   *
   * The in-memory map is empty (simulating a fresh browser session
   * after the user completed the recurring task in a previous
   * session). The rollback path must read the persisted
   * `task_completions` row and use its `spawned_task_id` field.
   */
  it('uncompleteTask falls back to task_completions.spawned_task_id when in-memory map is empty', async () => {
    const task = makeTask({ id: 'task-7' });
    useTaskStore.setState({ tasks: [task] });

    getAllCompletionsMock.mockResolvedValueOnce([
      {
        id: 'task-7__2026-05-10',
        task_id: 'task-7',
        completed_date_local: '2026-05-10',
        completed_date: 1620000000000,
        completed_at: 1620000000000,
        priority: 3,
        was_overdue: false,
        days_to_complete: null,
        tags: null,
        spawned_task_id: 'task-7-next',
      },
    ]);

    updateTaskMock.mockResolvedValueOnce({ ...task, status: 'todo' });
    await useTaskStore.getState().uncompleteTask('task-7');

    await flushMicrotasks();
    expect(deleteTaskMock).toHaveBeenCalledWith('test-uid', 'task-7-next');
  });

  /**
   * Recurring-completion path passes the spawned task id to the
   * Firestore `task_completions` row so the rollback survives reload.
   */
  it('records spawnedTaskId on the task_completions row', async () => {
    const task = makeTask({ id: 'task-3' });
    useTaskStore.setState({ tasks: [task] });

    updateTaskMock.mockResolvedValueOnce({ ...task, status: 'done' });
    createTaskMock.mockResolvedValueOnce(
      makeTask({ id: 'task-3-next', due_date: '2026-05-19', status: 'todo' }),
    );

    await useTaskStore.getState().completeTask('task-3');

    // The completion-row write is fire-and-forget; flush microtasks.
    await flushMicrotasks();
    expect(recordCompletionMock).toHaveBeenCalled();
    const input = recordCompletionMock.mock.calls[0][1] as {
      spawnedTaskId?: string;
    };
    expect(input.spawnedTaskId).toBe('task-3-next');
  });

  /**
   * Non-recurring completion path: spawnedTaskId stays `undefined`,
   * and the in-memory map is not populated. This is the regression
   * sentinel for the simple-task path.
   */
  it('does not populate spawned id for non-recurring tasks', async () => {
    const task = makeTask({ id: 'task-9', recurrence_json: null });
    useTaskStore.setState({ tasks: [task] });

    updateTaskMock.mockResolvedValueOnce({ ...task, status: 'done' });

    await useTaskStore.getState().completeTask('task-9');

    expect(createTaskMock).not.toHaveBeenCalled();
    expect(
      useTaskStore.getState().spawnedRecurrenceByTaskId.has('task-9'),
    ).toBe(false);

    await flushMicrotasks();
    expect(recordCompletionMock).toHaveBeenCalled();
    const input = recordCompletionMock.mock.calls[0][1] as {
      spawnedTaskId?: string;
    };
    expect(input.spawnedTaskId).toBeUndefined();
  });
});
