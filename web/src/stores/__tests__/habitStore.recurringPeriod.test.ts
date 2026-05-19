import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Vitest 4 + jsdom 29 ship a non-functional `localStorage` stub — install
// the same in-memory shim used by `habitStore.startOfDayHour.test.ts`.
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

vi.mock('@/api/firestore/habits', () => ({
  getHabits: vi.fn(),
  getHabit: vi.fn(),
  getCompletions: vi.fn(),
  getAllCompletions: vi.fn(),
  createHabit: vi.fn(),
  updateHabit: vi.fn(),
  deleteHabit: vi.fn(),
  toggleCompletion: vi.fn(),
  subscribeToHabits: vi.fn(),
  subscribeToCompletions: vi.fn(),
}));
vi.mock('@/api/firestore/aiPreferences', () => ({
  DEFAULT_AI_FEATURES_ENABLED: true,
  getAiFeaturesEnabled: vi.fn(),
  setAiFeaturesEnabled: vi.fn(),
}));
vi.mock('@/api/firestore/taskBehaviorPreferences', () => ({
  DEFAULT_DAY_START_HOUR: 0,
  getDayStartHour: vi.fn(),
  setDayStartHour: vi.fn(),
  subscribeToDayStartHour: vi.fn(),
}));
vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: vi.fn(() => 'test-uid'),
  setFirebaseUid: vi.fn(),
}));
vi.mock('@/api/client', () => ({
  default: {},
  setAiFeaturesEnabledProvider: vi.fn(),
}));

import { useHabitStore } from '@/stores/habitStore';
import { useSettingsStore } from '@/stores/settingsStore';
import type { Habit, HabitCompletion, HabitFrequency } from '@/types/habit';

function makeHabit(overrides: Partial<Habit> = {}): Habit {
  return {
    id: 'habit-1',
    user_id: 'test-uid',
    name: 'Visit dentist',
    description: null,
    icon: '🦷',
    color: '#4A90D9',
    category: null,
    frequency: 'weekly',
    target_count: 3,
    active_days_json: null,
    is_active: true,
    created_at: '2026-05-13T00:00:00Z',
    updated_at: '2026-05-13T00:00:00Z',
    ...overrides,
  };
}

function makeCompletion(overrides: Partial<HabitCompletion> = {}): HabitCompletion {
  return {
    id: `c-${overrides.date ?? '2026-05-18'}`,
    habit_id: 'habit-1',
    date: '2026-05-18',
    count: 1,
    created_at: '2026-05-18T12:00:00Z',
    ...overrides,
  };
}

describe('habitStore — non-daily period semantics', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    useSettingsStore.setState({ startOfDayHour: 0 });
    useHabitStore.setState({
      habits: [],
      completions: {},
      stats: {},
      selectedHabit: null,
      isLoading: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /**
   * The bug this locks: a weekly habit with `target_count = 3` (3 times
   * per week) was rendering 0/3 today and refusing to mark "done" unless
   * the user logged 3 completions on a single day. The correct semantics
   * — mirroring Android `HabitRepository.getHabitsWithFullStatus` — is
   * that `target_count` for non-daily habits is the *period* total with
   * an implicit per-day target of 1.
   */
  it('weekly: isTodayCompleted = true when period target met across multiple days', () => {
    // 2026-05-20 (Wed) — same week as 2026-05-18 Mon.
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'weekly', target_count: 3 })],
      completions: {
        'habit-1': [
          makeCompletion({ date: '2026-05-18', count: 1 }),
          makeCompletion({ date: '2026-05-19', count: 1 }),
          makeCompletion({ date: '2026-05-20', count: 1 }),
        ],
      },
    });

    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(true);
    expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(3);
    // Today's count is still 1 — the period total drives "completed",
    // not the daily count.
    expect(useHabitStore.getState().getTodayCount('habit-1')).toBe(1);
  });

  it('weekly: isTodayCompleted = false when period target NOT yet met', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'weekly', target_count: 3 })],
      completions: {
        'habit-1': [
          makeCompletion({ date: '2026-05-18', count: 1 }),
          makeCompletion({ date: '2026-05-19', count: 1 }),
        ],
      },
    });
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);
    expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(2);
  });

  it('weekly: completions from last week do not leak into the current period', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'weekly', target_count: 2 })],
      completions: {
        'habit-1': [
          // Previous week — 2026-05-11 (Mon) through 2026-05-17 (Sun).
          makeCompletion({ date: '2026-05-11', count: 1 }),
          makeCompletion({ date: '2026-05-13', count: 1 }),
          // Current week — 2026-05-18 (Mon) onward.
          makeCompletion({ date: '2026-05-18', count: 1 }),
        ],
      },
    });
    expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(1);
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);
  });

  /**
   * The user-reported symptom that drove this PR: "the toggle won't mark
   * done" on the Recurring tab. After PR-this, tapping once on a
   * monthly target=1 habit lights up the checkbox.
   */
  it('monthly target=1: one completion this month → today completed', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'monthly', target_count: 1 })],
      completions: {
        'habit-1': [makeCompletion({ date: '2026-05-05', count: 1 })],
      },
    });
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(true);
    expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(1);
  });

  it('monthly: April completion does NOT count for a May read', () => {
    vi.setSystemTime(new Date(2026, 4, 5, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'monthly', target_count: 1 })],
      completions: {
        'habit-1': [makeCompletion({ date: '2026-04-28', count: 1 })],
      },
    });
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);
    expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(0);
  });

  it.each<[HabitFrequency, string, string, boolean]>([
    ['fortnightly', '2026-05-25', '2026-05-18', true],
    ['fortnightly', '2026-06-02', '2026-05-18', false],
    ['bimonthly', '2026-06-15', '2026-05-18', true],
    ['bimonthly', '2026-07-15', '2026-05-18', false],
    ['quarterly', '2026-06-15', '2026-04-02', true],
    ['quarterly', '2026-07-01', '2026-04-02', false],
  ])(
    '%s: completion on %s counts for read on %s → %s',
    (frequency, todayIsoLocal, completionDate, expected) => {
      const [y, m, d] = todayIsoLocal.split('-').map(Number);
      vi.setSystemTime(new Date(y, m - 1, d, 12, 0, 0));
      useHabitStore.setState({
        habits: [makeHabit({ frequency, target_count: 1 })],
        completions: {
          'habit-1': [makeCompletion({ date: completionDate, count: 1 })],
        },
      });
      expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(expected);
    },
  );

  /**
   * Daily semantics MUST stay unchanged — the bug was scoped to non-daily
   * habits and a regression here would re-introduce the
   * "X/target today" counter that PR-this preserved for daily habits.
   */
  it('daily target=3: still needs 3 completions today to mark done', () => {
    vi.setSystemTime(new Date(2026, 4, 18, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'daily', target_count: 3 })],
      completions: {
        'habit-1': [makeCompletion({ date: '2026-05-18', count: 2 })],
      },
    });
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);
    expect(useHabitStore.getState().getTodayCount('habit-1')).toBe(2);

    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'daily', target_count: 3 })],
      completions: {
        'habit-1': [makeCompletion({ date: '2026-05-18', count: 3 })],
      },
    });
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(true);
  });

  it('getTodayProgress counts a weekly habit as completed when period target is met', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'weekly', target_count: 2 })],
      completions: {
        'habit-1': [
          makeCompletion({ date: '2026-05-18', count: 1 }),
          makeCompletion({ date: '2026-05-19', count: 1 }),
        ],
      },
    });
    expect(useHabitStore.getState().getTodayProgress()).toEqual({
      completed: 1,
      total: 1,
    });
  });
});
