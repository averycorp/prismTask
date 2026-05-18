import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

// jsdom 29 ships a non-functional localStorage stub. Mirror the
// in-memory shim other today-section tests use so `setItem`/`getItem`
// behave like a real browser.
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

import { HabitsSection } from '@/features/today/HabitsSection';
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
    created_at: '',
    updated_at: '',
    ...overrides,
  };
}

describe('HabitsSection', () => {
  beforeEach(() => {
    useHabitStore.setState({
      habits: [],
      completions: {},
      stats: {},
      selectedHabit: null,
      isLoading: false,
      error: null,
    });
    localStorage.clear();
  });

  it('renders nothing when no active habits are present', () => {
    useHabitStore.setState({ habits: [], completions: {} });
    const { container } = render(
      <MemoryRouter>
        <HabitsSection />
      </MemoryRouter>,
    );
    expect(container.firstChild).toBeNull();
  });

  it('hides habits marked is_active=false', () => {
    useHabitStore.setState({
      habits: [habit('h1', { is_active: false })],
      completions: {},
    });
    const { container } = render(
      <MemoryRouter>
        <HabitsSection />
      </MemoryRouter>,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders rows for active habits with a done counter', () => {
    useHabitStore.setState({
      habits: [habit('h1', { name: 'Drink Water' }), habit('h2', { name: 'Walk' })],
      completions: {},
    });
    render(
      <MemoryRouter>
        <HabitsSection />
      </MemoryRouter>,
    );
    expect(screen.getByText('Drink Water')).toBeInTheDocument();
    expect(screen.getByText('Walk')).toBeInTheDocument();
    expect(screen.getByText('0/2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /toggle drink water/i })).toBeInTheDocument();
  });

  it('groups habits into Daily and Recurring sub-headers when both buckets are non-empty', () => {
    useHabitStore.setState({
      habits: [
        habit('h1', { name: 'Meditate', frequency: 'daily' }),
        habit('h2', { name: 'Long Run', frequency: 'weekly', target_count: 3 }),
        habit('h3', { name: 'Plan Budget', frequency: 'quarterly', target_count: 1 }),
      ],
      completions: {},
    });
    render(
      <MemoryRouter>
        <HabitsSection />
      </MemoryRouter>,
    );
    // "Daily" appears twice: once as the sub-header, once as the
    // frequency label on the Meditate row (target=1, daily → "Daily").
    // "Recurring" only appears as the sub-header — non-daily rows say
    // "this week" / "this quarter" instead.
    expect(screen.getAllByText('Daily')).toHaveLength(2);
    expect(screen.getByText('Recurring')).toBeInTheDocument();
    expect(screen.getByText('0/3 this week')).toBeInTheDocument();
    expect(screen.getByText('0/1 this quarter')).toBeInTheDocument();
  });

  it('omits sub-headers when only one bucket has habits', () => {
    useHabitStore.setState({
      habits: [
        habit('h1', { name: 'Meditate', frequency: 'daily' }),
        habit('h2', { name: 'Drink Water', frequency: 'daily' }),
      ],
      completions: {},
    });
    render(
      <MemoryRouter>
        <HabitsSection />
      </MemoryRouter>,
    );
    // "Daily" appears as the frequency label on each row (target=1),
    // but not as a section sub-header. Easier to assert on "Recurring"
    // — it must not appear at all when no recurring habits exist.
    expect(screen.queryByText('Recurring')).not.toBeInTheDocument();
  });
});
