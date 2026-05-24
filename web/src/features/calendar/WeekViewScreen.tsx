import { useState, useEffect } from 'react';
import {
  format,
  addDays,
  startOfWeek,
  isToday,
} from 'date-fns';
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
import { CalendarTaskCard } from './CalendarTaskCard';
import { CalendarHabitChips } from './CalendarHabitChips';
import { weekOffsetFromMonday } from './calendarDay';
import { QuickCreateInput } from './QuickCreateInput';
import { useDateNavigation } from '@/hooks/useDateNavigation';
import { useCalendarTasks } from '@/hooks/useCalendarTasks';
import { useCalendarHabits } from '@/hooks/useCalendarHabits';
import { useTaskStore } from '@/stores/taskStore';
import { useIsMobile, useIsTablet } from '@/hooks/useMediaQuery';
import { Button } from '@/components/ui/Button';
import type { Task } from '@/types/task';
import type { Habit } from '@/types/habit';
import { lazy, Suspense } from 'react';

const TaskEditor = lazy(() => import('@/features/tasks/TaskEditor'));

// --- Draggable Task Card wrapper ---
function DraggableTaskCard({
  task,
  onClick,
}: {
  task: Task;
  onClick: (task: Task) => void;
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `task-${task.id}`,
    data: { task },
  });

  return (
    <div
      ref={setNodeRef}
      {...listeners}
      {...attributes}
      className={`${isDragging ? 'opacity-30' : ''}`}
      style={{ touchAction: 'none' }}
    >
      <CalendarTaskCard task={task} onClick={onClick} />
    </div>
  );
}

// --- Droppable Day Column ---
function DayColumn({
  date,
  tasks,
  habits,
  isHighlighted,
  onTaskClick,
  onQuickCreate,
  quickCreateDate,
  onQuickCreateDone,
  onQuickCreateCancel,
}: {
  date: Date;
  tasks: Task[];
  habits: Habit[];
  isHighlighted: boolean;
  onTaskClick: (task: Task) => void;
  onQuickCreate: (date: string) => void;
  quickCreateDate: string | null;
  onQuickCreateDone: () => void;
  onQuickCreateCancel: () => void;
}) {
  const dateStr = format(date, 'yyyy-MM-dd');
  const dayName = format(date, 'EEE');
  const dayNum = format(date, 'd');
  const today = isToday(date);

  const { setNodeRef, isOver } = useDroppable({
    id: `day-${dateStr}`,
    data: { date: dateStr },
  });

  return (
    <div
      ref={setNodeRef}
      className={`flex flex-col border-r border-[var(--color-border)] last:border-r-0 min-h-[300px] transition-colors ${
        isOver ? 'bg-[var(--color-accent)]/5' : ''
      } ${isHighlighted ? 'bg-[var(--color-accent)]/[0.03]' : ''}`}
      onClick={(e) => {
        // Only trigger if clicking the empty area, not a task card
        if (e.target === e.currentTarget || (e.target as HTMLElement).closest('[data-column-area]')) {
          onQuickCreate(dateStr);
        }
      }}
    >
      {/* Column header */}
      <div className="flex flex-col items-center border-b border-[var(--color-border)] px-1 py-2">
        <span
          className={`text-xs font-medium ${
            today
              ? 'text-[var(--color-accent)]'
              : 'text-[var(--color-text-secondary)]'
          }`}
        >
          {dayName}
        </span>
        <span
          className={`mt-0.5 flex h-7 w-7 items-center justify-center rounded-full text-sm font-semibold ${
            today
              ? 'bg-[var(--color-accent)] text-white'
              : 'text-[var(--color-text-primary)]'
          }`}
        >
          {dayNum}
        </span>
        {tasks.length > 0 && (
          <span className="mt-1 rounded-full bg-[var(--color-bg-secondary)] px-1.5 py-0.5 text-[10px] font-medium text-[var(--color-text-secondary)]">
            {tasks.length}
          </span>
        )}
      </div>

      {/* Task cards */}
      <div className="flex flex-1 flex-col gap-1 p-1.5" data-column-area>
        <CalendarHabitChips habits={habits} />
        {tasks.map((task) => (
          <DraggableTaskCard key={task.id} task={task} onClick={onTaskClick} />
        ))}

        {/* Quick create input */}
        {quickCreateDate === dateStr && (
          <QuickCreateInput
            date={dateStr}
            onCreated={onQuickCreateDone}
            onCancel={onQuickCreateCancel}
          />
        )}
      </div>
    </div>
  );
}

