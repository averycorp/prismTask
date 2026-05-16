import { useMemo, useState } from 'react';
import { Activity, CheckCircle2, RotateCcw } from 'lucide-react';
import { toast } from 'sonner';
import { Modal } from '@/components/ui/Modal';
import { useTaskStore } from '@/stores/taskStore';
import { useHabitStore } from '@/stores/habitStore';
import { useLogicalToday } from '@/utils/useLogicalToday';
import { useSettingsStore } from '@/stores/settingsStore';

/**
 * "Completed Today" bottom sheet — web port of Android's
 * `app/src/main/java/com/averycorp/prismtask/ui/screens/today/components/DoneCounterSheet.kt`.
 *
 * Opened by tapping the "done" counter in the Today progress header
 * (e.g. "3 Of 7 Completed Today" → tap to expand). Lists today's
 * completed tasks and completed habits with a per-row Undo affordance.
 *
 * Undo routes through:
 *   - tasks → `useTaskStore.uncompleteTask` (same path the editor uses)
 *   - habits → `useHabitStore.toggleCompletion` (idempotent — toggles
 *     a completion off when one already exists for the given date)
 *
 * Sourced from the same in-memory state the rest of Today reads, so
 * undoing a row removes it from the list reactively without a refetch.
 */
interface DoneCounterSheetProps {
  isOpen: boolean;
  onClose: () => void;
}

export function DoneCounterSheet({ isOpen, onClose }: DoneCounterSheetProps) {
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const todayIso = useLogicalToday(startOfDayHour);
  const todayTasks = useTaskStore((s) => s.todayTasks);
  const uncompleteTask = useTaskStore((s) => s.uncompleteTask);
  const habits = useHabitStore((s) => s.habits);
  const completions = useHabitStore((s) => s.completions);
  const toggleCompletion = useHabitStore((s) => s.toggleCompletion);

  const [busy, setBusy] = useState<string | null>(null);

  const completedTasks = useMemo(
    () => todayTasks.filter((t) => t.status === 'done'),
    [todayTasks],
  );

  // A habit is "completed today" when its target count has been hit
  // for the user's logical-today ISO. We compute against the active
  // habit set so deactivated habits with stale completions don't bleed
  // into the sheet.
  const completedHabits = useMemo(() => {
    const rows: Array<{
      id: string;
      name: string;
      icon: string;
      color: string | null;
      count: number;
      target: number;
    }> = [];
    for (const habit of habits) {
      if (!habit.is_active) continue;
      const list = completions[habit.id] || [];
      const todayCompletion = list.find((c) => c.date === todayIso);
      const count = todayCompletion?.count ?? 0;
      const target = habit.target_count || 1;
      if (count >= target) {
        rows.push({
          id: habit.id,
          name: habit.name,
          icon: habit.icon || '🎯',
          color: habit.color ?? null,
          count,
          target,
        });
      }
    }
    return rows;
  }, [habits, completions, todayIso]);

  const total = completedTasks.length + completedHabits.length;

  const onUndoTask = async (taskId: string) => {
    setBusy(taskId);
    try {
      await uncompleteTask(taskId);
      toast.success('Task Reopened');
    } catch {
      toast.error('Failed To Reopen Task');
    } finally {
      setBusy(null);
    }
  };

  const onUndoHabit = async (habitId: string) => {
    setBusy(habitId);
    try {
      await toggleCompletion(habitId, todayIso);
      toast.success('Habit Unmarked');
    } catch {
      toast.error('Failed To Update Habit');
    } finally {
      setBusy(null);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Completed Today"
      size="md"
    >
      <div
        className="flex max-h-[60vh] flex-col"
        data-testid="done-counter-sheet"
      >
        {total === 0 && (
          <div className="flex flex-col items-center justify-center py-12 text-center">
            <CheckCircle2 className="mb-3 h-10 w-10 text-[var(--color-text-secondary)]" />
            <p className="text-sm text-[var(--color-text-secondary)]">
              Nothing Completed Yet Today. Finish A Task Or Habit To See It
              Here.
            </p>
          </div>
        )}

        {completedTasks.length > 0 && (
          <section className="mb-3">
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
              Tasks ({completedTasks.length})
            </h3>
            <ul className="space-y-1">
              {completedTasks.map((task) => (
                <li
                  key={`task_${task.id}`}
                  className="flex items-center gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2"
                >
                  <CheckCircle2
                    className="h-4 w-4 shrink-0 text-[var(--color-accent)]"
                    aria-hidden="true"
                  />
                  <span className="min-w-0 flex-1 truncate text-sm text-[var(--color-text-primary)] line-through opacity-80">
                    {task.title}
                  </span>
                  <button
                    type="button"
                    onClick={() => void onUndoTask(task.id)}
                    disabled={busy === task.id}
                    className="inline-flex shrink-0 items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-[var(--color-accent)] hover:bg-[var(--color-accent)]/10 disabled:cursor-not-allowed disabled:opacity-60"
                    aria-label={`Undo Task ${task.title}`}
                  >
                    <RotateCcw className="h-3 w-3" aria-hidden="true" />
                    Undo
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}

        {completedHabits.length > 0 && (
          <section>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
              Habits ({completedHabits.length})
            </h3>
            <ul className="space-y-1">
              {completedHabits.map((habit) => (
                <li
                  key={`habit_${habit.id}`}
                  className="flex items-center gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2"
                >
                  <span
                    className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[var(--color-bg-secondary)] text-xs"
                    style={
                      habit.color
                        ? { color: habit.color }
                        : undefined
                    }
                    aria-hidden="true"
                  >
                    {habit.icon}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-sm text-[var(--color-text-primary)]">
                    {habit.name}
                  </span>
                  {habit.target > 1 && (
                    <span className="shrink-0 rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-[10px] text-[var(--color-text-secondary)]">
                      {habit.count}/{habit.target}
                    </span>
                  )}
                  <Activity
                    className="h-3.5 w-3.5 shrink-0 text-[var(--color-accent)]"
                    aria-hidden="true"
                  />
                  <button
                    type="button"
                    onClick={() => void onUndoHabit(habit.id)}
                    disabled={busy === habit.id}
                    className="inline-flex shrink-0 items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-[var(--color-accent)] hover:bg-[var(--color-accent)]/10 disabled:cursor-not-allowed disabled:opacity-60"
                    aria-label={`Undo Habit ${habit.name}`}
                  >
                    <RotateCcw className="h-3 w-3" aria-hidden="true" />
                    Undo
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </Modal>
  );
}
