import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Vitest 4 + jsdom 29 ship a non-functional `localStorage` stub. The
// settingsStore writes to localStorage on every `setSetting` call, so we
// install a minimal in-memory shim before any store imports run. See
// `settingsStore.startOfDayHour.test.ts` for the mirror of this fix.
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

// Stub the Firestore-backed habits API and ancillary modules pulled in
// transitively by the habit store. The store never calls these in the
// tests below — the assertions all read computed getters from in-memory
// state — but the imports are evaluated when the module loads.
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
import type { Habit, HabitCompletion } from '@/types/habit';

function makeHabit(overrides: Partial<Habit> = {}): Habit {
  return {
    id: 'habit-1',
    user_id: 'test-uid',
    name: 'Drink water',
    description: null,
    icon: '💧',
    color: '#4A90D9',
    category: null,
    frequency: 'daily',
    target_count: 1,
    active_days_json: null,
    is_active: true,
    created_at: '2026-05-13T00:00:00Z',
    updated_at: '2026-05-13T00:00:00Z',
    ...overrides,
  };
}

function makeCompletion(overrides: Partial<HabitCompletion> = {}): HabitCompletion {
  return {
    id: 'completion-1',
    habit_id: 'habit-1',
    date: '2026-05-13',
    count: 1,
    created_at: '2026-05-13T23:00:00Z',
    ...overrides,
  };
}

describe('habitStore — Start-of-Day awareness', () => {
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
   * Repro of the user-reported "habits checked off on phone don't appear
   * checked off on web" bug.
   *
   * Setup: SoD = 4. The user completes a habit at 02:00 local on
   * 2026-05-14. Android writes `completedDateLocal = "2026-05-13"`
   * because the logical day with SoD = 4 still belongs to yesterday
   * (2 < 4 → roll back one calendar day; see Android
   * `HabitRepository.completeHabit` → `epochToLocalDateString` and
   * `DayBoundary.normalizeToDayStart`).
   *
   * Web reads that completion via the Firestore real-time listener,
   * stores it under `completions["habit-1"] = [{ date: "2026-05-13" }]`.
   * When the user then opens the web app at 02:30 local on 2026-05-14
   * the UI calls `isTodayCompleted("habit-1")`. With SoD = 4 the
   * **logical today** is also 2026-05-13 — so the getter must return
   * `true`.
   *
   * On main, `todayStr()` returned `format(new Date(), 'yyyy-MM-dd')` =
   * "2026-05-14", and the lookup missed → returned `false`. This test
   * locks the SoD-aware behaviour.
   */
  it('isTodayCompleted honours SoD hour: 02:00 local with SoD=4 reads yesterday-keyed completion', () => {
    // 2026-05-14 02:30 local → logical today is 2026-05-13.
    vi.setSystemTime(new Date(2026, 4, 14, 2, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 4 });
    useHabitStore.setState({
      habits: [makeHabit()],
      completions: {
        'habit-1': [makeCompletion({ date: '2026-05-13' })],
      },
    });

    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(true);
    expect(useHabitStore.getState().getTodayCount('habit-1')).toBe(1);
    expect(useHabitStore.getState().getTodayProgress()).toEqual({
      completed: 1,
      total: 1,
    });
  });

  it('isTodayCompleted with SoD=0 (default) still uses calendar midnight', () => {
    // 2026-05-14 02:30 local → with SoD = 0 the logical today is
    // 2026-05-14, so a 2026-05-13 completion is *yesterday* and the
    // habit reads as not-yet-completed.
    vi.setSystemTime(new Date(2026, 4, 14, 2, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 0 });
    useHabitStore.setState({
      habits: [makeHabit()],
      completions: {
        'habit-1': [makeCompletion({ date: '2026-05-13' })],
      },
    });

    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(false);
    expect(useHabitStore.getState().getTodayCount('habit-1')).toBe(0);
  });

  it('isTodayCompleted finds same-logical-day completion for late-night SoD=4 scenario', () => {
    // 2026-05-13 23:00 local → with SoD = 4 the logical day is still
    // 2026-05-13. A "today" completion written by Android at this hour
    // uses date "2026-05-13", and web should agree.
    vi.setSystemTime(new Date(2026, 4, 13, 23, 0, 0));
    useSettingsStore.setState({ startOfDayHour: 4 });
    useHabitStore.setState({
      habits: [makeHabit()],
      completions: {
        'habit-1': [makeCompletion({ date: '2026-05-13' })],
      },
    });

    expect(useHabitStore.getState().isTodayCompleted('habit-1')).toBe(true);
  });
});
