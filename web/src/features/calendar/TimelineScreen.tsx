import { useState, useEffect, useMemo, useRef } from 'react';
import { format, isToday } from 'date-fns';
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  type DragStartEvent,
  type DragEndEvent,
} from '@dnd-kit/core';
import { useDroppable } from '@dnd-kit/core';
import { useDraggable } from '@dnd-kit/core';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { toast } from 'sonner';
import { CalendarNav } from './CalendarNav';
import { QuickCreateInput } from './QuickCreateInput';
import { useDateNavigation } from '@/hooks/useDateNavigation';
import { useCalendarTasks } from '@/hooks/useCalendarTasks';
import { useTaskStore } from '@/stores/taskStore';
import { Button } from '@/components/ui/Button';
import { Tooltip } from '@/components/ui/Tooltip';
import { PRIORITY_CONFIG } from '@/utils/priority';
import { formatTime } from '@/utils/dates';
import type { Task } from '@/types/task';
import { lazy, Suspense } from 'react';

const TaskEditor = lazy(() => import('@/features/tasks/TaskEditor'));

const HOUR_HEIGHT = 60; // px per hour
const START_HOUR = 6; // 6 AM
const END_HOUR = 24; // midnight
const TOTAL_HOURS = END_HOUR - START_HOUR;

function timeToMinutes(time: string): number {
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
}

function minutesToTime(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

function timeToOffset(time: string): number {
  const minutes = timeToMinutes(time);
  const startMinutes = START_HOUR * 60;
  return ((minutes - startMinutes) / 60) * HOUR_HEIGHT;
}

// Compute overlap columns for overlapping tasks
function computeColumns(tasks: Task[]): Map<string, { col: number; totalCols: number }> {
  const result = new Map<string, { col: number; totalCols: number }>();
  if (tasks.length === 0) return result;

  // Sort by start time
  const sorted = [...tasks].sort((a, b) => {
    const aMin = timeToMinutes(a.due_time!);
    const bMin = timeToMinutes(b.due_time!);
    return aMin - bMin;
  });

  // Track active columns
  const columns: { taskId: string; endMinute: number }[] = [];

  for (const task of sorted) {
    const startMin = timeToMinutes(task.due_time!);
    const duration = task.estimated_duration || 30;
    const endMin = startMin + duration;

    // Find first available column
    let col = 0;
    const activeCols = columns
      .filter((c) => c.endMinute > startMin)
      .map((c) => {
        const existingLayout = result.get(c.taskId);
        return existingLayout?.col ?? 0;
      })
      .sort((a, b) => a - b);

    while (activeCols.includes(col)) {
      col++;
    }

    columns.push({ taskId: task.id, endMinute: endMin });
    result.set(task.id, { col, totalCols: 1 }); // totalCols computed in second pass
  }

  // Second pass: compute total columns for each group
  for (const task of sorted) {
    const startMin = timeToMinutes(task.due_time!);
    const duration = task.estimated_duration || 30;
    const endMin = startMin + duration;

    const overlapping = sorted.filter((other) => {
      const otherStart = timeToMinutes(other.due_time!);
      const otherEnd = otherStart + (other.estimated_duration || 30);
      return otherStart < endMin && otherEnd > startMin;
    });

    const maxCol = Math.max(
      ...overlapping.map((o) => result.get(o.id)?.col ?? 0),
    );
    const totalCols = Math.min(maxCol + 1, 3);

    for (const o of overlapping) {
      const layout = result.get(o.id);
      if (layout) {
        layout.totalCols = Math.max(layout.totalCols, totalCols);
      }
    }
  }

  return result;
}

// --- Draggable Time Block ---
function TimeBlock({
  task,
  col,
  totalCols,
  onClick,
}: {
  task: Task;
  col: number;
  totalCols: number;
  onClick: (task: Task) => void;
}) {
  const priorityConf = PRIORITY_CONFIG[task.priority];
  const duration = task.estimated_duration || 30;
  const top = timeToOffset(task.due_time!);
  const height = Math.max((duration / 60) * HOUR_HEIGHT, 20);

  const widthPercent = totalCols > 1 ? 100 / totalCols : 100;
  const leftPercent = col * widthPercent;

  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `timeline-task-${task.id}`,
    data: { task },
  });

  return (
    <div
      ref={setNodeRef}
      {...listeners}
      {...attributes}
      className={`absolute rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] cursor-pointer transition-shadow hover:shadow-md ${
        isDragging ? 'opacity-30 z-50' : 'z-10'
      }`}
      style={{
        top: `${top}px`,
        height: `${height}px`,
        left: `calc(${leftPercent}% + 2px)`,
        width: `calc(${widthPercent}% - 4px)`,
        borderLeftWidth: '3px',
        borderLeftColor: priorityConf.color,
        touchAction: 'none',
      }}
      onClick={(e) => {
        e.stopPropagation();
        onClick(task);
      }}
    >
      <div className="flex h-full flex-col overflow-hidden px-2 py-1">
        <span className="truncate text-xs font-medium text-[var(--color-text-primary)]">
          {task.title}
        </span>
        {height > 36 && (
          <span className="truncate text-[10px] text-[var(--color-text-secondary)]">
            {formatTime(task.due_time)} ·{' '}
            {duration >= 60
              ? `${Math.floor(duration / 60)}h${duration % 60 > 0 ? ` ${duration % 60}m` : ''}`
              : `${duration}m`}
          </span>
        )}
      </div>
    </div>
  );
}

