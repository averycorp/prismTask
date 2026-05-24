import { useCallback } from 'react';
import { useHabitStore } from '@/stores/habitStore';
import type { Habit } from '@/types/habit';

/**
 * Pure resolver: which of `habits` are scheduled on `date`.
 *
 * Only *daily* habits map cleanly onto a calendar cell: they recur every
 * day, optionally restricted to specific weekdays via `active_days_json`
 * (a JSON array of ISO weekdays, 1=Mon … 7=Sun — same convention as
 * `TodayScreen`). Period-target habits (weekly/monthly/etc.) are counted
 * over a window rather than tied to a specific date, so they are not
 * surfaced on individual calendar days here.
 */
export function habitsForDate(habits: Habit[], date: Date): Habit[] {
  const jsDay = date.getDay();
  const isoDay = jsDay === 0 ? 7 : jsDay; // 1=Mon … 7=Sun
  return habits.filter((habit) => {
    if (!habit.is_active) return false;
    if (habit.frequency !== 'daily') return false;
    if (!habit.active_days_json) return true;
    try {
      const activeDays = JSON.parse(habit.active_days_json) as number[];
      return (
        !Array.isArray(activeDays) ||
        activeDays.length === 0 ||
        activeDays.includes(isoDay)
      );
    } catch {
      return true;
    }
  });
}

/**
 * Surfaces daily habits onto calendar days. Habits are read from the
 * Firestore-backed habit store — the source of truth for the web client
 * (bug B-05).
 */
export function useCalendarHabits() {
  const habits = useHabitStore((s) => s.habits);
  const fetchHabits = useHabitStore((s) => s.fetchHabits);

  const getHabitsForDate = useCallback(
    (date: Date): Habit[] => habitsForDate(habits, date),
    [habits],
  );

  return { habits, fetchHabits, getHabitsForDate };
}
