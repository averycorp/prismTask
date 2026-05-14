import { useEffect, useState, useCallback, useRef } from 'react';
import {
  Sun,
  ChevronDown,
  ChevronRight,
  PartyPopper,
  Activity,
  BarChart3,
} from 'lucide-react';
import { format, parseISO, startOfWeek, subDays, isMonday, isSunday } from 'date-fns';
import { toast } from 'sonner';
import { useNavigate } from 'react-router-dom';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import { useHabitStore } from '@/stores/habitStore';
import { dashboardApi } from '@/api/dashboard';
import { TaskRow } from '@/components/shared/TaskRow';
import { Spinner } from '@/components/ui/Spinner';
import TaskEditor from '@/features/tasks/TaskEditor';
import type { Task } from '@/types/task';
import type { DashboardSummary } from '@/types/api';
import { MedicationSlotList } from '@/features/daily-essentials/MedicationSlotList';
import { MorningCheckInCard } from '@/features/checkin/MorningCheckInCard';
import { BoundaryTodayBanner } from '@/features/boundaries/BoundaryTodayBanner';
import { SelfCareNudgeCard } from '@/features/today/SelfCareNudgeCard';
import { TodayLeisureMinimumRow } from '@/features/today/TodayLeisureMinimumRow';
import { SchoolworkTodayCard } from '@/features/today/SchoolworkTodayCard';
import { TodayBalanceBar } from '@/features/today/TodayBalanceBar';
import { useSettingsStore } from '@/stores/settingsStore';
import { Sparkles as SparklesIcon } from 'lucide-react';
import { useLogicalToday } from '@/utils/useLogicalToday';

const COLLAPSE_KEY = 'prismtask_today_collapse';

function loadCollapseState(): Record<string, boolean> {
  try {
    return JSON.parse(localStorage.getItem(COLLAPSE_KEY) || '{}');
  } catch {
    return {};
  }
}

function saveCollapseState(state: Record<string, boolean>) {
  localStorage.setItem(COLLAPSE_KEY, JSON.stringify(state));
}

