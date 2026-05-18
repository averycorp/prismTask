import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Vitest 4 + jsdom 29 ship a non-functional `localStorage` stub. Same
// in-memory shim as `habitStore.startOfDayHour.test.ts`.
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

describe('habitStore.getTodayProgress — medication built-in filter', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 4, 18, 12, 0, 0));
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

  it('excludes the medication built-in from the today-progress total', () => {
    useHabitStore.setState({
      habits: [
        makeHabit({ id: 'water', name: 'Drink water' }),
        makeHabit({
          id: 'meds',
          name: 'Medication',
          template_key: 'builtin_medication',
          is_built_in: true,
        }),
      ],
      completions: {},
    });

    expect(useHabitStore.getState().getTodayProgress()).toEqual({
      completed: 0,
      total: 1,
    });
  });

  it('excludes a renamed medication built-in by template_key', () => {
    useHabitStore.setState({
      habits: [
        makeHabit({ id: 'water', name: 'Drink water' }),
        makeHabit({
          id: 'meds',
          name: 'My Pills',
          template_key: 'builtin_medication',
          is_built_in: true,
        }),
      ],
      completions: {
        meds: [makeCompletion({ habit_id: 'meds', date: '2026-05-18' })],
      },
    });

    const progress = useHabitStore.getState().getTodayProgress();
    expect(progress.total).toBe(1);
    expect(progress.completed).toBe(0);
  });

  it('keeps user-created habits named "Medication" in the count', () => {
    useHabitStore.setState({
      habits: [
        makeHabit({
          id: 'user-med',
          name: 'Medication',
          is_built_in: false,
          template_key: null,
        }),
      ],
      completions: {},
    });

    expect(useHabitStore.getState().getTodayProgress()).toEqual({
      completed: 0,
      total: 1,
    });
  });
});