// --- Point Event (time but no duration) ---
function PointEvent({
  task,
  onClick,
}: {
  task: Task;
  onClick: (task: Task) => void;
}) {
  const priorityConf = PRIORITY_CONFIG[task.priority];
  const top = timeToOffset(task.due_time!);

  return (
    <Tooltip content={`${task.title} at ${formatTime(task.due_time)}`}>
      <button
        className="absolute left-0 right-0 z-10 flex items-center gap-1.5 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] px-2 py-1 transition-shadow hover:shadow-md"
        style={{
          top: `${top}px`,
          borderLeftWidth: '3px',
          borderLeftColor: priorityConf.color,
        }}
        onClick={(e) => {
          e.stopPropagation();
          onClick(task);
        }}
      >
        <span className="h-2 w-2 shrink-0 rounded-full" style={{ backgroundColor: priorityConf.color }} />
        <span className="truncate text-xs font-medium text-[var(--color-text-primary)]">
          {task.title}
        </span>
        <span className="ml-auto shrink-0 text-[10px] text-[var(--color-text-secondary)]">
          {formatTime(task.due_time)}
        </span>
      </button>
    </Tooltip>
  );
}

// --- Droppable Hour Slot ---
function HourSlot({
  hour,
  onClickSlot,
}: {
  hour: number;
  onClickSlot: (time: string) => void;
}) {
  const timeStr = minutesToTime(hour * 60);
  const { setNodeRef, isOver } = useDroppable({
    id: `slot-${hour}`,
    data: { time: timeStr },
  });

  return (
    <div
      ref={setNodeRef}
      className={`relative border-b border-[var(--color-border)]/50 transition-colors ${
        isOver ? 'bg-[var(--color-accent)]/5' : ''
      }`}
      style={{ height: `${HOUR_HEIGHT}px` }}
      onClick={() => onClickSlot(timeStr)}
    >
      {/* Half-hour line */}
      <div
        className="absolute left-0 right-0 border-b border-dashed border-[var(--color-border)]/30"
        style={{ top: `${HOUR_HEIGHT / 2}px` }}
      />
    </div>
  );
}

// --- Current Time Indicator ---
function CurrentTimeIndicator() {
  const [now, setNow] = useState(new Date());

  useEffect(() => {
    const interval = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(interval);
  }, []);

  const hours = now.getHours();
  const minutes = now.getMinutes();

  if (hours < START_HOUR || hours >= END_HOUR) return null;

  const top = ((hours - START_HOUR) * 60 + minutes) / 60 * HOUR_HEIGHT;
  const timeLabel = format(now, 'h:mm a');

  return (
    <div
      className="absolute left-0 right-0 z-20 pointer-events-none"
      style={{ top: `${top}px` }}
    >
      <div className="flex items-center">
        <span className="shrink-0 rounded-full bg-red-500 px-1.5 py-0.5 text-[9px] font-medium text-white">
          {timeLabel}
        </span>
        <div className="flex-1 border-t-2 border-red-500" />
      </div>
    </div>
  );
}