// --- Mobile Day View (single day with swipe nav) ---
function MobileDayView({
  date,
  tasks,
  habits,
  onTaskClick,
  onQuickCreate,
  quickCreateDate,
  onQuickCreateDone,
  onQuickCreateCancel,
  onPrev,
  onNext,
}: {
  date: Date;
  tasks: Task[];
  habits: Habit[];
  onTaskClick: (task: Task) => void;
  onQuickCreate: (date: string) => void;
  quickCreateDate: string | null;
  onQuickCreateDone: () => void;
  onQuickCreateCancel: () => void;
  onPrev: () => void;
  onNext: () => void;
}) {
  const dateStr = format(date, 'yyyy-MM-dd');
  const today = isToday(date);

  return (
    <div className="flex flex-col">
      {/* Day header with mini nav */}
      <div className="flex items-center justify-between border-b border-[var(--color-border)] px-4 py-3">
        <button onClick={onPrev} className="p-1 text-[var(--color-text-secondary)]">
          <ChevronLeft className="h-5 w-5" />
        </button>
        <div className="text-center">
          <div
            className={`text-lg font-semibold ${
              today ? 'text-[var(--color-accent)]' : 'text-[var(--color-text-primary)]'
            }`}
          >
            {format(date, 'EEEE')}
          </div>
          <div className="text-xs text-[var(--color-text-secondary)]">
            {format(date, 'MMM d, yyyy')}
          </div>
        </div>
        <button onClick={onNext} className="p-1 text-[var(--color-text-secondary)]">
          <ChevronRight className="h-5 w-5" />
        </button>
      </div>

      {/* Tasks */}
      <div className="flex flex-col gap-2 p-4">
        <CalendarHabitChips habits={habits} />

        {tasks.length === 0 && habits.length === 0 && !quickCreateDate && (
          <button
            onClick={() => onQuickCreate(dateStr)}
            className="rounded-lg border border-dashed border-[var(--color-border)] py-8 text-center text-sm text-[var(--color-text-secondary)] hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]"
          >
            No tasks. Tap to add one.
          </button>
        )}

        {tasks.map((task) => (
          <CalendarTaskCard key={task.id} task={task} onClick={onTaskClick} />
        ))}

        {quickCreateDate === dateStr && (
          <QuickCreateInput
            date={dateStr}
            onCreated={onQuickCreateDone}
            onCancel={onQuickCreateCancel}
          />
        )}

        {tasks.length > 0 && quickCreateDate !== dateStr && (
          <button
            onClick={() => onQuickCreate(dateStr)}
            className="mt-1 flex items-center gap-1 text-xs text-[var(--color-text-secondary)] hover:text-[var(--color-accent)]"
          >
            + Add Task
          </button>
        )}
      </div>
    </div>
  );
}

