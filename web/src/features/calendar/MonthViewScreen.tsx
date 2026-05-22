import { useState, useEffect, useMemo } from 'react';
import {
  format,
  startOfWeek,
  endOfWeek,
  addDays,
  isSameMonth,
  isToday,
  isSameDay,
} from 'date-fns';
import { ChevronLeft, ChevronRight, X } from 'lucide-react';
import { CalendarNav } from './CalendarNav';
import { QuickCreateInput } from './QuickCreateInput';
import { useDateNavigation } from '@/hooks/useDateNavigation';
import { useCalendarTasks } from '@/hooks/useCalendarTasks';
import { useTaskStore } from '@/stores/taskStore';
import { useIsMobile } from '@/hooks/useMediaQuery';
import { Button } from '@/components/ui/Button';
import { Checkbox } from '@/components/ui/Checkbox';
import { PRIORITY_CONFIG } from '@/utils/priority';
import { getPriorityColor } from '@/utils/priority';
import type { Task } from '@/types/task';
import { lazy, Suspense } from 'react';

const TaskEditor = lazy(() => import('@/features/tasks/TaskEditor'));

const DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const DAY_LABELS_SHORT = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];

// --- Priority dot indicators ---
function TaskDensityDots({
  tasks,
}: {
  tasks: Task[];
}) {
  if (tasks.length === 0) return null;

  // Sort by priority (lowest number = highest priority)
  const sorted = [...tasks].sort((a, b) => a.priority - b.priority);
  const dots = sorted.slice(0, 3);

  return (
    <div className="flex items-center justify-center gap-0.5 mt-0.5">
      {dots.map((task, i) => (
        <span
          key={i}
          className="h-1.5 w-1.5 rounded-full"
          style={{ backgroundColor: PRIORITY_CONFIG[task.priority].color }}
        />
      ))}
    </div>
  );
}

// --- Day Detail Panel ---
function DayDetailPanel({
  date,
  tasks,
  onTaskClick,
  onClose,
  onComplete,
  onUncomplete,
  onQuickCreate,
  quickCreateDate,
  onQuickCreateDone,
  onQuickCreateCancel,
}: {
  date: Date;
  tasks: Task[];
  onTaskClick: (task: Task) => void;
  onClose: () => void;
  onComplete: (taskId: string) => void;
  onUncomplete: (taskId: string) => void;
  onQuickCreate: (dateStr: string) => void;
  quickCreateDate: string | null;
  onQuickCreateDone: () => void;
  onQuickCreateCancel: () => void;
}) {
  const dateStr = format(date, 'yyyy-MM-dd');

  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <div className="mb-3 flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">
            {format(date, 'EEEE, MMMM d')}
          </h3>
          <span className="text-xs text-[var(--color-text-secondary)]">
            {tasks.length} {tasks.length === 1 ? 'task' : 'tasks'}
          </span>
        </div>
        <button
          onClick={onClose}
          className="rounded-lg p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      {/* Task list */}
      <div className="flex flex-col gap-1">
        {tasks.map((task) => {
          const isDone = task.status === 'done';
          return (
            <div
              key={task.id}
              className="group flex items-center gap-2.5 rounded-lg px-2 py-2 transition-colors hover:bg-[var(--color-bg-secondary)]"
            >
              <Checkbox
                checked={isDone}
                onChange={(checked) =>
                  checked ? onComplete(task.id) : onUncomplete(task.id)
                }
                priorityColor={getPriorityColor(task.priority)}
              />
              <button
                type="button"
                className="flex flex-1 items-center gap-2 text-left min-w-0"
                onClick={() => onTaskClick(task)}
              >
                <span
                  className={`flex-1 truncate text-sm ${
                    isDone
                      ? 'text-[var(--color-text-secondary)] line-through'
                      : 'text-[var(--color-text-primary)]'
                  }`}
                >
                  {task.title}
                </span>
              </button>
            </div>
          );
        })}
      </div>

      {/* Quick create */}
      {quickCreateDate === dateStr ? (
        <div className="mt-2">
          <QuickCreateInput
            date={dateStr}
            onCreated={onQuickCreateDone}
            onCancel={onQuickCreateCancel}
          />
        </div>
      ) : (
        <button
          onClick={() => onQuickCreate(dateStr)}
          className="mt-2 flex w-full items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-accent)]"
        >
          + Add Task
        </button>
      )}
    </div>
  );
}

