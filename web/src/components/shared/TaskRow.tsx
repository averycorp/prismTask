import { useState, useCallback, memo } from 'react';
import { Link2 } from 'lucide-react';
import { Checkbox } from '@/components/ui/Checkbox';
import { PriorityBadge } from './PriorityBadge';
import { DueDateLabel } from './DueDateLabel';
import { Badge } from '@/components/ui/Badge';
import { QuickReschedule } from './QuickReschedule';
import { getPriorityColor } from '@/utils/priority';
import type { Task } from '@/types/task';

interface TaskRowProps {
  task: Task;
  selected?: boolean;
  onToggleSelect?: () => void;
  onComplete: (taskId: string) => void;
  onUncomplete?: (taskId: string) => void;
  onClick: (task: Task) => void;
  onReschedule?: (taskId: string, date: string) => void;
  showProject?: boolean;
  projectName?: string;
  projectColor?: string;
  showSelection?: boolean;
  /**
   * Number of unmet (not-done) blockers gating this task. When `> 0`
   * the row mutes visually (~40% opacity, matching Android's
   * `BlockedTaskCard` convention) and shows a "Blocked by N" chip that
   * opens the dependency editor on click. `0` / undefined → no chip,
   * full opacity.
   */
  blockedByCount?: number;
  /**
   * Click handler for the blocker chip. When provided, the chip becomes
   * a button; clicking forwards to this callback (typically opens the
   * task editor scrolled to the Organize tab). When omitted, the chip
   * is decorative.
   */
  onBlockerChipClick?: (task: Task) => void;
  className?: string;
}

export const TaskRow = memo(function TaskRow({
  task,
  selected = false,
  onToggleSelect,
  onComplete,
  onUncomplete,
  onClick,
  onReschedule,
  showProject = false,
  projectName,
  projectColor,
  showSelection = false,
  blockedByCount = 0,
  onBlockerChipClick,
  className = '',
}: TaskRowProps) {
  const [rescheduleOpen, setRescheduleOpen] = useState(false);
  const [rescheduleAnchor, setRescheduleAnchor] = useState<{
    x: number;
    y: number;
  }>();

  const isDone = task.status === 'done';
  const priorityColor = getPriorityColor(task.priority);

  const handleCheckboxChange = useCallback(
    (checked: boolean) => {
      if (checked) {
        onComplete(task.id);
      } else if (onUncomplete) {
        onUncomplete(task.id);
      }
    },
    [task.id, onComplete, onUncomplete],
  );

  const handleContextMenu = useCallback(
    (e: React.MouseEvent) => {
      if (!onReschedule) return;
      e.preventDefault();
      setRescheduleAnchor({ x: e.clientX, y: e.clientY });
      setRescheduleOpen(true);
    },
    [onReschedule],
  );

  const subtaskCount = task.subtasks?.length ?? 0;
  const subtaskDone =
    task.subtasks?.filter((s) => s.status === 'done').length ?? 0;

  // Mute the row when blocked by unmet dependencies — mirrors Android's
  // `BlockedTaskCard` 40% opacity convention so the visual cue is
  // identical across platforms.
  const isBlocked = blockedByCount > 0 && !isDone;

  return (
    <div
      className={`group relative flex items-center gap-3 rounded-lg border border-transparent px-3 py-2.5 transition-colors hover:bg-[var(--color-bg-secondary)] ${
        selected ? 'bg-[var(--color-accent)]/5 border-[var(--color-accent)]/20' : ''
      } ${isBlocked ? 'opacity-40' : ''} ${className}`}
      onContextMenu={handleContextMenu}
    >
      {/* Selection checkbox */}
      {showSelection && onToggleSelect && (
        <Checkbox
          checked={selected}
          onChange={onToggleSelect}
          className="shrink-0"
        />
      )}

      {/* Priority-colored completion checkbox */}
      <Checkbox
        checked={isDone}
        onChange={handleCheckboxChange}
        priorityColor={priorityColor}
        className="shrink-0"
      />

      {/* Task content - clickable area */}
      <button
        type="button"
        className="flex min-w-0 flex-1 items-center gap-2 text-left"
        onClick={() => onClick(task)}
      >
        <span
          className={`flex-1 truncate text-sm font-medium transition-all ${
            isDone
              ? 'text-[var(--color-text-secondary)] line-through opacity-60'
              : 'text-[var(--color-text-primary)]'
          }`}
        >
          {task.title}
        </span>

        {/* Inline badges */}
        <span className="flex shrink-0 items-center gap-2">
          {/* Blocked-by chip — opens dependency editor on click */}
          {isBlocked && (
            <span
              role={onBlockerChipClick ? 'button' : undefined}
              tabIndex={onBlockerChipClick ? 0 : undefined}
              onClick={
                onBlockerChipClick
                  ? (e) => {
                      e.stopPropagation();
                      onBlockerChipClick(task);
                    }
                  : undefined
              }
              onKeyDown={
                onBlockerChipClick
                  ? (e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        e.stopPropagation();
                        onBlockerChipClick(task);
                      }
                    }
                  : undefined
              }
              className="inline-flex items-center gap-1 rounded-full border border-amber-500/40 bg-amber-500/10 px-2 py-0.5 text-[10px] font-medium text-amber-600 dark:text-amber-400"
              aria-label={`Blocked by ${blockedByCount} task${blockedByCount === 1 ? '' : 's'}`}
            >
              <Link2 className="h-3 w-3" aria-hidden="true" />
              Blocked By {blockedByCount}
            </span>
          )}

          {/* Subtask count */}
          {subtaskCount > 0 && (
            <Badge
              variant="outline"
              color={
                subtaskDone === subtaskCount
                  ? '#22c55e'
                  : 'var(--color-text-secondary)'
              }
            >
              {subtaskDone}/{subtaskCount}
            </Badge>
          )}

          {/* Project pill */}
          {showProject && projectName && (
            <span className="inline-flex items-center gap-1.5 rounded-full border border-[var(--color-border)] px-2 py-0.5 text-xs text-[var(--color-text-secondary)]">
              <span
                className="h-2 w-2 rounded-full"
                style={{
                  backgroundColor: projectColor || 'var(--color-accent)',
                }}
              />
              {projectName}
            </span>
          )}

          {/* Priority indicator */}
          {!isDone && <PriorityBadge priority={task.priority} iconOnly />}

          {/* Due date */}
          <DueDateLabel date={task.due_date} />
        </span>
      </button>

      {/* Quick reschedule popup */}
      {rescheduleOpen && onReschedule && (
        <QuickReschedule
          isOpen={rescheduleOpen}
          onClose={() => setRescheduleOpen(false)}
          onSelect={(date) => onReschedule(task.id, date)}
          anchorPoint={rescheduleAnchor}
        />
      )}
    </div>
  );
});
