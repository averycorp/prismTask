import { describe, it, expect } from 'vitest';
import { habitsForDate } from '../useCalendarHabits';
import type { Habit } from '@/types/habit';

function makeHabit(overrides: Partial<Habit>): Habit {
  return {
    id: overrides.id ?? 'h1',
    user_id: 'u1',
    name: overrides.name ?? 'Drink water',
    description: null,
    icon: null,
    color: null,
    category: null,
    frequency: overrides.frequency ?? 'daily',
    target_count: 1,
    active_days_json: overrides.active_days_json ?? null,
    is_active: overrides.is_active ?? true,
    created_at: '2026-05-15T00:00:00.000Z',
    updated_at: '2026-05-15T00:00:00.000Z',
    ...overrides,
  } as Habit;
}

// 2026-05-24 is a Sunday (ISO weekday 7); 2026-05-25 is a Monday (ISO 1).
const SUNDAY = new Date(2026, 4, 24);
const MONDAY = new Date(2026, 4, 25);

describe('habitsForDate (B-05 habits on calendar)', () => {
  it('includes an unrestricted daily habit on any day', () => {
    const habits = [makeHabit({ id: 'd' })];
    expect(habitsForDate(habits, SUNDAY).map((h) => h.id)).toEqual(['d']);
    expect(habitsForDate(habits, MONDAY).map((h) => h.id)).toEqual(['d']);
  });

  it('excludes inactive habits', () => {
    const habits = [makeHabit({ id: 'd', is_active: false })];
    expect(habitsForDate(habits, MONDAY)).toEqual([]);
  });

  it('excludes non-daily (period-target) habits', () => {
    const habits = [makeHabit({ id: 'w', frequency: 'weekly' })];
    expect(habitsForDate(habits, MONDAY)).toEqual([]);
  });

  it('respects active_days_json weekday restriction (ISO 1=Mon … 7=Sun)', () => {
    // Weekdays only: Mon–Fri.
    const habits = [
      makeHabit({ id: 'wk', active_days_json: JSON.stringify([1, 2, 3, 4, 5]) }),
    ];
    expect(habitsForDate(habits, MONDAY).map((h) => h.id)).toEqual(['wk']);
    expect(habitsForDate(habits, SUNDAY)).toEqual([]);
  });

  it('falls back to "every day" when active_days_json is malformed', () => {
    const habits = [makeHabit({ id: 'bad', active_days_json: 'not-json' })];
    expect(habitsForDate(habits, MONDAY).map((h) => h.id)).toEqual(['bad']);
  });
});
