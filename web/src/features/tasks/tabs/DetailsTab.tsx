import { Plus, GripVertical, X } from 'lucide-react';
import { Checkbox } from '@/components/ui/Checkbox';
import { PRIORITY_CONFIG } from '@/utils/priority';
import type { Task, TaskPriority, TaskStatus } from '@/types/task';

/**
 * Details tab content for the task editor — mirrors Android
 * `addedittask/tabs/DetailsTab.kt`. Pure presentational shell:
 * Title, Description, Priority pills, Status dropdown, Subtasks.
 *
 * Auto-saves are owned by the parent TaskEditor (debounced); each
 * change handler here is fired directly on user input.
 */
const STATUS_OPTIONS: { value: TaskStatus; label: string }[] = [
  { value: 'todo', label: 'To Do' },
  { value: 'in_progress', label: 'In Progress' },
  { value: 'done', label: 'Done' },
  { value: 'cancelled', label: 'Cancelled' },
];

export interface DetailsTabProps {
  isCreate: boolean;
  title: string;
  onTitleChange: (v: string) => void;
  description: string;
  onDescriptionChange: (v: string) => void;
  priority: TaskPriority;
  onPriorityChange: (p: TaskPriority) => void;
  status: TaskStatus;
  onStatusChange: (s: TaskStatus) => void;
  subtasks: Task[];
  newSubtaskTitle: string;
  onNewSubtaskTitleChange: (v: string) => void;
  onAddSubtask: () => void;
  onToggleSubtask: (subtask: Task) => void;
  onDeleteSubtask: (id: string) => void;
}

export function DetailsTab({
  isCreate,
  title,
  onTitleChange,
  description,
  onDescriptionChange,
  priority,
  onPriorityChange,
  status,
  onStatusChange,
  subtasks,
  newSubtaskTitle,
  onNewSubtaskTitleChange,
  onAddSubtask,
  onToggleSubtask,
  onDeleteSubtask,
}: DetailsTabProps) {
  const subtasksDone = subtasks.filter((s) => s.status === 'done').length;
  const subtaskProgress =
    subtasks.length > 0
      ? Math.round((subtasksDone / subtasks.length) * 100)
      : 0;

  return (
    <div className="flex flex-col gap-4" data-testid="task-editor-details-tab">
      {/* Title */}
      <div>
        <label
          htmlFor="task-title-input"
          className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
        >
          Title
        </label>
        <input
          id="task-title-input"
          type="text"
          value={title}
          onChange={(e) => onTitleChange(e.target.value)}
          placeholder="Task Title..."
          className="w-full border-none bg-transparent text-lg font-semibold text-[var(--color-text-primary)] outline-none placeholder-[var(--color-text-secondary)]"
          autoFocus={isCreate}
        />
      </div>

      {/* Description */}
      <div>
        <label
          htmlFor="task-description-input"
          className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
        >
          Description
        </label>
        <textarea
          id="task-description-input"
          value={description}
          onChange={(e) => onDescriptionChange(e.target.value)}
          placeholder="Add Description..."
          rows={3}
          className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        />
      </div>

      {/* Priority */}
      <div>
        <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
          Priority
        </label>
        <div className="flex gap-2" role="group" aria-label="Priority">
          {([1, 2, 3, 4] as TaskPriority[]).map((p) => (
            <button
              key={p}
              type="button"
              onClick={() => onPriorityChange(p)}
              aria-pressed={priority === p}
              className={`flex-1 rounded-lg border px-3 py-2 text-xs font-medium transition-colors ${
                priority === p
                  ? 'border-current'
                  : 'border-[var(--color-border)]'
              }`}
              style={{
                color:
                  priority === p
                    ? PRIORITY_CONFIG[p].color
                    : 'var(--color-text-secondary)',
                backgroundColor:
                  priority === p
                    ? PRIORITY_CONFIG[p].bgColor
                    : 'transparent',
              }}
            >
              {PRIORITY_CONFIG[p].label}
            </button>
          ))}
        </div>
      </div>

      {/* Status */}
      <div>
        <label
          htmlFor="task-status-select"
          className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
        >
          Status
        </label>
        <select
          id="task-status-select"
          value={status}
          onChange={(e) => onStatusChange(e.target.value as TaskStatus)}
          className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        >
          {STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </div>

      {/* Subtasks — edit mode only since subtasks need a parent task ID */}
      {!isCreate && (
        <div>
          <div className="mb-2 flex items-center justify-between">
            <label className="text-xs font-medium text-[var(--color-text-secondary)]">
              Subtasks
            </label>
            {subtasks.length > 0 && (
              <span className="text-xs text-[var(--color-text-secondary)]">
                {subtasksDone}/{subtasks.length}
              </span>
            )}
          </div>

          {subtasks.length > 0 && (
            <div className="mb-2 h-1.5 w-full overflow-hidden rounded-full bg-[var(--color-bg-secondary)]">
              <div
                className="h-full rounded-full bg-[var(--color-accent)] transition-all duration-300"
                style={{ width: `${subtaskProgress}%` }}
              />
            </div>
          )}

          <div className="flex flex-col gap-1">
            {subtasks.map((subtask) => (
              <div
                key={subtask.id}
                className="group flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-[var(--color-bg-secondary)]"
              >
                <GripVertical className="h-3 w-3 shrink-0 cursor-grab text-[var(--color-text-secondary)] opacity-0 group-hover:opacity-100" />
                <Checkbox
                  checked={subtask.status === 'done'}
                  onChange={() => onToggleSubtask(subtask)}
                />
                <span
                  className={`flex-1 text-sm ${
                    subtask.status === 'done'
                      ? 'text-[var(--color-text-secondary)] line-through'
                      : 'text-[var(--color-text-primary)]'
                  }`}
                >
                  {subtask.title}
                </span>
                <button
                  type="button"
                  onClick={() => onDeleteSubtask(subtask.id)}
                  aria-label={`Delete subtask "${subtask.title}"`}
                  className="shrink-0 text-[var(--color-text-secondary)] opacity-0 hover:text-red-500 group-hover:opacity-100 transition-colors"
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            ))}
          </div>

          <div className="mt-1 flex items-center gap-2">
            <Plus className="h-4 w-4 shrink-0 text-[var(--color-text-secondary)]" />
            <input
              type="text"
              value={newSubtaskTitle}
              onChange={(e) => onNewSubtaskTitleChange(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') onAddSubtask();
              }}
              placeholder="Add Subtask..."
              aria-label="Add Subtask"
              className="flex-1 border-none bg-transparent py-1 text-sm text-[var(--color-text-primary)] outline-none placeholder-[var(--color-text-secondary)]"
            />
          </div>
        </div>
      )}
    </div>
  );
}