export function TodayScreen() {
  const navigate = useNavigate();
  const {
    todayTasks,
    overdueTasks,
    upcomingTasks,
    fetchToday,
    fetchOverdue,
    fetchUpcoming,
    updateTask,
    completeTask,
    uncompleteTask,
    setSelectedTask,
  } = useTaskStore();

  const { projects, fetchAllProjects } = useProjectStore();

  const {
    habits,
    fetchHabits,
    toggleCompletion,
    isTodayCompleted,
    getTodayCount,
    getTodayProgress,
  } = useHabitStore();

  const settingsShowBriefing = useSettingsStore((s) => s.showBriefingCard);
  const settingsStartOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  // Derive a bundle so the reference is stable across renders and
  // callers can read the logical "today" ISO without recomputing.
  const settingsStartOfDayTodayIso = useLogicalToday(settingsStartOfDayHour);
  const settingsStartOfDay = {
    hour: settingsStartOfDayHour,
    todayIso: settingsStartOfDayTodayIso,
  };

  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>(
    loadCollapseState,
  );
  const [editorOpen, setEditorOpen] = useState(false);
  const refreshTimerRef = useRef<ReturnType<typeof setInterval>>(undefined);

  // Track completed tasks for undo
  const undoTimerRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(
    new Map(),
  );
  const [pendingCompletions, setPendingCompletions] = useState<Set<string>>(
    new Set(),
  );

  const projectMap = new Map(projects.map((p) => [p.id, p]));

  const loadData = useCallback(async () => {
    try {
      await Promise.all([
        fetchToday(),
        fetchOverdue(),
        fetchUpcoming(7),
        fetchAllProjects(),
        fetchHabits(),
        dashboardApi.getSummary().then(setSummary).catch(() => {}),
      ]);
    } finally {
      setLoading(false);
    }
  }, [fetchToday, fetchOverdue, fetchUpcoming, fetchAllProjects, fetchHabits]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // Auto-refresh every 60s
  useEffect(() => {
    refreshTimerRef.current = setInterval(() => {
      if (!document.hidden) {
        loadData();
      }
    }, 60000);
    return () => clearInterval(refreshTimerRef.current);
  }, [loadData]);

  // Cleanup undo timers on unmount
  useEffect(() => {
    const timers = undoTimerRef.current;
    return () => {
      timers.forEach((timer) => clearTimeout(timer));
    };
  }, []);

  const toggleSection = (key: string) => {
    setCollapsed((prev) => {
      const next = { ...prev, [key]: !prev[key] };
      saveCollapseState(next);
      return next;
    });
  };

  const handleComplete = useCallback(
    (taskId: string) => {
      // Optimistic: add to pending completions visually
      setPendingCompletions((prev) => new Set([...prev, taskId]));

      // Set up undo timer (5s)
      const timer = setTimeout(async () => {
        try {
          await completeTask(taskId);
        } catch {
          toast.error('Failed to complete task');
        }
        setPendingCompletions((prev) => {
          const next = new Set(prev);
          next.delete(taskId);
          return next;
        });
        undoTimerRef.current.delete(taskId);
      }, 5000);

      undoTimerRef.current.set(taskId, timer);

      toast('Task completed', {
        action: {
          label: 'Undo',
          onClick: () => {
            clearTimeout(timer);
            undoTimerRef.current.delete(taskId);
            setPendingCompletions((prev) => {
              const next = new Set(prev);
              next.delete(taskId);
              return next;
            });
          },
        },
        duration: 5000,
      });
    },
    [completeTask],
  );

  const handleUncomplete = useCallback(
    async (taskId: string) => {
      try {
        await uncompleteTask(taskId);
      } catch {
        toast.error('Failed to reopen task');
      }
    },
    [uncompleteTask],
  );

  const handleReschedule = useCallback(
    async (taskId: string, date: string) => {
      try {
        await updateTask(taskId, { due_date: date });
        toast.success('Task rescheduled');
        loadData();
      } catch {
        toast.error('Failed to reschedule');
      }
    },
    [updateTask, loadData],
  );

  const handleTaskClick = useCallback(
    (task: Task) => {
      setSelectedTask(task);
      setEditorOpen(true);
    },
    [setSelectedTask, setEditorOpen],
  );

  // Filter out pending completions from active task lists
  const filterPending = (tasks: Task[]) =>
    tasks.filter((t) => !pendingCompletions.has(t.id));

  const activeOverdue = filterPending(
    overdueTasks.filter((t) => t.status !== 'done'),
  );
  const activeToday = filterPending(
    todayTasks.filter((t) => t.status !== 'done'),
  );
  const activeUpcoming = filterPending(
    upcomingTasks.filter(
      (t) =>
        t.status !== 'done' &&
        !activeToday.some((at) => at.id === t.id) &&
        !activeOverdue.some((ao) => ao.id === t.id),
    ),
  );

  // Group upcoming by day
  const upcomingByDay = activeUpcoming.reduce<Record<string, Task[]>>(
    (acc, task) => {
      const key = task.due_date || 'No date';
      if (!acc[key]) acc[key] = [];
      acc[key].push(task);
      return acc;
    },
    {},
  );

  // Completed today count (tasks + habits)
  const completedTasksToday =
    todayTasks.filter((t) => t.status === 'done').length +
    pendingCompletions.size;
  const totalTasksToday = todayTasks.length + overdueTasks.length;
  const habitProgress = getTodayProgress();
  const completedToday = completedTasksToday + habitProgress.completed;
  const totalToday = totalTasksToday + habitProgress.total;
  const progressPct =
    totalToday > 0 ? Math.round((completedToday / totalToday) * 100) : 0;

  const isEmpty =
    activeOverdue.length === 0 &&
    activeToday.length === 0 &&
    activeUpcoming.length === 0;

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl">
      {/* Header */}
      <div className="mb-6 flex items-center gap-3">
        <Sun className="h-7 w-7 text-[var(--color-accent)]" />
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          Today
        </h1>
        <span className="text-sm text-[var(--color-text-secondary)]">
          {format(parseISO(settingsStartOfDay.todayIso), 'EEEE, MMMM d')}
        </span>
      </div>

      <BoundaryTodayBanner />
      <TodayBalanceBar />
      <SelfCareNudgeCard />
      <MorningCheckInCard />
      <TodayLeisureMinimumRow />
      <SchoolworkTodayCard />

      {/* AI Briefing teaser — hidden by default respect setting */}
      {settingsShowBriefing && (
        <button
          onClick={() => navigate('/briefing')}
          className="mb-4 flex w-full items-start gap-3 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4 text-left transition-colors hover:border-[var(--color-accent)]/50 hover:bg-[var(--color-bg-secondary)]"
          aria-label="Open daily briefing"
        >
          <SparklesIcon
            className="mt-0.5 h-5 w-5 shrink-0 text-[var(--color-accent)]"
            aria-hidden="true"
          />
          <span className="flex-1">
            <span className="block text-sm font-semibold text-[var(--color-text-primary)]">
              Today's Briefing
            </span>
            <span className="block text-xs text-[var(--color-text-secondary)]">
              One-line summary + top priorities, heads-up items, and suggested
              order — generated from your open tasks and habits.
            </span>
          </span>
          <ChevronRight
            className="mt-0.5 h-4 w-4 shrink-0 text-[var(--color-text-secondary)]"
            aria-hidden="true"
          />
        </button>
      )}

      {/* Progress Header */}
      <div className="mb-6 flex items-center gap-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3">
        {/* Progress ring */}
        <div className="relative h-12 w-12 shrink-0">
          <svg className="h-12 w-12 -rotate-90" viewBox="0 0 48 48">
            <circle
              cx="24"
              cy="24"
              r="20"
              fill="none"
              stroke="var(--color-bg-secondary)"
              strokeWidth="4"
            />
            <circle
              cx="24"
              cy="24"
              r="20"
              fill="none"
              stroke="var(--color-accent)"
              strokeWidth="4"
              strokeLinecap="round"
              strokeDasharray={`${(progressPct / 100) * 125.6} 125.6`}
              className="transition-all duration-500"
            />
          </svg>
          <span className="absolute inset-0 flex items-center justify-center text-xs font-bold text-[var(--color-text-primary)]">
            {progressPct}%
          </span>
        </div>
        <div className="flex-1">
          <p className="text-sm font-medium text-[var(--color-text-primary)]">
            {completedToday} of {totalToday} completed today
            {habitProgress.total > 0 && (
              <span className="text-[var(--color-text-secondary)]">
                {' '}({completedTasksToday} tasks, {habitProgress.completed} habits)
              </span>
            )}
          </p>
          <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-[var(--color-bg-secondary)]">
            <div
              className="h-full rounded-full bg-[var(--color-accent)] transition-all duration-500"
              style={{ width: `${progressPct}%` }}
            />
          </div>
        </div>
        {summary && (
          <div className="hidden gap-4 text-center sm:flex">
            <div>
              <p className="text-lg font-bold text-red-500">
                {summary.overdue_tasks}
              </p>
              <p className="text-xs text-[var(--color-text-secondary)]">
                Overdue
              </p>
            </div>
            <div>
              <p className="text-lg font-bold text-[var(--color-accent)]">
                {summary.today_tasks}
              </p>
              <p className="text-xs text-[var(--color-text-secondary)]">
                Today
              </p>
            </div>
            <div>
              <p className="text-lg font-bold text-[var(--color-text-primary)]">
                {summary.upcoming_tasks}
              </p>
              <p className="text-xs text-[var(--color-text-secondary)]">
                Upcoming
              </p>
            </div>
          </div>
        )}
      </div>

      {/* Habit Chips */}
      {habits.length > 0 && (
        <div className="mb-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3">
          <div className="mb-2 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Activity className="h-4 w-4 text-[var(--color-accent)]" />
              <span className="text-xs font-semibold text-[var(--color-text-primary)]">
                Today's Habits
              </span>
            </div>
            <button
              onClick={() => navigate('/habits')}
              className="text-xs text-[var(--color-accent)] hover:underline"
            >
              View All
            </button>
          </div>
          <div className="flex gap-2 overflow-x-auto pb-1">
            {habits
              .filter((h) => h.is_active)
              .map((habit) => {
                const completed = isTodayCompleted(habit.id);
                const habitColor = habit.color || 'var(--color-accent)';
                const count = getTodayCount(habit.id);
                return (
                  <button
                    key={habit.id}
                    onClick={async () => {
                      const today = format(new Date(), 'yyyy-MM-dd');
                      try {
                        await toggleCompletion(habit.id, today);
                      } catch {
                        toast.error('Failed to update habit');
                      }
                    }}
                    className={`flex shrink-0 items-center gap-2 rounded-full border px-3 py-1.5 text-xs font-medium transition-all ${
                      completed
                        ? 'border-transparent text-white'
                        : 'border-[var(--color-border)] text-[var(--color-text-primary)] hover:border-[var(--color-accent)]/50'
                    }`}
                    style={
                      completed
                        ? { backgroundColor: habitColor }
                        : undefined
                    }
                  >
                    <span>{habit.icon || '🎯'}</span>
                    <span className="max-w-[100px] truncate">
                      {habit.name}
                    </span>
                    {habit.target_count > 1 && (
                      <span
                        className={`rounded-full px-1.5 py-0.5 text-[10px] ${
                          completed
                            ? 'bg-white/20'
                            : 'bg-[var(--color-bg-secondary)]'
                        }`}
                      >
                        {count}/{habit.target_count}
                      </span>
                    )}
                    {completed && habit.target_count <= 1 && (
                      <svg
                        className="h-3 w-3"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                        strokeWidth={3}
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          d="M5 13l4 4L19 7"
                        />
                      </svg>
                    )}
                  </button>
                );
              })}
          </div>
        </div>
      )}

      {/* Weekly Habit Summary Banner */}
      <WeeklyHabitSummary />

      {/* Daily Essentials — medication time slots */}
      <section className="flex flex-col gap-2">
        <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">
          Medications
        </h3>
        <MedicationSlotList />
      </section>

      {/* Empty state */}
      {isEmpty && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] py-16 text-center">
          <PartyPopper className="mb-4 h-16 w-16 text-[var(--color-accent)]" />
          <h3 className="text-xl font-semibold text-[var(--color-text-primary)]">
            All Caught Up!
          </h3>
          <p className="mt-2 text-sm text-[var(--color-text-secondary)]">
            You have no pending tasks. Enjoy your day!
          </p>
        </div>
      )}

      {/* Overdue Section */}
      {activeOverdue.length > 0 && (
        <TaskSection
          title="Overdue"
          count={activeOverdue.length}
          accentColor="#ef4444"
          collapsed={!!collapsed['overdue']}
          onToggle={() => toggleSection('overdue')}
        >
          {activeOverdue
            .sort((a, b) => (b.urgency_score ?? 0) - (a.urgency_score ?? 0))
            .map((task) => (
              <TaskRow
                key={task.id}
                task={task}
                onComplete={handleComplete}
                onUncomplete={handleUncomplete}
                onClick={handleTaskClick}
                onReschedule={handleReschedule}
                showProject
                projectName={projectMap.get(task.project_id)?.title}
                projectColor={undefined}
              />
            ))}
        </TaskSection>
      )}

      {/* Today Section */}
      {activeToday.length > 0 && (
        <TaskSection
          title="Today"
          count={activeToday.length}
          accentColor="var(--color-accent)"
          collapsed={!!collapsed['today']}
          onToggle={() => toggleSection('today')}
        >
          {activeToday
            .sort(
              (a, b) =>
                a.priority - b.priority ||
                (b.urgency_score ?? 0) - (a.urgency_score ?? 0),
            )
            .map((task) => (
              <TaskRow
                key={task.id}
                task={task}
                onComplete={handleComplete}
                onUncomplete={handleUncomplete}
                onClick={handleTaskClick}
                onReschedule={handleReschedule}
                showProject
                projectName={projectMap.get(task.project_id)?.title}
                projectColor={undefined}
              />
            ))}
        </TaskSection>
      )}

      {/* Upcoming Section */}
      {activeUpcoming.length > 0 && (
        <TaskSection
          title="Upcoming"
          count={activeUpcoming.length}
          accentColor="var(--color-text-secondary)"
          collapsed={!!collapsed['upcoming']}
          onToggle={() => toggleSection('upcoming')}
        >
          {Object.entries(upcomingByDay)
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([dateKey, tasks]) => (
              <div key={dateKey}>
                <div className="px-3 py-1.5 text-xs font-medium text-[var(--color-text-secondary)]">
                  {dateKey !== 'No date'
                    ? format(parseISO(dateKey), 'EEEE, MMMM d')
                    : 'No Date'}
                </div>
                {tasks
                  .sort(
                    (a, b) =>
                      a.priority - b.priority ||
                      (b.urgency_score ?? 0) - (a.urgency_score ?? 0),
                  )
                  .map((task) => (
                    <TaskRow
                      key={task.id}
                      task={task}
                      onComplete={handleComplete}
                      onUncomplete={handleUncomplete}
                      onClick={handleTaskClick}
                      onReschedule={handleReschedule}
                      showProject
                      projectName={projectMap.get(task.project_id)?.title}
                      projectColor={undefined}
                    />
                  ))}
              </div>
            ))}
        </TaskSection>
      )}

      {/* Task Editor Drawer */}
      {editorOpen && (
        <TaskEditor
          onClose={() => {
            setEditorOpen(false);
            setSelectedTask(null);
          }}
          onUpdate={() => loadData()}
        />
      )}
    </div>
  );
}