// --- Main WeekViewScreen ---
export function WeekViewScreen() {
  const { currentDate, goForward, goBack, goToToday, label, dateRange } =
    useDateNavigation('week');
  const { getTasksForDate, refetch } = useCalendarTasks(
    dateRange.start,
    dateRange.end,
  );
  const { getHabitsForDate, fetchHabits } = useCalendarHabits();
  const { updateTask, setSelectedTask } = useTaskStore();

  const isMobile = useIsMobile();
  const isTablet = useIsTablet();

  useEffect(() => {
    fetchHabits();
  }, [fetchHabits]);

  const [quickCreateDate, setQuickCreateDate] = useState<string | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [activeDragTask, setActiveDragTask] = useState<Task | null>(null);
  // Default the mobile single-day card to *today* within the current week
  // (Mon=0 … Sun=6), not the start of the week (bug B-11).
  const [mobileDayOffset, setMobileDayOffset] = useState(() =>
    weekOffsetFromMonday(new Date()),
  );

  // Generate week days (Mon-Sun)
  const weekDays = Array.from({ length: 7 }, (_, i) =>
    addDays(startOfWeek(currentDate, { weekStartsOn: 1 }), i),
  );

  // DnD sensors — require 5px movement before starting drag to allow clicks
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
    const targetDate = over.data.current?.date as string | undefined;
    if (!task || !targetDate) return;

    // Don't update if dropped on same day
    if (task.due_date === targetDate) return;

    try {
      await updateTask(task.id, { due_date: targetDate });
      toast.success('Task moved');
      refetch();
    } catch {
      toast.error('Failed to move task');
    }
  };

  const handleTaskClick = (task: Task) => {
    setSelectedTask(task);
    setEditorOpen(true);
  };

  const handleQuickCreate = (dateStr: string) => {
    setQuickCreateDate(dateStr);
  };

  const handleQuickCreateDone = () => {
    setQuickCreateDate(null);
    refetch();
  };

  const handleQuickCreateCancel = () => {
    setQuickCreateDate(null);
  };

  const handleEditorClose = () => {
    setEditorOpen(false);
    setSelectedTask(null);
  };

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

  // Mobile: show single day view
  if (isMobile) {
    const mobileDate = addDays(
      startOfWeek(currentDate, { weekStartsOn: 1 }),
      mobileDayOffset,
    );
    const mobileTasks = getTasksForDate(mobileDate);
    const mobileHabits = getHabitsForDate(mobileDate);

    return (
      <div className="mx-auto max-w-6xl">
        <CalendarNav />

        {/* Header */}
        <div className="mb-4 flex items-center justify-between">
          <h1 className="text-lg font-bold text-[var(--color-text-primary)]">
            Week View
          </h1>
          <Button variant="ghost" size="sm" onClick={goToToday}>
            Today
          </Button>
        </div>

        {/* Week selector row */}
        <div className="mb-4 flex items-center justify-between">
          <button
            onClick={goBack}
            className="rounded-lg p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]"
          >
            <ChevronLeft className="h-4 w-4" />
          </button>
          <span className="text-sm font-medium text-[var(--color-text-primary)]">
            {label}
          </span>
          <button
            onClick={goForward}
            className="rounded-lg p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]"
          >
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>

        {/* Day tabs */}
        <div className="mb-4 flex gap-1 overflow-x-auto pb-1">
          {weekDays.map((day, i) => {
            const count = getTasksForDate(day).length;
            return (
              <button
                key={i}
                onClick={() => setMobileDayOffset(i)}
                className={`flex flex-col items-center rounded-lg px-3 py-2 text-xs transition-colors ${
                  i === mobileDayOffset
                    ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                    : isToday(day)
                      ? 'text-[var(--color-accent)]'
                      : 'text-[var(--color-text-secondary)]'
                }`}
              >
                <span className="font-medium">{format(day, 'EEE')}</span>
                <span className="mt-0.5 font-semibold">{format(day, 'd')}</span>
                {count > 0 && (
                  <span className="mt-0.5 h-1.5 w-1.5 rounded-full bg-[var(--color-accent)]" />
                )}
              </button>
            );
          })}
        </div>

        <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)]">
          <MobileDayView
            date={mobileDate}
            tasks={mobileTasks}
            habits={mobileHabits}
            onTaskClick={handleTaskClick}
            onQuickCreate={handleQuickCreate}
            quickCreateDate={quickCreateDate}
            onQuickCreateDone={handleQuickCreateDone}
            onQuickCreateCancel={handleQuickCreateCancel}
            onPrev={() =>
              setMobileDayOffset((p) => (p > 0 ? p - 1 : p))
            }
            onNext={() =>
              setMobileDayOffset((p) => (p < 6 ? p + 1 : p))
            }
          />
        </div>

        <Suspense fallback={null}>
          {editorOpen && (
            <TaskEditor onClose={handleEditorClose} onUpdate={refetch} />
          )}
        </Suspense>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-7xl">
      <CalendarNav />

      {/* Header */}
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={goBack}
            className="rounded-lg p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]"
            aria-label="Previous week"
          >
            <ChevronLeft className="h-5 w-5" />
          </button>
          <h1 className="text-xl font-bold text-[var(--color-text-primary)] min-w-[220px] text-center">
            {label}
          </h1>
          <button
            onClick={goForward}
            className="rounded-lg p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]"
            aria-label="Next week"
          >
            <ChevronRight className="h-5 w-5" />
          </button>
        </div>
        <Button variant="ghost" size="sm" onClick={goToToday}>
          Today
        </Button>
      </div>

      {/* Week grid */}
      <DndContext
        sensors={sensors}
        onDragStart={handleDragStart}
        onDragEnd={handleDragEnd}
      >
        <div
          className={`grid rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] overflow-hidden ${
            isTablet ? 'grid-cols-5' : 'grid-cols-7'
          }`}
        >
          {weekDays
            .slice(0, isTablet ? 5 : 7)
            .map((day) => (
              <DayColumn
                key={format(day, 'yyyy-MM-dd')}
                date={day}
                tasks={getTasksForDate(day)}
                habits={getHabitsForDate(day)}
                isHighlighted={isToday(day)}
                onTaskClick={handleTaskClick}
                onQuickCreate={handleQuickCreate}
                quickCreateDate={quickCreateDate}
                onQuickCreateDone={handleQuickCreateDone}
                onQuickCreateCancel={handleQuickCreateCancel}
              />
            ))}
        </div>

        {/* Tablet: scrollable weekend */}
        {isTablet && (
          <div className="mt-2 grid grid-cols-2 gap-2">
            {weekDays.slice(5).map((day) => (
              <div
                key={format(day, 'yyyy-MM-dd')}
                className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] overflow-hidden"
              >
                <DayColumn
                  date={day}
                  tasks={getTasksForDate(day)}
                  habits={getHabitsForDate(day)}
                  isHighlighted={isToday(day)}
                  onTaskClick={handleTaskClick}
                  onQuickCreate={handleQuickCreate}
                  quickCreateDate={quickCreateDate}
                  onQuickCreateDone={handleQuickCreateDone}
                  onQuickCreateCancel={handleQuickCreateCancel}
                />
              </div>
            ))}
          </div>
        )}

        {/* Drag overlay — floating card while dragging */}
        <DragOverlay>
          {activeDragTask && (
            <div className="w-40 opacity-90 shadow-lg">
              <CalendarTaskCard
                task={activeDragTask}
                onClick={() => {}}
              />
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
