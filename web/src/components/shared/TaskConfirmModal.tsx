import { useEffect, useId, useRef, useState } from 'react';
import { X, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { MAX_TASK_TITLE_LENGTH, clampTitle } from '@/utils/taskTitle';

/**
 * Editable task-confirm payload — what we hand back via [onSave] when the
 * user accepts. `tags` are kebab-stripped of leading `#` so the caller can
 * forward them straight to the task API.
 */
export interface TaskConfirmDraft {
  title: string;
  due_date: string | null;
  due_time: string | null;
  priority: number | null;
  project_suggestion: string | null;
  tags: string[];
  recurrence_hint: string | null;
}

interface TaskConfirmModalProps {
  initial: TaskConfirmDraft;
  onSave: (draft: TaskConfirmDraft) => void;
  onCancel: () => void;
}

const PRIORITY_LABELS: Record<number, string> = {
  0: 'None',
  1: 'Low',
  2: 'Medium',
  3: 'High',
  4: 'Urgent',
};

/**
 * The web mirror of Android's `TaskConfirmSheet`. Surfaces every parsed
 * field (title, date, time, priority, project, tags, recurrence) as
 * editable controls before the actual insert. Mirrors NLPInput's existing
 * inline confirm popover, but factored out so QuickCreateInput and other
 * single-field surfaces can reuse it.
 */
export function TaskConfirmModal({ initial, onSave, onCancel }: TaskConfirmModalProps) {
  const [title, setTitle] = useState(initial.title);
  const [dueDate, setDueDate] = useState(initial.due_date ?? '');
  const [dueTime, setDueTime] = useState(initial.due_time ?? '');
  const [priority, setPriority] = useState<number>(initial.priority ?? 0);
  const [projectName, setProjectName] = useState(initial.project_suggestion ?? '');
  const [tagsText, setTagsText] = useState(initial.tags.join(', '));
  const recurrence = initial.recurrence_hint;

  const titleRef = useRef<HTMLInputElement>(null);
  const idBase = useId();
  const titleId = `${idBase}-title`;
  const dateId = `${idBase}-date`;
  const timeId = `${idBase}-time`;
  const priorityId = `${idBase}-priority`;
  const projectId = `${idBase}-project`;
  const tagsId = `${idBase}-tags`;

  useEffect(() => {
    titleRef.current?.focus();
    titleRef.current?.select();
  }, []);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onCancel]);

  const commit = () => {
    const cleanedTags = tagsText
      .split(',')
      .map((t) => t.trim().replace(/^#/, ''))
      .filter((t) => t.length > 0);
    onSave({
      title: clampTitle(title.trim() || initial.title),
      due_date: dueDate || null,
      due_time: dueTime || null,
      priority: priority > 0 ? priority : null,
      project_suggestion: projectName.trim() || null,
      tags: cleanedTags,
      recurrence_hint: recurrence,
    });
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-label="Confirm task details"
      onClick={onCancel}
    >
      <div
        className="w-full max-w-md rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-5 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Sparkles className="h-4 w-4 text-[var(--color-accent)]" aria-hidden="true" />
            <h4 className="text-base font-semibold text-[var(--color-text-primary)]">
              Confirm Task
            </h4>
          </div>
          <button
            onClick={onCancel}
            className="text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
            aria-label="Close"
          >
            <X className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>

        <div className="flex flex-col gap-3">
          <div>
            <div className="mb-1 flex items-center justify-between">
              <label
                htmlFor={titleId}
                className="block text-xs font-medium text-[var(--color-text-secondary)]"
              >
                Title
              </label>
              <span
                className={`text-xs tabular-nums ${
                  title.length >= MAX_TASK_TITLE_LENGTH
                    ? 'font-medium text-[var(--color-danger,#dc2626)]'
                    : 'text-[var(--color-text-secondary)]'
                }`}
                aria-live="polite"
              >
                {title.length}/{MAX_TASK_TITLE_LENGTH}
              </span>
            </div>
            <input
              ref={titleRef}
              id={titleId}
              type="text"
              maxLength={MAX_TASK_TITLE_LENGTH}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  commit();
                }
              }}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label
                htmlFor={dateId}
                className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
              >
                Due Date
              </label>
              <input
                id={dateId}
                type="date"
                value={dueDate}
                onChange={(e) => setDueDate(e.target.value)}
                className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
              />
            </div>
            <div>
              <label
                htmlFor={timeId}
                className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
              >
                Due Time
              </label>
              <input
                id={timeId}
                type="time"
                value={dueTime}
                onChange={(e) => setDueTime(e.target.value)}
                className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
              />
            </div>
          </div>

          <div>
            <label
              htmlFor={priorityId}
              className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
            >
              Priority
            </label>
            <select
              id={priorityId}
              value={priority}
              onChange={(e) => setPriority(Number(e.target.value))}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            >
              {[0, 1, 2, 3, 4].map((p) => (
                <option key={p} value={p}>
                  {PRIORITY_LABELS[p]}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label
              htmlFor={projectId}
              className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
            >
              Project (Optional)
            </label>
            <input
              id={projectId}
              type="text"
              value={projectName}
              onChange={(e) => setProjectName(e.target.value)}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            />
          </div>

          <div>
            <label
              htmlFor={tagsId}
              className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
            >
              Tags (Comma-Separated)
            </label>
            <input
              id={tagsId}
              type="text"
              value={tagsText}
              onChange={(e) => setTagsText(e.target.value)}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            />
          </div>

          {recurrence && (
            <div className="text-xs text-[var(--color-text-secondary)]">
              Repeats: <span className="text-[var(--color-text-primary)]">{recurrence}</span>
            </div>
          )}
        </div>

        <div className="mt-5 flex justify-end gap-2">
          <Button variant="ghost" size="sm" onClick={onCancel}>
            Cancel
          </Button>
          <Button size="sm" onClick={commit} disabled={!title.trim()}>
            Save Task
          </Button>
        </div>
      </div>
    </div>
  );
}
