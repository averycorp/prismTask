import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Activity, CheckCircle2, Circle, ChevronDown, ChevronRight, Flame } from 'lucide-react';
import { toast } from 'sonner';
import { useHabitStore } from '@/stores/habitStore';
import { useLogicalToday } from '@/utils/useLogicalToday';
import { useSettingsStore } from '@/stores/settingsStore';
import type { Habit, HabitCompletion, HabitFrequency } from '@/types/habit';
import { isRecurringFrequency } from '@/types/habit';

/**
 * Today-screen Habits section — row-style checkable habits. Web port of
 * `TodayHabitCheckItem.kt` + the parent list in `Today.kt` (parity unit
 * 7 of 23).
 *
 * Differs from the legacy chip-style row inside `TodayScreen.tsx` in
 * two ways: rows are task-shaped (full-width, checkbox + name + streak
 * badge) instead of pill chips, and the section is collapsible to match
 * the rest of the Today screen's `TaskSection` chrome.
 *
 * Habits are grouped into "Daily" (frequency=daily) and "Recurring"
 * (weekly / fortnightly / monthly / bimonthly / quarterly) sub-buckets,
 * matching the segmented filter on Android's `HabitListScreen`. The
 * sub-headers are only rendered when both buckets are non-empty —
 * otherwise the section collapses back to a single flat list.
 *
 * Tapping the checkbox toggles `habit_completions` for the logical day
 * (Start-of-Day-aware). The streak badge surfaces when the habit's
 * `showStreak` flag is on and the current streak is positive.
 */
const COLLAPSE_KEY = 'prismtask_today_habits_section_collapsed';

function loadCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSE_KEY) === '1';
  } catch {
    return false;
  }
}

function saveCollapsed(collapsed: boolean) {
  try {
    localStorage.setItem(COLLAPSE_KEY, collapsed ? '1' : '0');
  } catch {
    // localStorage may be disabled — fall back to in-memory only.
  }
}

function completionsThisWeek(
  list: readonly HabitCompletion[] | undefined,
  todayIso: string,
): number {
  if (!list || list.length === 0) return 0;
  const todayDate = new Date(todayIso + 'T12:00:00');
  const startOfWeek = new Date(todayDate);
  startOfWeek.setDate(todayDate.getDate() - ((todayDate.getDay() + 6) % 7));
  startOfWeek.setHours(0, 0, 0, 0);
  return list.reduce((sum, c) => {
    const d = new Date(c.date + 'T00:00:00');
    return d >= startOfWeek && d <= todayDate ? sum + c.count : sum;
  }, 0);
}

function frequencyLabel(
  frequency: HabitFrequency,
  target: number,
  todayCount: number,
  weekCount: number,
): string {
  switch (frequency) {
    case 'daily':
      return target > 1 ? `${todayCount}/${target} today` : 'Daily';
    case 'weekly':
      return `${weekCount}/${target} this week`;
    case 'fortnightly':
      return `${weekCount}/${target} this fortnight`;
    case 'monthly':
      return `${weekCount}/${target} this month`;
    case 'bimonthly':
      return `${weekCount}/${target} this period`;
    case 'quarterly':
      return `${weekCount}/${target} this quarter`;
  }
}

