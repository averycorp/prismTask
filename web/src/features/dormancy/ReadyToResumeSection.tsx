import { RotateCcw } from 'lucide-react';
import type { DormantTask } from './dormancy';

/**
 * Dormancy Re-Entry: read-only "Ready to Resume" section for the web task list.
 *
 * Web parity is display-only by design (the sync layer is thinner than Android)
 * — there is no 5-minute session execution here. Clicking a row opens the task
 * so the user can pick it back up; the 5-minute Resume Tiny session lives on
 * Android. Forgiveness-first copy (PHILOSOPHY.md Principle 1): invitational, no
 * shaming "days since you failed" framing.
 */
interface Props {
  items: DormantTask[];
  onOpen: (taskId: string) => void;
}

export function ReadyToResumeSection({ items, onOpen }: Props) {
  if (items.length === 0) return null;
  return (
    <div className="mb-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-4">
      <div className="mb-2 flex items-center gap-2">
        <RotateCcw className="h-4 w-4 text-[var(--color-accent)]" />
        <h2 className="text-sm font-semibold text-[var(--color-text-primary)]">
          Ready to Resume
        </h2>
      </div>
      <ul className="flex flex-col gap-2">
        {items.map(({ task, daysDormant }) => (
          <li key={task.id}>
            <button
              type="button"
              onClick={() => onOpen(task.id)}
              className="flex w-full items-center justify-between rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-primary)] px-3 py-2 text-left hover:border-[var(--color-accent)]"
            >
              <div className="min-w-0">
                <div className="truncate text-sm font-medium text-[var(--color-text-primary)]">
                  {task.title}
                </div>
                {task.re_entry_context ? (
                  <div className="truncate text-xs italic text-[var(--color-text-secondary)]">
                    {task.re_entry_context}
                  </div>
                ) : null}
              </div>
              <span className="ml-3 shrink-0 text-xs text-[var(--color-text-secondary)]">
                {daysDormant} {daysDormant === 1 ? 'day' : 'days'} dormant
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