// --- Main MonthViewScreen ---
export function MonthViewScreen() {
  const { currentDate, goForward, goBack, goToToday, label, dateRange } =
    useDateNavigation('month');

  // Extend date range to include partial weeks at start/end of month
  const calendarStart = startOfWeek(dateRange.start, { weekStartsOn: 1 });
  const calendarEnd = endOfWeek(dateRange.end, { weekStartsOn: 1 });

  const { getTasksForDate, refetch } =
    useCalendarTasks(calendarStart, calendarEnd);
  const { setSelectedTask, completeTask, uncompleteTask } =
    useTaskStore();

  const isMobile = useIsMobile();
  const [selectedDay, setSelectedDay] = useState<Date | null>(null);
  const [quickCreateDate, setQuickCreateDate] = useState<string | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);

  // Stabilize deps for useMemo (Date objects are new every render)
  const calendarStartTime = calendarStart.getTime();
  const calendarEndTime = calendarEnd.getTime();

  // Build calendar grid: 6 rows of 7 days
  const calendarDays = useMemo(() => {
    const start = new Date(calendarStartTime);
    const end = new Date(calendarEndTime);
    const days: Date[] = [];
    let current = start;
    while (current <= end) {
      days.push(current);
      current = addDays(current, 1);
    }
    // Ensure we have exactly 42 days (6 weeks)
    while (days.length < 42) {
      days.push(addDays(days[days.length - 1], 1));
    }
    return days;
  }, [calendarStartTime, calendarEndTime]);

  const weeks = useMemo(() => {
    const result: Date[][] = [];
    for (let i = 0; i < calendarDays.length; i += 7) {
      result.push(calendarDays.slice(i, i + 7));
    }
    return result;
  }, [calendarDays]);

  const handleTaskClick = (task: Task) => {
    setSelectedTask(task);
    setEditorOpen(true);
  };

  const handleDayClick = (day: Date) => {
    if (selectedDay && isSameDay(selectedDay, day)) {
      setSelectedDay(null);
    } else {
      setSelectedDay(day);
    }
  };

  const handleEditorClose = () => {
    setEditorOpen(false);
    setSelectedTask(null);
  };

  const handleComplete = async (taskId: string) => {
    try {
      await completeTask(taskId);
      refetch();
    } catch {
      // handled by store
    }
  };

  const handleUncomplete = async (taskId: string) => {
    try {
      await uncompleteTask(taskId);
      refetch();
    } catch {
      // handled by store
    }
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
        case 'Escape':
          if (selectedDay) {
            e.preventDefault();
            setSelectedDay(null);
          }
          break;
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [goBack, goForward, goToToday, selectedDay]);

  return (
    <div className="mx-auto max-w-7xl">
      <CalendarNav />

      {/* Header */}
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={goBack}
            className="rounded-lg p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]"
            aria-label="Previous month"
          >
            <ChevronLeft className="h-5 w-5" />
          </button>
          <h1 className="text-xl font-bold text-[var(--color-text-primary)] min-w-[180px] text-center">
            {label}
          </h1>
          <button
            onClick={goForward}
            className="rounded-lg p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]"
            aria-label="Next month"
          >
            <ChevronRight className="h-5 w-5" />
          </button>
        </div>
        <Button variant="ghost" size="sm" onClick={goToToday}>
          Today
        </Button>
      </div>

      <div className={`flex gap-4 ${isMobile ? 'flex-col' : ''}`}>
        {/* Calendar grid */}
        <div className="flex-1">
          <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] overflow-hidden">
            {/* Day of week headers */}
            <div className="grid grid-cols-7 border-b border-[var(--color-border)]">
              {(isMobile ? DAY_LABELS_SHORT : DAY_LABELS).map((day) => (
                <div
                  key={day}
                  className="py-2 text-center text-xs font-medium text-[var(--color-text-secondary)]"
                >
                  {day}
                </div>
              ))}
            </div>

            {/* Calendar rows */}
            {weeks.map((week, weekIdx) => (
              <div
                key={weekIdx}
                className="grid grid-cols-7 border-b border-[var(--color-border)] last:border-b-0"
              >
                {week.map((day) => {
                  const inMonth = isSameMonth(day, currentDate);
                  const today = isToday(day);
                  const isSelected =
                    selectedDay !== null && isSameDay(day, selectedDay);
                  const tasks = getTasksForDate(day);
                  const dateStr = format(day, 'yyyy-MM-dd');

                  return (
                    <button
                      key={dateStr}
                      onClick={() => handleDayClick(day)}
                      className={`relative flex flex-col items-center border-r border-[var(--color-border)] last:border-r-0 transition-colors ${
                        isMobile ? 'py-2 min-h-[48px]' : 'py-2 min-h-[72px]'
                      } ${
                        isSelected
                          ? 'bg-[var(--color-accent)]/5'
                          : 'hover:bg-[var(--color-bg-secondary)]'
                      }`}
                    >
                      <span
                        className={`flex h-6 w-6 items-center justify-center rounded-full text-xs font-medium ${
                          today
                            ? 'bg-[var(--color-accent)] text-white'
                            : inMonth
                              ? 'text-[var(--color-text-primary)]'
                              : 'text-[var(--color-text-secondary)]/50'
                        }`}
                      >
                        {format(day, 'd')}
                      </span>

                      <TaskDensityDots tasks={tasks} />

                      {/* Task count on desktop */}
                      {!isMobile && tasks.length > 0 && (
                        <span className="mt-0.5 text-[10px] text-[var(--color-text-secondary)]">
                          {tasks.length} {tasks.length === 1 ? 'task' : 'tasks'}
                        </span>
                      )}
                    </button>
                  );
                })}
              </div>
            ))}
          </div>
        </div>

        {/* Day detail panel — side panel on desktop, below on mobile */}
        {selectedDay && (
          <div className={isMobile ? 'w-full' : 'w-80 shrink-0'}>
            <DayDetailPanel
              date={selectedDay}
              tasks={getTasksForDate(selectedDay)}
              onTaskClick={handleTaskClick}
              onClose={() => setSelectedDay(null)}
              onComplete={handleComplete}
              onUncomplete={handleUncomplete}
              onQuickCreate={(d) => setQuickCreateDate(d)}
              quickCreateDate={quickCreateDate}
              onQuickCreateDone={() => {
                setQuickCreateDate(null);
                refetch();
              }}
              onQuickCreateCancel={() => setQuickCreateDate(null)}
            />
          </div>
        )}
      </div>

      <Suspense fallback={null}>
        {editorOpen && (
          <TaskEditor onClose={handleEditorClose} onUpdate={refetch} />
        )}
      </Suspense>
    </div>
  );
}
