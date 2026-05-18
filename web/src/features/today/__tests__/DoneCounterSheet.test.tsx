import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Task } from '@/types/task';

// jsdom 29 ships a non-functional localStorage stub. Mirror the
// in-memory shim other today-section tests use.
vi.hoisted(() => {
  const existing = (globalThis as { localStorage?: Storage }).localStorage;
  if (existing && typeof existing.clear === 'function') return;
  const backing = new Map<string, string>();
  const shim: Storage = {
    get length() {
      return backing.size;
    },
    clear: () => backing.clear(),
    getItem: (key) => (backing.has(key) ? backing.get(key)! : null),
    key: (index) => Array.from(backing.keys())[index] ?? null,
    removeItem: (key) => {
      backing.delete(key);
    },
    setItem: (key, value) => {
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

vi.mock('@/api/firestore/habits', () => ({
  getHabits: vi.fn(),
  getCompletions: vi.fn(),
  getAllCompletions: vi.fn(),
  createHabit: vi.fn(),
  updateHabit: vi.fn(),
  deleteHabit: vi.fn(),
  toggleCompletion: vi.fn(),
  subscribeToHabits: vi.fn(() => () => undefined),
  subscribeToCompletions: vi.fn(() => () => undefined),
}));
vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: vi.fn(() => 'test-uid'),
  setFirebaseUid: vi.fn(),
}));
vi.mock('@/api/client', () => ({
  default: {},
  setAiFeaturesEnabledProvider: vi.fn(),
}));
vi.mock('@/api/firestore/taskBehaviorPreferences', () => ({
  DEFAULT_DAY_START_HOUR: 0,
  getDayStartHour: vi.fn(),
  setDayStartHour: vi.fn(),
  subscribeToDayStartHour: vi.fn(),
}));

// `sonner` toasts render imperatively; we don't need to verify them
// in this suite, just stub them so the test runner doesn't choke on
// the unmounted Toaster portal.
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

import { DoneCounterSheet } from '@/features/today/DoneCounterSheet';
import { useTaskStore } from '@/stores/taskStore';
import { useHabitStore } from '@/stores/habitStore';

function task(id: string, overrides: Partial<Task> = {}): Task {
  return {
    id,
    project_id: 'p',
    user_id: 'u',
    parent_id: null,
    title: `Task ${id}`,
    description: null,
    notes: null,
    status: 'done',
    priority: 3,
    due_date: null,
    due_time: null,
    planned_date: null,
    completed_at: null,
    urgency_score: 0,
    recurrence_json: null,
    eisenhower_quadrant: null,
    eisenhower_updated_at: null,
    estimated_duration: null,
    actual_duration: null,
    sort_order: 0,
    depth: 0,
    created_at: '',
    updated_at: '',
    ...overrides,
  };
}

/**
 * Component tests for the Today Done-counter sheet's recurring-task
 * branching — the web port of Android `CompletedTaskItem`'s "Already
 * Completed" choice dialog. The test guarantees:
 *
 *   1. Non-recurring completed tasks expose Undo only (no dialog, no
 *      Log Again button).
 *   2. Recurring completed tasks expose Log Again on the row AND open
 *      the "Already Completed" dialog when the title is tapped, with
 *      the exact strings Android uses.
 *   3. Each branch invokes the right taskStore action
 *      (`uncompleteTask` vs. `logAdditionalCompletion`).
 */
describe('DoneCounterSheet — Log Again parity', () => {
  let uncompleteSpy: ReturnType<typeof vi.fn>;
  let logAgainSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    uncompleteSpy = vi.fn(async () => undefined);
    logAgainSpy = vi.fn(async () => undefined);
    useTaskStore.setState({
      tasks: [],
      todayTasks: [],
      overdueTasks: [],
      upcomingTasks: [],
      uncompleteTask: uncompleteSpy as unknown as ReturnType<
        typeof useTaskStore.getState
      >['uncompleteTask'],
      logAdditionalCompletion: logAgainSpy as unknown as ReturnType<
        typeof useTaskStore.getState
      >['logAdditionalCompletion'],
    });
    useHabitStore.setState({
      habits: [],
      completions: {},
    });
    localStorage.clear();
  });

  it('non-recurring task: row Undo button calls uncompleteTask directly (no dialog)', async () => {
    useTaskStore.setState({
      todayTasks: [task('t1', { title: 'Plain Task', status: 'done' })],
    });

    const user = userEvent.setup();
    render(<DoneCounterSheet isOpen onClose={() => undefined} />);

    // No Log Again affordance on a non-recurring task.
    expect(
      screen.queryByRole('button', { name: /log again plain task/i }),
    ).not.toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: /undo task plain task/i }),
    );

    await waitFor(() => {
      expect(uncompleteSpy).toHaveBeenCalledWith('t1');
    });
    expect(logAgainSpy).not.toHaveBeenCalled();
    // No dialog ever opened.
    expect(
      screen.queryByRole('heading', { name: /already completed/i }),
    ).not.toBeInTheDocument();
  });

  it('recurring task: tapping the title opens the Already Completed dialog with Android copy', async () => {
    useTaskStore.setState({
      todayTasks: [
        task('t2', {
          title: 'Daily Walk',
          status: 'done',
          recurrence_json: '{"type":"daily"}',
        }),
      ],
    });

    const user = userEvent.setup();
    render(<DoneCounterSheet isOpen onClose={() => undefined} />);

    // The tap-target button on the title row should be there.
    await user.click(
      screen.getByRole('button', {
        name: /choose action for completed task daily walk/i,
      }),
    );

    // Dialog title.
    expect(
      screen.getByRole('heading', { name: /already completed/i }),
    ).toBeInTheDocument();
    // Body text byte-matches Android `CompletedTaskItem`'s AlertDialog
    // body (`TodaySwipeableTaskItem.kt` ~line 407).
    expect(
      screen.getByText(
        /"Daily Walk" is already checked off for today\. Log another completion or mark it incomplete\?/,
      ),
    ).toBeInTheDocument();
    // Both buttons rendered with the Android strings.
    expect(
      screen.getByRole('button', { name: /^log again$/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /^mark incomplete$/i }),
    ).toBeInTheDocument();
  });

  it('recurring task: dialog Log Again invokes logAdditionalCompletion', async () => {
    useTaskStore.setState({
      todayTasks: [
        task('t3', {
          title: 'Daily Walk',
          status: 'done',
          recurrence_json: '{"type":"daily"}',
        }),
      ],
    });

    const user = userEvent.setup();
    render(<DoneCounterSheet isOpen onClose={() => undefined} />);

    await user.click(
      screen.getByRole('button', {
        name: /choose action for completed task daily walk/i,
      }),
    );
    await user.click(screen.getByRole('button', { name: /^log again$/i }));

    await waitFor(() => {
      expect(logAgainSpy).toHaveBeenCalledWith('t3');
    });
    expect(uncompleteSpy).not.toHaveBeenCalled();
  });

  it('recurring task: dialog Mark Incomplete invokes uncompleteTask', async () => {
    useTaskStore.setState({
      todayTasks: [
        task('t4', {
          title: 'Daily Walk',
          status: 'done',
          recurrence_json: '{"type":"daily"}',
        }),
      ],
    });

    const user = userEvent.setup();
    render(<DoneCounterSheet isOpen onClose={() => undefined} />);

    await user.click(
      screen.getByRole('button', {
        name: /choose action for completed task daily walk/i,
      }),
    );
    await user.click(
      screen.getByRole('button', { name: /^mark incomplete$/i }),
    );

    await waitFor(() => {
      expect(uncompleteSpy).toHaveBeenCalledWith('t4');
    });
    expect(logAgainSpy).not.toHaveBeenCalled();
  });

  it('recurring task: direct row Log Again button invokes logAdditionalCompletion without opening the dialog', async () => {
    useTaskStore.setState({
      todayTasks: [
        task('t5', {
          title: 'Daily Walk',
          status: 'done',
          recurrence_json: '{"type":"daily"}',
        }),
      ],
    });

    const user = userEvent.setup();
    render(<DoneCounterSheet isOpen onClose={() => undefined} />);

    await user.click(
      screen.getByRole('button', { name: /^log again daily walk$/i }),
    );

    await waitFor(() => {
      expect(logAgainSpy).toHaveBeenCalledWith('t5');
    });
    expect(uncompleteSpy).not.toHaveBeenCalled();
    expect(
      screen.queryByRole('heading', { name: /already completed/i }),
    ).not.toBeInTheDocument();
  });
});
