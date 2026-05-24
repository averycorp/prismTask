import { Repeat } from 'lucide-react';
import type { Habit } from '@/types/habit';

/**
 * Compact, read-only habit markers rendered inside a calendar day cell so
 * recurring daily habits are visible alongside dated tasks (bug B-05).
 */
export function CalendarHabitChips({ habits }: { habits: Habit[] }) {
  if (habits.length === 0) return null;
  return (
    <ul className="flex flex-col gap-1" aria-label="Habits">
      {habits.map((habit) => (
        <li
          key={habit.id}
          className="flex items-center gap-1.5 rounded-md border border-dashed border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-1.5 py-1 text-[11px] text-[var(--color-text-secondary)]"
          title={`Habit: ${habit.name}`}
        >
          <Repeat
            className="h-3 w-3 shrink-0"
            style={habit.color ? { color: habit.color } : undefined}
            aria-hidden="true"
          />
          <span className="truncate">{habit.name}</span>
        </li>
      ))}
    </ul>
  );
}