// --- Main TimelineScreen ---
export function TimelineScreen() {
  const { currentDate, goForward, goBack, goToToday, label, dateRange } =
    useDateNavigation('day');
  const { getTasksForDate, refetch } = useCalendarTasks(
    dateRange.start,
    dateRange.end,
  );
  const { updateTask, setSelectedTask } = useTaskStore();

  const timeGridRef = useRef<HTMLDivElement>(null);

  const [quickCreateTime, setQuickCreateTime] = useState<string | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [activeDragTask, setActiveDragTask] = useState<Task | null>(null);

  const dateStr = format(currentDate, 'yyyy-MM-dd');
  const dayTasks = getTasksForDate(currentDate);

  // Separate tasks into scheduled (with time) and unscheduled
  const { scheduledTasks, pointEvents, unscheduledTasks } = useMemo(() => {
    const scheduled: Task[] = [];
    const points: Task[] = [];
    const unscheduled: Task[] = [];

    for (const task of dayTasks) {
      if (task.due_time) {
        if (task.estimated_duration && task.estimated_duration > 0) {
          scheduled.push(task);
        } else {
          points.push(task);
        }
      } else {
        unscheduled.push(task);
      }
    }

    return {
      scheduledTasks: scheduled,
      pointEvents: points,
      unscheduledTasks: unscheduled,
    };
  }, [dayTasks]);

  // Compute overlap columns for scheduled tasks
  const columns = useMemo(
    () => computeColumns(scheduledTasks),
    [scheduledTasks],
  );

  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: { distance: 5 },
    }),
  );

  const handleDragStart = (event: DragStartEvent) => {
    const task = event.active.data.current?.task as Task | undefined;
    if (task) setActiveDragTask(task);
  };

  const handleDragEnd = async (event: DragEndEvent) => {
    setActiveDragTask(null);
    const { active, over } = event;
    if (!over) return;

    const task = active.data.current?.task as Task | undefined;
    const targetTime = over.data.current?.time as string | undefined;
    if (!task || !targetTime) return;

    // Don't update if dropped on same time slot
    if (task.due_time === targetTime) return;

    try {
      await updateTask(task.id, { due_date: dateStr });
      toast.success('Task rescheduled');
      refetch();
    } catch {
      toast.error('Failed to reschedule task');
    }
  };

  const handleTaskClick = (task: Task) => {
    setSelectedTask(task);
    setEditorOpen(true);
  };

  const handleEditorClose = () => {
    setEditorOpen(false);
    setSelectedTask(null);
  };

  const handleSlotClick = (time: string) => {
    setQuickCreateTime(time);
  };

  // Scroll to current time on mount
  useEffect(() => {
    if (!timeGridRef.current || !isToday(currentDate)) return;
    const now = new Date();
    const hours = now.getHours();
    if (hours >= START_HOUR && hours < END_HOUR) {
      const offset = ((hours - START_HOUR) * HOUR_HEIGHT) - 100;
      timeGridRef.current.scrollTop = Math.max(0, offset);
    }
  }, [currentDate]);

  // Keyboard navigation
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const active = document.activeElement;
      if (
        active?.tagName === 'INPUT' ||
        active?.tagName === 'TEXTAREA' ||
        active?.tagName === 'SELECT'
      )
        return;

      switch (e.key) {
        case 'ArrowLeft':
          e.preventDefault();
          goBack();
          break;
        case 'ArrowRight':
          e.preventDefault();
          goForward();
          break;
        case 't':
          e.preventDefault();
          goToToday();
          break;
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [goBack, goForward, goToToday]);

  const hours = Array.from(
    { length: TOTAL_HOURS },
    (_, i) => START_HOUR + i,
  );

  return (
    <div className="mx-auto max-w-4xl">
      <CalendarNav />

      {/* Header */}
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={goBack}
            className="rounded-lg p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]"
            aria-label="Previous day"
          >
            <ChevronLeft className="h-5 w-5" />
          </button>
          <h1 className="text-xl font-bold text-[var(--color-text-primary)] min-w-[240px] text-center">
            {label}
          </h1>
          <button
            onClick={goForward}
            className="rounded-lg p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]"
            aria-label="Next day"
          >
            <ChevronRight className="h-5 w-5" />
          </button>
        </div>
        <Button variant="ghost" size="sm" onClick={goToToday}>
          Today
        </Button>
      </div>

      {/* Unscheduled tasks section */}
      {unscheduledTasks.length > 0 && (
        <div className="mb-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3">
          <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-[var(--color-text-secondary)]">
            Unscheduled
          </h3>
          <div className="flex flex-wrap gap-2">
            {unscheduledTasks.map((task) => {
              const priorityConf = PRIORITY_CONFIG[task.priority];
              return (
                <button
                  key={task.id}
                  onClick={() => handleTaskClick(task)}
                  className="flex items-center gap-1.5 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2.5 py-1.5 text-xs transition-colors hover:border-[var(--color-accent)]/50"
                >
                  <span
                    className="h-2 w-2 rounded-full"
                    style={{ backgroundColor: priorityConf.color }}
                  />
                  <span className="text-[var(--color-text-primary)]">
                    {task.title}
                  </span>
                </button>
              );
            })}
          </div>
        </div>
      )}

      {/* Time grid */}
      <DndContext
        sensors={sensors}
        onDragStart={handleDragStart}
        onDragEnd={handleDragEnd}
      >
        <div
          ref={timeGridRef}
          className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] overflow-y-auto"
          style={{ maxHeight: 'calc(100vh - 280px)' }}
        >
          <div className="relative flex">
            {/* Hour labels column */}
            <div className="w-16 shrink-0 border-r border-[var(--color-border)]">
              {hours.map((hour) => (
                <div
                  key={hour}
                  className="relative flex items-start justify-end pr-2"
                  style={{ height: `${HOUR_HEIGHT}px` }}
                >
                  <span className="relative -top-2 text-[10px] font-medium text-[var(--color-text-secondary)]">
                    {format(
                      new Date(2000, 0, 1, hour),
                      'h a',
                    )}
                  </span>
                </div>
              ))}
            </div>

            {/* Time slots and events */}
            <div className="relative flex-1">
              {/* Hour slot backgrounds */}
              {hours.map((hour) => (
                <HourSlot
                  key={hour}
                  hour={hour}
                  onClickSlot={handleSlotClick}
                />
              ))}

              {/* Current time indicator */}
              {isToday(currentDate) && <CurrentTimeIndicator />}

              {/* Scheduled task blocks */}
              {scheduledTasks.map((task) => {
                const layout = columns.get(task.id) || {
                  col: 0,
                  totalCols: 1,
                };
                return (
                  <TimeBlock
                    key={task.id}
                    task={task}
                    col={layout.col}
                    totalCols={layout.totalCols}
                    onClick={handleTaskClick}
                  />
                );
              })}

              {/* Point events (time but no duration) */}
              {pointEvents.map((task) => (
                <PointEvent
                  key={task.id}
                  task={task}
                  onClick={handleTaskClick}
                />
              ))}

              {/* Quick create at time slot */}
              {quickCreateTime && (
                <div
                  className="absolute left-0 right-0 z-30 px-2"
                  style={{ top: `${timeToOffset(quickCreateTime)}px` }}
                >
                  <QuickCreateInput
                    date={dateStr}
                    time={quickCreateTime}
                    onCreated={() => {
                      setQuickCreateTime(null);
                      refetch();
                    }}
                    onCancel={() => setQuickCreateTime(null)}
                  />
                </div>
              )}
            </div>
          </div>
        </div>

        <DragOverlay>
          {activeDragTask && (
            <div className="w-48 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] p-2 shadow-lg opacity-90"
              style={{
                borderLeftWidth: '3px',
                borderLeftColor: PRIORITY_CONFIG[activeDragTask.priority].color,
              }}
            >
              <span className="truncate text-xs font-medium text-[var(--color-text-primary)]">
                {activeDragTask.title}
              </span>
            </div>
          )}
        </DragOverlay>
      </DndContext>

      <Suspense fallback={null}>
        {editorOpen && (
          <TaskEditor onClose={handleEditorClose} onUpdate={refetch} />
        )}
      </Suspense>
    </div>
  );
}
