import { useEffect, useState, useCallback, useRef, useMemo } from 'react';
import {
  Sun,
  ChevronDown,
  ChevronRight,
  PartyPopper,
  BarChart3,
  Pin,
  Timer,
  MessageSquare,
  Zap,
} from 'lucide-react';
import { format, parseISO, startOfWeek, subDays, isMonday, isSunday } from 'date-fns';
import { toast } from 'sonner';
import { useNavigate, Link } from 'react-router-dom';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import { useHabitStore } from '@/stores/habitStore';
import { useTaskDependencyStore } from '@/stores/taskDependencyStore';
import { buildUnmetBlockerCountMap } from '@/features/tasks/dependencyHelpers';
import { dashboardApi } from '@/api/dashboard';
import { TaskRow } from '@/components/shared/TaskRow';
import { Spinner } from '@/components/ui/Spinner';
import type { Task } from '@/types/task';
import { lazy, Suspense } from 'react';

const TaskEditor = lazy(() => import('@/features/tasks/TaskEditor'));
import type { DashboardSummary } from '@/types/api';
import { MorningCheckInCard } from '@/features/checkin/MorningCheckInCard';
import { BoundaryTodayBanner } from '@/features/boundaries/BoundaryTodayBanner';
import { SelfCareNudgeCard } from '@/features/today/SelfCareNudgeCard';
import { TodayLeisureMinimumRow } from '@/features/today/TodayLeisureMinimumRow';
import { HabitsSection } from '@/features/today/HabitsSection';
import { BurnoutBadgeSection } from '@/features/today/BurnoutBadgeSection';
import { SelfCareRoutineSection } from '@/features/today/SelfCareRoutineSection';
import { TodayBalanceBar } from '@/features/today/TodayBalanceBar';
import { DailyEssentialsCards } from '@/features/today/DailyEssentialsCards';
import { PlanForTodaySheet } from '@/features/today/PlanForTodaySheet';
import { DoneCounterSheet } from '@/features/today/DoneCounterSheet';
import { MorningCheckInBanner } from '@/features/today/MorningCheckInBanner';
import { RestDayBanner } from '@/features/today/RestDayBanner';
import { useRestDayStore } from '@/stores/restDayStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { useDashboardStore } from '@/stores/dashboardStore';
import { Sparkles as SparklesIcon } from 'lucide-react';
import { useLogicalToday } from '@/utils/useLogicalToday';
import { isMetaBuiltInHabit } from '@/utils/metaBuiltInHabit';

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
    tasks: allTasks,
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

  // Live edge set from Firestore listener wired in `useFirestoreSync`.
  const dependencies = useTaskDependencyStore((s) => s.dependencies);

  const { projects, fetchAllProjects } = useProjectStore();

  // Habits — only `fetchHabits` + progress numbers are read here. Row
  // rendering lives in `<HabitsSection />`, which subscribes directly
  // to the store with stable Zustand selectors.
  const fetchHabits = useHabitStore((s) => s.fetchHabits);
  const getTodayProgress = useHabitStore((s) => s.getTodayProgress);
  // Subscribe to completions so progress updates when a habit is checked off
  useHabitStore((s) => s.completions);

  // Rest-Day primitive (`docs/REST_DAY.md`). When today is marked as a
  // rest day we replace the dense task list with a soft takeover banner;
  // the rest of the screen scaffolding (header date, balance bar, etc.)
  // stays so the user can still tap into other surfaces. The dashboard
  // sections, plan-more affordance, and "Show Blocked Tasks" toggle —
  // i.e. everything that nags about uncompleted work — drop out.
  const isRestingToday = useRestDayStore((s) => s.isRestDayToday());
  const lowEnergyModeEnabled = useRestDayStore((s) => s.lowEnergyModeEnabled);
  const setLowEnergyModeEnabled = useRestDayStore(
    (s) => s.setLowEnergyModeEnabled,
  );

  const settingsShowBriefing = useSettingsStore((s) => s.showBriefingCard);
  const settingsStartOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  // Cross-device dashboard order + per-section visibility (parity C.1f).
  // The Settings → Dashboard subsection writes here; the Firestore
  // listener wired in `useFirestoreSync` keeps it in sync with
  // Android's `DashboardPreferences` DataStore.
  const dashboardSectionOrder = useDashboardStore((s) => s.sectionOrder);
  const dashboardHiddenSections = useDashboardStore((s) => s.hiddenSections);
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
  const [planSheetOpen, setPlanSheetOpen] = useState(false);
  const [doneSheetOpen, setDoneSheetOpen] = useState(false);
  // "Show blocked tasks" filter (parity B.12). Default false — mirrors
  // Android's blocked-task hide behaviour on the Today screen so users
  // aren't nagged about work they can't act on yet.
  const [showBlocked, setShowBlocked] = useState(false);
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

  // One-pass blocker count map shared by the filter + per-row chip.
  // Recomputed only when `dependencies` or `allTasks` change.
  const blockerCounts = useMemo(
    () => buildUnmetBlockerCountMap(dependencies, allTasks),
    [dependencies, allTasks],
  );
  const unmetBlockerCount = (taskId: string): number =>
    blockerCounts.get(taskId) ?? 0;

  // Hide tasks blocked by unmet dependencies unless the user opts in.
  const blockedAwareFilter = (t: Task) =>
    showBlocked || unmetBlockerCount(t.id) === 0;

  const activeOverdue = filterPending(
    overdueTasks.filter(
      (t) =>
        t.status !== 'done' &&
        blockedAwareFilter(t) &&
        (!lowEnergyModeEnabled || t.cognitive_load === 'EASY'),
    ),
  );
  const activeToday = filterPending(
    todayTasks.filter(
      (t) =>
        t.status !== 'done' &&
        blockedAwareFilter(t) &&
        (!lowEnergyModeEnabled || t.cognitive_load === 'EASY'),
    ),
  );
  const activeUpcoming = filterPending(
    upcomingTasks.filter(
      (t) =>
        t.status !== 'done' &&
        blockedAwareFilter(t) &&
        !activeToday.some((at) => at.id === t.id) &&
        !activeOverdue.some((ao) => ao.id === t.id),
    ),
  );

  // Count of currently-hidden blocked tasks — surfaced in the toggle
  // label so users can see *why* the toggle exists at a glance.
  let hiddenBlockedCount = 0;
  if (!showBlocked) {
    const seen = new Set<string>();
    for (const t of [...overdueTasks, ...todayTasks, ...upcomingTasks]) {
      if (seen.has(t.id)) continue;
      seen.add(t.id);
      if (t.status !== 'done' && unmetBlockerCount(t.id) > 0) {
        hiddenBlockedCount++;
      }
    }
  }

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
        {!isRestingToday && (
          <button
            type="button"
            onClick={() => setPlanSheetOpen(true)}
            className="ml-auto inline-flex items-center gap-1 rounded-full border border-[var(--color-border)] px-3 py-1 text-xs font-medium text-[var(--color-text-primary)] hover:border-[var(--color-accent)]/50 hover:bg-[var(--color-bg-secondary)]"
            aria-label="Plan Today"
            data-testid="plan-today-button"
          >
            <Pin className="h-3.5 w-3.5 text-[var(--color-accent)]" />
            Plan Today
          </button>
        )}
      </div>

      {/* Quick-actions chip row — parity with Android
          `TodayScreen.kt:521–700`. Horizontally scrollable so it scales
          on small screens. Hidden on rest days. */}
      {!isRestingToday && (
        <div
          className="mb-4 flex items-center gap-2 overflow-x-auto pb-1"
          role="toolbar"
          aria-label="Quick Actions"
        >
          <button
            type="button"
            onClick={() => setLowEnergyModeEnabled(!lowEnergyModeEnabled)}
            className={`inline-flex shrink-0 items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors duration-200 ${
              lowEnergyModeEnabled
                ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                : 'border-[var(--color-border)] text-[var(--color-text-primary)] hover:border-[var(--color-accent)]/50 hover:bg-[var(--color-bg-secondary)]'
            }`}
            aria-pressed={lowEnergyModeEnabled}
            data-testid="low-energy-chip"
          >
            <Zap className="h-3.5 w-3.5" aria-hidden="true" />
            {lowEnergyModeEnabled ? 'Low Energy On' : 'Low Energy'}
          </button>
        </div>
      )}

      <MorningCheckInBanner />
      <BoundaryTodayBanner />
      <TodayBalanceBar />
      <SelfCareNudgeCard />
      <MorningCheckInCard />
      <TodayLeisureMinimumRow />

      {/* Rest-Day primitive. The banner renders both states (takeover
          when resting; small mark-as-rest affordance when not). Tasks
          stay in Room either way — see `docs/REST_DAY.md` § *The core
          rule*. */}
      <RestDayBanner />

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

      {/* Dashboard sections render in user-configured order (parity
          C.1f). Hidden sections drop out entirely. Always-on cards
          above (mood/balance/check-in/etc.) are non-dashboard
          surfaces and stay above the reorderable list.

          On a rest day the dashboard sections drop out — the soft
          takeover banner replaces the dense task list per
          `docs/REST_DAY.md` § *The core rule* ("the Today screen
          replaces the dense list with a soft header"). */}
      {!isRestingToday && dashboardSectionOrder.map((sectionKey) => {
        if (dashboardHiddenSections.includes(sectionKey)) return null;
        switch (sectionKey) {
          case 'progress':
            return (
              <ProgressSection
                key={sectionKey}
                progressPct={progressPct}
                completedToday={completedToday}
                totalToday={totalToday}
                completedTasksToday={completedTasksToday}
                habitProgress={habitProgress}
                summary={summary}
                onCounterClick={() => setDoneSheetOpen(true)}
              />
            );
          case 'daily_essentials':
            return <DailyEssentialsCards key={sectionKey} />;
          case 'burnout_badge':
            // Self-silencing — hides itself when score is calm. The
            // tap routes to /settings so the user can adjust boundaries.
            return <BurnoutBadgeSection key={sectionKey} />;
          case 'self_care_nudge':
            // Routine prompt (morning/bedtime) — distinct from the
            // burnout-driven `<SelfCareNudgeCard />` above the dashboard
            // sections, which rotates by burnout signals.
            return <SelfCareRoutineSection key={sectionKey} />;
          case 'habits':
            // Row-based habits section (parity with Android's
            // `TodayHabitCheckItem`). The chip-style fallback below is
            // retained for callers that haven't migrated yet — currently
            // unused but kept compile-safe.
            return <HabitsSection key={sectionKey} />;
          case 'overdue':
            return activeOverdue.length > 0 ? (
              <TaskSection
                key={sectionKey}
                title="Overdue"
                count={activeOverdue.length}
                accentColor="#ef4444"
                collapsed={!!collapsed['overdue']}
                onToggle={() => toggleSection('overdue')}
              >
                {activeOverdue
                  .sort(
                    (a, b) => (b.urgency_score ?? 0) - (a.urgency_score ?? 0),
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
                      blockedByCount={unmetBlockerCount(task.id)}
                      onBlockerChipClick={handleTaskClick}
                    />
                  ))}
              </TaskSection>
            ) : null;
          case 'today_tasks':
            return activeToday.length > 0 ? (
              <TaskSection
                key={sectionKey}
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
                      blockedByCount={unmetBlockerCount(task.id)}
                      onBlockerChipClick={handleTaskClick}
                    />
                  ))}
              </TaskSection>
            ) : null;
          case 'plan_more':
            return (
              <PlanMoreSection
                key={sectionKey}
                onClick={() => setPlanSheetOpen(true)}
              />
            );
          case 'completed':
            // Web doesn't currently expose a Completed-today list on
            // Today (the done count lives in the progress header).
            // Hidden by default; the key still round-trips so Android
            // can show it without overwriting web state.
            return null;
          default:
            return null;
        }
      })}

      {/* Weekly Habit Summary Banner — non-dashboard, always shown */}
      <WeeklyHabitSummary />

      {/* "Show Blocked Tasks" toggle (parity B.12). Non-dashboard; only
          renders when there's something to reveal — keeps the chrome
          quiet on days when nothing is blocked. Hidden on rest days so
          the takeover banner isn't trailed by task-shaped chrome. */}
      {!isRestingToday && (hiddenBlockedCount > 0 || showBlocked) && (
        <div className="mb-3 flex items-center justify-end">
          <label className="inline-flex cursor-pointer items-center gap-2 text-xs text-[var(--color-text-secondary)]">
            <input
              type="checkbox"
              checked={showBlocked}
              onChange={(e) => setShowBlocked(e.target.checked)}
              className="h-3.5 w-3.5 rounded border-[var(--color-border)] text-[var(--color-accent)]"
            />
            Show Blocked Tasks
            {!showBlocked && hiddenBlockedCount > 0 && (
              <span className="rounded-full bg-[var(--color-bg-secondary)] px-1.5 py-0.5 text-[10px] font-medium text-[var(--color-text-secondary)]">
                {hiddenBlockedCount} Hidden
              </span>
            )}
          </label>
        </div>
      )}

      {/* Empty state — hidden on rest days; the takeover banner is its
          own copy and an extra "All Caught Up" card would muddy the
          message. */}
      {!isRestingToday && isEmpty && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] py-16 px-6 text-center">
          <PartyPopper className="mb-4 h-16 w-16 text-[var(--color-accent)] transition-transform duration-300 prism-pop-hover cursor-pointer" />
          <h3 className="text-xl font-semibold text-[var(--color-text-primary)]">
            All Caught Up!
          </h3>
          <p className="mt-2 mb-6 text-sm text-[var(--color-text-secondary)] max-w-sm">
            You have no pending tasks. Take a moment to restore your energy or explore these activities:
          </p>
          
          <div className="flex flex-wrap items-center justify-center gap-3">
            <Link
              to="/pomodoro"
              className="inline-flex items-center gap-1.5 rounded-full border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-2 text-xs font-semibold text-[var(--color-text-primary)] shadow-sm prism-interactive transition-colors hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]"
            >
              <Timer className="h-4 w-4 text-[var(--color-accent)]" />
              Focus Session
            </Link>
            <Link
              to="/chat"
              className="inline-flex items-center gap-1.5 rounded-full border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-2 text-xs font-semibold text-[var(--color-text-primary)] shadow-sm prism-interactive transition-colors hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]"
            >
              <MessageSquare className="h-4 w-4 text-[var(--color-accent)]" />
              AI Assistant
            </Link>
            <Link
              to="/balance/weekly-report"
              className="inline-flex items-center gap-1.5 rounded-full border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-2 text-xs font-semibold text-[var(--color-text-primary)] shadow-sm prism-interactive transition-colors hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]"
            >
              <BarChart3 className="h-4 w-4 text-[var(--color-accent)]" />
              Weekly Balance
            </Link>
          </div>
        </div>
      )}

      {/* Upcoming Section — non-dashboard (no Android counterpart key).
          Hidden on rest days so the takeover banner isn't followed by a
          list of looming work. */}
      {!isRestingToday && activeUpcoming.length > 0 && (
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
                      blockedByCount={unmetBlockerCount(task.id)}
                      onBlockerChipClick={handleTaskClick}
                    />
                  ))}
              </div>
            ))}
        </TaskSection>
      )}

      {/* Task Editor Drawer */}
      <Suspense fallback={null}>
        {editorOpen && (
          <TaskEditor
            onClose={() => {
              setEditorOpen(false);
              setSelectedTask(null);
            }}
            onUpdate={() => loadData()}
          />
        )}
      </Suspense>

      {/* Plan For Today Sheet */}
      <PlanForTodaySheet
        isOpen={planSheetOpen}
        onClose={() => setPlanSheetOpen(false)}
        todayIso={settingsStartOfDay.todayIso}
      />

      {/* Done Counter Sheet — opened by tapping the progress counter */}
      <DoneCounterSheet
        isOpen={doneSheetOpen}
        onClose={() => setDoneSheetOpen(false)}
      />
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
    // Skip the six built-in meta-habits — they're surfaced via dedicated
    // Today cards, not as habit check-ins. Keeps the weekly summary
    // count consistent with the Today habit section and Habits screen.
    if (isMetaBuiltInHabit(habit)) continue;
    const habitCompletions = completions[habit.id] || [];

    if (habit.frequency !== 'daily') {
      // Treat every non-daily frequency as a single-bucket "did the
      // user hit target in last week?" check. Matches the weekly-style
      // surfacing used elsewhere on web (see `habitStore.getTodayProgress`
      // / `streaks.ts`); period-aware windows for fortnightly/monthly/
      // bimonthly/quarterly are a follow-up.
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

/**
 * Progress card — top dashboard slot. Mirrors the original inline JSX
 * 1:1; extracted only so it can be ordered/hidden by the dashboard
 * preferences (parity C.1f).
 */
function ProgressSection({
  progressPct,
  completedToday,
  totalToday,
  completedTasksToday,
  habitProgress,
  summary,
  onCounterClick,
}: {
  progressPct: number;
  completedToday: number;
  totalToday: number;
  completedTasksToday: number;
  habitProgress: { completed: number; total: number };
  summary: DashboardSummary | null;
  onCounterClick: () => void;
}) {
  return (
    <div className="mb-6 flex items-center gap-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3">
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
        <button
          type="button"
          onClick={onCounterClick}
          className="text-left text-sm font-medium text-[var(--color-text-primary)] hover:underline focus:outline-none"
          aria-label="Open Completed Today List"
          data-testid="done-counter-button"
        >
          {completedToday} Of {totalToday} Completed Today
          {habitProgress.total > 0 && (
            <span className="text-[var(--color-text-secondary)]">
              {' '}({completedTasksToday} Tasks, {habitProgress.completed} Habits)
            </span>
          )}
        </button>
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
            <p className="text-xs text-[var(--color-text-secondary)]">Overdue</p>
          </div>
          <div>
            <p className="text-lg font-bold text-[var(--color-accent)]">
              {summary.today_tasks}
            </p>
            <p className="text-xs text-[var(--color-text-secondary)]">Today</p>
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
  );
}

/**
 * Plan-more card — opens the PlanForTodaySheet. Extracted from the
 * inline JSX so the dashboard ordering switch can place it
 * arbitrarily.
 */
function PlanMoreSection({ onClick }: { onClick: () => void }) {
  return (
    <div className="mb-4">
      <button
        onClick={onClick}
        className="flex w-full items-center gap-3 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3 text-left transition-colors hover:border-[var(--color-accent)]/50 hover:bg-[var(--color-bg-secondary)]"
        aria-label="Open plan for today sheet"
      >
        <Pin
          className="h-5 w-5 shrink-0 text-[var(--color-accent)]"
          aria-hidden="true"
        />
        <span className="flex-1">
          <span className="block text-sm font-semibold text-[var(--color-text-primary)]">
            Plan For Today
          </span>
          <span className="block text-xs text-[var(--color-text-secondary)]">
            Pull undone tasks into today's plan in one batch.
          </span>
        </span>
        <ChevronRight
          className="h-4 w-4 shrink-0 text-[var(--color-text-secondary)]"
          aria-hidden="true"
        />
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

