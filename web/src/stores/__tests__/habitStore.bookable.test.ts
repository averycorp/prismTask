import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Vitest 4 + jsdom 29 ship a non-functional `localStorage` stub — install
// the same in-memory shim used by the sibling habitStore tests.
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
vi.mock('@/api/firestore/habitLogs', () => ({
  createLog: vi.fn(),
  deleteLog: vi.fn(),
  subscribeToHabitLogs: vi.fn(),
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
import { useHabitLogStore } from '@/stores/habitLogStore';
import { useSettingsStore } from '@/stores/settingsStore';
import type { Habit } from '@/types/habit';
import type { HabitLog } from '@/api/firestore/habitLogs';

function makeHabit(overrides: Partial<Habit> = {}): Habit {
  return {
    id: 'habit-1',
    user_id: 'test-uid',
    name: 'Therapy session',
    description: null,
    icon: '🛋️',
    color: '#4A90D9',
    category: null,
    frequency: 'weekly',
    target_count: 1,
    active_days_json: null,
    is_active: true,
    is_bookable: true,
    created_at: '2026-05-13T00:00:00Z',
    updated_at: '2026-05-13T00:00:00Z',
    ...overrides,
  };
}

function makeLog(overrides: Partial<HabitLog> = {}): HabitLog {
  return {
    id: `log-${overrides.date ?? Date.now()}`,
    habit_id: 'habit-1',
    date: overrides.date ?? Date.now(),
    notes: null,
    created_at: Date.now(),
    ...overrides,
  };
}

function seedLogs(habitId: string, logs: HabitLog[]) {
  useHabitLogStore.setState({
    logsByHabit: { [habitId]: logs.slice().sort((a, b) => b.date - a.date) },
  });
}

describe('habitStore — bookable habit recency', () => {
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
    useHabitLogStore.setState({
      logsByHabit: {},
      isLoading: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /**
   * The bug this locks: a weekly bookable habit (e.g. "Therapy") booked
   * via `HabitBookingDialog` was rendering as "not done this week" on
   * Today + Habits because the recency check only read `completions`.
   * Bookings write to `habit_logs`, so the user's perfectly-real booking
   * vanished from the indicator.
   */
  it('weekly bookable: a single log inside the current week marks done', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'weekly', target_count: 1 })],
    });
    seedLogs('habit-1', [
      makeLog({ date: new Date(2026, 4, 19, 14, 0, 0).getTime() }),
    ]);
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(true);
    expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(1);
  });

  it('weekly bookable: a log from last week does NOT mark this week done', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'weekly', target_count: 1 })],
    });
    seedLogs('habit-1', [
      makeLog({ date: new Date(2026, 4, 12, 14, 0, 0).getTime() }),
    ]);
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);
    expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(0);
  });

  it('monthly bookable target=2: two logs in the same month → done', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'monthly', target_count: 2 })],
    });
    seedLogs('habit-1', [
      makeLog({
        id: 'log-a',
        date: new Date(2026, 4, 6, 9, 0, 0).getTime(),
      }),
      makeLog({
        id: 'log-b',
        date: new Date(2026, 4, 17, 9, 0, 0).getTime(),
      }),
    ]);
    expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(2);
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(true);
  });

  it('monthly bookable target=2: one log in this month + one in next month → not done', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'monthly', target_count: 2 })],
    });
    seedLogs('habit-1', [
      makeLog({
        id: 'log-a',
        date: new Date(2026, 4, 6, 9, 0, 0).getTime(),
      }),
      makeLog({
        id: 'log-b',
        date: new Date(2026, 5, 4, 9, 0, 0).getTime(),
      }),
    ]);
    expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(1);
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);
  });

  it('daily bookable: a log today marks today done; a log yesterday does not', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'daily', target_count: 1 })],
    });
    seedLogs('habit-1', [
      makeLog({ date: new Date(2026, 4, 20, 8, 30, 0).getTime() }),
    ]);
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(true);
    expect(useHabitStore.getState().getTodayCount('habit-1')).toBe(1);

    seedLogs('habit-1', [
      makeLog({ date: new Date(2026, 4, 19, 23, 30, 0).getTime() }),
    ]);
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);
    expect(useHabitStore.getState().getTodayCount('habit-1')).toBe(0);
  });

  it('daily bookable + SoD=4: a log at 02:00 logically belongs to yesterday', () => {
    // Reference time: 2026-05-20 10:00 (logical day 2026-05-20).
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useSettingsStore.setState({ startOfDayHour: 4 });
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'daily', target_count: 1 })],
    });
    // Log at 2026-05-20 02:00 — before today's SoD, so the logical day
    // is 2026-05-19. Should NOT count for today.
    seedLogs('habit-1', [
      makeLog({ date: new Date(2026, 4, 20, 2, 0, 0).getTime() }),
    ]);
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);

    // Log at 2026-05-20 05:00 — after SoD, logical day 2026-05-20 → done.
    seedLogs('habit-1', [
      makeLog({ date: new Date(2026, 4, 20, 5, 0, 0).getTime() }),
    ]);
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(true);
  });

  it('bookable: logs ADD to checkbox completions, do not replace them', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'weekly', target_count: 3 })],
      completions: {
        'habit-1': [
          {
            id: 'c-1',
            habit_id: 'habit-1',
            date: '2026-05-18',
            count: 1,
            created_at: '2026-05-18T10:00:00Z',
          },
        ],
      },
    });
    seedLogs('habit-1', [
      makeLog({
        id: 'log-a',
        date: new Date(2026, 4, 19, 9, 0, 0).getTime(),
      }),
      makeLog({
        id: 'log-b',
        date: new Date(2026, 4, 20, 9, 0, 0).getTime(),
      }),
    ]);
    // 1 completion + 2 logs = 3 → meets target.
    expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(3);
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(true);
  });

  it('non-bookable habit: logs are IGNORED even if present', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [
        makeHabit({
          frequency: 'weekly',
          target_count: 1,
          is_bookable: false,
        }),
      ],
    });
    // A stray log on a non-bookable habit must not light up the
    // indicator — log-folding is gated on `is_bookable`.
    seedLogs('habit-1', [
      makeLog({ date: new Date(2026, 4, 19, 9, 0, 0).getTime() }),
    ]);
    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);
    expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(0);
  });

  it('getTodayProgress folds bookable logs into the period count', () => {
    vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'weekly', target_count: 1 })],
    });
    seedLogs('habit-1', [
      makeLog({ date: new Date(2026, 4, 19, 9, 0, 0).getTime() }),
    ]);
    expect(useHabitStore.getState().getTodayProgress()).toEqual({
      completed: 1,
      total: 1,
    });
  });

  it('getWeekCompletions lights up the day a bookable habit was logged', () => {
    // Tuesday 2026-05-19, logical day = 2026-05-19.
    vi.setSystemTime(new Date(2026, 4, 19, 12, 0, 0));
    useHabitStore.setState({
      habits: [makeHabit({ frequency: 'weekly', target_count: 1 })],
    });
    seedLogs('habit-1', [
      makeLog({ date: new Date(2026, 4, 19, 9, 0, 0).getTime() }),
    ]);
    const week = useHabitStore.getState().getWeekCompletions('habit-1');
    // Week is Mon..Sun. The Tuesday dot should be lit.
    expect(week[1]).toBe(true);
    // No other days lit (no completions, no other logs).
    expect(week.filter((b) => b).length).toBe(1);
  });

  /**
   * Regression: the web app used to fold *future-dated* bookings into the
   * "done this period" count, so booking a therapy session for next
   * Friday made a weekly habit look completed today. Bookings that
   * haven't happened yet are not completions — they should surface
   * separately via `getPeriodBookings` / `getWeekBookings`.
   */
  describe('future bookings are distinct from completions', () => {
    it('weekly bookable: a future booking inside this week does NOT mark done', () => {
      // Monday 2026-05-18 noon — Friday 2026-05-22 is still in this week.
      vi.setSystemTime(new Date(2026, 4, 18, 12, 0, 0));
      useHabitStore.setState({
        habits: [makeHabit({ frequency: 'weekly', target_count: 1 })],
      });
      seedLogs('habit-1', [
        makeLog({ date: new Date(2026, 4, 22, 14, 0, 0).getTime() }),
      ]);
      expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);
      expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(0);
      expect(useHabitStore.getState().getPeriodBookings('habit-1')).toBe(1);
    });

    it('monthly bookable target=2: one past log + one future booking → 1 done, 1 booked', () => {
      vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
      useHabitStore.setState({
        habits: [makeHabit({ frequency: 'monthly', target_count: 2 })],
      });
      seedLogs('habit-1', [
        makeLog({
          id: 'log-past',
          date: new Date(2026, 4, 6, 9, 0, 0).getTime(),
        }),
        makeLog({
          id: 'log-future',
          date: new Date(2026, 4, 28, 9, 0, 0).getTime(),
        }),
      ]);
      expect(useHabitStore.getState().getPeriodCompletions('habit-1')).toBe(1);
      expect(useHabitStore.getState().getPeriodBookings('habit-1')).toBe(1);
      expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);
    });

    it('getTodayProgress excludes future bookings from the completed tally', () => {
      vi.setSystemTime(new Date(2026, 4, 18, 12, 0, 0));
      useHabitStore.setState({
        habits: [makeHabit({ frequency: 'weekly', target_count: 1 })],
      });
      seedLogs('habit-1', [
        makeLog({ date: new Date(2026, 4, 22, 14, 0, 0).getTime() }),
      ]);
      expect(useHabitStore.getState().getTodayProgress()).toEqual({
        completed: 0,
        total: 1,
      });
    });

    it('getWeekCompletions does NOT light up the day of a future booking', () => {
      // Monday 2026-05-18 — booking for Friday 2026-05-22.
      vi.setSystemTime(new Date(2026, 4, 18, 12, 0, 0));
      useHabitStore.setState({
        habits: [makeHabit({ frequency: 'weekly', target_count: 1 })],
      });
      seedLogs('habit-1', [
        makeLog({ date: new Date(2026, 4, 22, 14, 0, 0).getTime() }),
      ]);
      const week = useHabitStore.getState().getWeekCompletions('habit-1');
      expect(week.filter((b) => b).length).toBe(0);
      const bookings = useHabitStore.getState().getWeekBookings('habit-1');
      // Friday is index 4 (Mon=0..Sun=6).
      expect(bookings[4]).toBe(true);
      expect(bookings.filter((b) => b).length).toBe(1);
    });

    it('getWeekBookings is all-false for non-bookable habits', () => {
      vi.setSystemTime(new Date(2026, 4, 18, 12, 0, 0));
      useHabitStore.setState({
        habits: [
          makeHabit({
            frequency: 'weekly',
            target_count: 1,
            is_bookable: false,
          }),
        ],
      });
      seedLogs('habit-1', [
        makeLog({ date: new Date(2026, 4, 22, 14, 0, 0).getTime() }),
      ]);
      const bookings = useHabitStore.getState().getWeekBookings('habit-1');
      expect(bookings.every((b) => !b)).toBe(true);
    });

    it('getPeriodBookings is 0 for daily habits (no future inside a 1-day period)', () => {
      vi.setSystemTime(new Date(2026, 4, 20, 10, 0, 0));
      useHabitStore.setState({
        habits: [makeHabit({ frequency: 'daily', target_count: 1 })],
      });
      // Even with a log scheduled for tomorrow, daily habits short-circuit
      // because their period is exactly today.
      seedLogs('habit-1', [
        makeLog({ date: new Date(2026, 4, 21, 9, 0, 0).getTime() }),
      ]);
      expect(useHabitStore.getState().getPeriodBookings('habit-1')).toBe(0);
    });

    it('completion on a day with a same-day booking wins over the booking marker', () => {
      // Tuesday 2026-05-19. Completion logged for today; an extra log
      // dated "tomorrow" should not mark Tuesday as booked.
      vi.setSystemTime(new Date(2026, 4, 19, 12, 0, 0));
      useHabitStore.setState({
        habits: [makeHabit({ frequency: 'weekly', target_count: 2 })],
        completions: {
          'habit-1': [
            {
              id: 'c-1',
              habit_id: 'habit-1',
              date: '2026-05-19',
              count: 1,
              created_at: '2026-05-19T12:00:00Z',
            },
          ],
        },
      });
      seedLogs('habit-1', [
        makeLog({ date: new Date(2026, 4, 22, 9, 0, 0).getTime() }),
      ]);
      const week = useHabitStore.getState().getWeekCompletions('habit-1');
      const bookings = useHabitStore.getState().getWeekBookings('habit-1');
      // Tuesday (idx 1): completed (true), not booked.
      expect(week[1]).toBe(true);
      expect(bookings[1]).toBe(false);
      // Friday (idx 4): not completed, but booked.
      expect(week[4]).toBe(false);
      expect(bookings[4]).toBe(true);
    });
  });
});