// Weekly Habit Summary Banner (shown on Sunday/Monday)
function WeeklyHabitSummary() {
  const navigate = useNavigate();
  const { habits, completions } = useHabitStore();
  const today = new Date();

  // Only show on Sunday or Monday
  if (!isSunday(today) && !isMonday(today)) return null;
  if (habits.length === 0) return null;

  // Calculate last week's stats
  const lastWeekStart = startOfWeek(subDays(today, 7), { weekStartsOn: 1 });
  const lastWeekEnd = subDays(
    startOfWeek(today, { weekStartsOn: 1 }),
    1,
  );

  let totalDue = 0;
  let totalCompleted = 0;

  for (const habit of habits) {
    if (!habit.is_active) continue;
    const habitCompletions = completions[habit.id] || [];

    if (habit.frequency === 'weekly') {
      totalDue++;
      let weekCount = 0;
      for (const c of habitCompletions) {
        const d = parseISO(c.date);
        if (d >= lastWeekStart && d <= lastWeekEnd) weekCount += c.count;
      }
      if (weekCount >= habit.target_count) totalCompleted++;
    } else {
      // Daily: count active days in the week
      for (let i = 0; i < 7; i++) {
        const day = new Date(lastWeekStart);
        day.setDate(lastWeekStart.getDate() + i);
        const activeDays = habit.active_days_json
          ? JSON.parse(habit.active_days_json)
          : null;
        const jsDay = day.getDay();
        const isoDay = jsDay === 0 ? 7 : jsDay;
        if (activeDays && !activeDays.includes(isoDay)) continue;
        totalDue++;
        const dateStr = format(day, 'yyyy-MM-dd');
        const completion = habitCompletions.find((c) => c.date === dateStr);
        if ((completion?.count || 0) >= habit.target_count) totalCompleted++;
      }
    }
  }

  if (totalDue === 0) return null;
  const pct = Math.round((totalCompleted / totalDue) * 100);

  return (
    <div className="mb-4 flex items-center gap-3 rounded-xl border border-[var(--color-accent)]/20 bg-[var(--color-accent)]/5 px-4 py-3">
      <BarChart3 className="h-5 w-5 shrink-0 text-[var(--color-accent)]" />
      <p className="flex-1 text-sm text-[var(--color-text-primary)]">
        Last week: You completed{' '}
        <span className="font-semibold">
          {totalCompleted}/{totalDue}
        </span>{' '}
        habit check-ins ({pct}%)
      </p>
      <button
        onClick={() => navigate('/habits')}
        className="shrink-0 text-xs font-medium text-[var(--color-accent)] hover:underline"
      >
        View Details
      </button>
    </div>
  );
}

// Collapsible section component
function TaskSection({
  title,
  count,
  accentColor,
  collapsed,
  onToggle,
  children,
}: {
  title: string;
  count: number;
  accentColor: string;
  collapsed: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="mb-4 overflow-hidden rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)]">
      <button
        onClick={onToggle}
        className="flex w-full items-center gap-2 px-4 py-3 text-left transition-colors hover:bg-[var(--color-bg-secondary)]"
      >
        {collapsed ? (
          <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" />
        ) : (
          <ChevronDown className="h-4 w-4 text-[var(--color-text-secondary)]" />
        )}
        <span
          className="h-2 w-2 rounded-full"
          style={{ backgroundColor: accentColor }}
        />
        <span className="text-sm font-semibold text-[var(--color-text-primary)]">
          {title}
        </span>
        <span className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-xs font-medium text-[var(--color-text-secondary)]">
          {count}
        </span>
      </button>
      {!collapsed && <div className="px-1 pb-2">{children}</div>}
    </div>
  );
}

