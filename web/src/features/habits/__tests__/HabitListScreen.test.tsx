import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

// Mirror the jsdom localStorage shim used elsewhere — vitest's jsdom
// ships a non-functional stub, and HabitListScreen reads through
// localStorage transitively via builtInHabitReconciler.
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

// HabitListScreen mounts and triggers `fetchHabits()`. The store
// pipes the mocked return value straight into `set({ habits })`, so an
// implicit `undefined` here corrupts the store mid-render. Always
// resolve with concrete empty arrays so the re-render sees a valid
// shape even when the test never awaits the fetch.
vi.mock('@/api/firestore/habits', () => ({
  getHabits: vi.fn().mockResolvedValue([]),
  getCompletions: vi.fn().mockResolvedValue([]),
  getAllCompletions: vi.fn().mockResolvedValue([]),
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

import { HabitListScreen } from '@/features/habits/HabitListScreen';
import { useHabitStore } from '@/stores/habitStore';
import type { Habit } from '@/types/habit';

function habit(id: string, overrides: Partial<Habit> = {}): Habit {
  return {
    id,
    user_id: 'u',
    name: `Habit ${id}`,
    description: null,
    icon: '🌱',
    color: '#22c55e',
    category: null,
    frequency: 'daily',
    target_count: 1,
    active_days_json: null,
    is_active: true,
    created_at: '2026-05-01T00:00:00.000Z',
    updated_at: '2026-05-01T00:00:00.000Z',
    ...overrides,
  };
}

function renderScreen() {
  return render(
    <MemoryRouter>
      <HabitListScreen />
    </MemoryRouter>,
  );
}

describe('HabitListScreen — meta-habit filtering', () => {
  beforeEach(() => {
    useHabitStore.setState({
      habits: [],
      completions: {},
      stats: {},
      selectedHabit: null,
      isLoading: false,
      error: null,
      // Stub fetchHabits so the screen's mount `useEffect` doesn't
      // synchronously flip `isLoading=true` and gate the list behind
      // the loading spinner. The store's real `fetchHabits` calls
      // `set({ isLoading: true })` before its mocked promise resolves,
      // which would otherwise stay true through the synchronous
      // assertions in these tests.
      fetchHabits: async () => undefined,
    });
    localStorage.clear();
  });

  // Mirror of Android's `HabitListViewModel.kt:213-252` filter. Android
  // keeps these rows in Room (cloud-synced through Firestore) but never
  // renders them inside the regular habit list — they appear as
  // Self-Care / Built-In / top-level cards. Web has to apply the same
  // filter or the cloud-synced rows leak in and make the web list
  // longer than the phone's.
  const META_NAMES = [
    'Morning Self-Care',
    'Bedtime Self-Care',
    'Medication',
    'Housework',
    'School',
    'Leisure',
  ];

  it('excludes the six meta-habit names but keeps regular user habits', () => {
    const userHabit = habit('user-1', { name: 'Workout' });
    const metaHabits = META_NAMES.map((name, idx) =>
      habit(`meta-${idx}`, { name }),
    );
    useHabitStore.setState({
      habits: [...metaHabits, userHabit],
      completions: {},
    });

    renderScreen();

    expect(screen.getByText('Workout')).toBeInTheDocument();
    for (const name of META_NAMES) {
      expect(screen.queryByText(name)).not.toBeInTheDocument();
    }
  });

  it('still hides is_active=false habits (regression guard for the prior fix)', () => {
    useHabitStore.setState({
      habits: [
        habit('archived', { name: 'Archived Habit', is_active: false }),
        habit('live', { name: 'Live Habit' }),
      ],
      completions: {},
    });

    renderScreen();

    expect(screen.getByText('Live Habit')).toBeInTheDocument();
    expect(screen.queryByText('Archived Habit')).not.toBeInTheDocument();
  });

  it('renders the "No Habits Yet" empty state when only meta-habits exist', () => {
    // Mirrors Android's behaviour: a fresh signed-in user whose phone
    // seeded the six meta-habits but no user habits should see the
    // empty-state CTA, not a list of six rows.
    useHabitStore.setState({
      habits: META_NAMES.map((name, idx) => habit(`meta-${idx}`, { name })),
      completions: {},
    });

    renderScreen();

    expect(screen.getByText(/No Habits Yet/i)).toBeInTheDocument();
  });
});