export function HabitsSection() {
  const navigate = useNavigate();
  const habits = useHabitStore((s) => s.habits);
  const completions = useHabitStore((s) => s.completions);
  const toggleCompletion = useHabitStore((s) => s.toggleCompletion);
  const isTodayCompleted = useHabitStore((s) => s.isTodayCompleted);
  const getTodayCount = useHabitStore((s) => s.getTodayCount);
  const getStreakData = useHabitStore((s) => s.getStreakData);
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const todayIso = useLogicalToday(startOfDayHour);

  const [collapsed, setCollapsed] = useState<boolean>(loadCollapsed);

  const activeHabits = habits.filter((h) => h.is_active);
  if (activeHabits.length === 0) return null;

  const dailyHabits = activeHabits.filter((h) => h.frequency === 'daily');
  const recurringHabits = activeHabits.filter((h) => isRecurringFrequency(h.frequency));
  const showSubHeaders = dailyHabits.length > 0 && recurringHabits.length > 0;

  const doneCount = activeHabits.filter((h) => isTodayCompleted(h.id)).length;

  const onToggle = () => {
    setCollapsed((prev) => {
      const next = !prev;
      saveCollapsed(next);
      return next;
    });
  };

  const renderRow = (habit: Habit) => (
    <HabitRow
      key={habit.id}
      habit={habit}
      completed={isTodayCompleted(habit.id)}
      currentCount={getTodayCount(habit.id)}
      weekCount={completionsThisWeek(completions[habit.id], todayIso)}
      streak={getStreakData(habit.id)?.currentStreak ?? 0}
      onToggle={async () => {
        try {
          await toggleCompletion(habit.id, todayIso);
        } catch {
          toast.error('Failed to update habit');
        }
      }}
      onClick={() => navigate(`/habits/${habit.id}/logs`)}
    />
  );

  return (
    <div className="mb-4 overflow-hidden rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)]">
      {/* Header is a row, not a single button — nesting a "View All"
          button inside the collapse button violates HTML and triggers a
          React hydration warning. The chevron + label area handles
          expand/collapse via its own button; the "View All" affordance
          sits as a sibling. */}
      <div className="flex w-full items-center gap-2 px-4 py-3">
        <button
          type="button"
          onClick={onToggle}
          className="flex flex-1 items-center gap-2 text-left transition-colors hover:opacity-80"
          aria-expanded={!collapsed}
          aria-controls="today-habits-rows"
        >
          {collapsed ? (
            <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" aria-hidden="true" />
          ) : (
            <ChevronDown className="h-4 w-4 text-[var(--color-text-secondary)]" aria-hidden="true" />
          )}
          <Activity className="h-4 w-4 text-[var(--color-accent)]" aria-hidden="true" />
          <span className="text-sm font-semibold text-[var(--color-text-primary)]">
            Habits
          </span>
          <span className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-xs font-medium text-[var(--color-text-secondary)]">
            {doneCount}/{activeHabits.length}
          </span>
        </button>
        <button
          type="button"
          onClick={() => navigate('/habits')}
          className="text-xs text-[var(--color-accent)] hover:underline"
        >
          View All
        </button>
      </div>
      {!collapsed && (
        <ul id="today-habits-rows" className="px-1 pb-2">
          {showSubHeaders && dailyHabits.length > 0 && (
            <li className="px-3 pt-2 pb-1 text-[11px] font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
              Daily
            </li>
          )}
          {dailyHabits.map(renderRow)}
          {showSubHeaders && recurringHabits.length > 0 && (
            <li className="px-3 pt-3 pb-1 text-[11px] font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
              Recurring
            </li>
          )}
          {recurringHabits.map(renderRow)}
        </ul>
      )}
    </div>
  );
}

interface HabitRowProps {
  habit: Habit;
  completed: boolean;
  currentCount: number;
  weekCount: number;
  streak: number;
  onToggle: () => void;
  onClick: () => void;
}

function HabitRow({
  habit,
  completed,
  currentCount,
  weekCount,
  streak,
  onToggle,
  onClick,
}: HabitRowProps) {
  const habitColor = habit.color || 'var(--color-accent)';
  // Web's Habit type has no `show_streak` flag yet — surface the badge
  // whenever the streak is positive. Android gates this on
  // `HabitEntity.showStreak`; until that lands on web, the >0 check
  // matches the user-visible default (streak hidden when 0).
  const showStreak = streak > 0;
  const label = frequencyLabel(habit.frequency, habit.target_count, currentCount, weekCount);
  return (
    <li className="flex items-center gap-3 rounded-md px-3 py-2 hover:bg-[var(--color-bg-secondary)]">
      <button
        type="button"
        onClick={onToggle}
        aria-pressed={completed}
        aria-label={`Toggle ${habit.name} complete`}
        className="shrink-0"
      >
        {completed ? (
          <CheckCircle2
            className="h-5 w-5"
            style={{ color: habitColor }}
            aria-hidden="true"
          />
        ) : (
          <Circle
            className="h-5 w-5 text-[var(--color-text-secondary)]"
            aria-hidden="true"
          />
        )}
      </button>
      <button
        type="button"
        onClick={onClick}
        className="flex flex-1 items-center gap-2 text-left"
        aria-label={`Open ${habit.name} logs`}
      >
        {habit.icon && <span className="text-base">{habit.icon}</span>}
        <span
          className={
            completed
              ? 'truncate text-sm font-medium text-[var(--color-text-secondary)] line-through'
              : 'truncate text-sm font-medium text-[var(--color-text-primary)]'
          }
        >
          {habit.name}
        </span>
        <span className="ml-auto shrink-0 rounded-full bg-[var(--color-bg-secondary)] px-1.5 py-0.5 text-[10px] text-[var(--color-text-secondary)]">
          {label}
        </span>
      </button>
      {showStreak && (
        <span className="flex shrink-0 items-center gap-1 text-xs font-medium text-amber-500">
          <Flame className="h-3.5 w-3.5" aria-hidden="true" />
          {streak}
        </span>
      )}
    </li>
  );
}

export default HabitsSection;
