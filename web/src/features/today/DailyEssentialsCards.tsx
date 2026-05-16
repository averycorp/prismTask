import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Circle,
  Coffee,
  Home,
  MoonStar,
  Sparkles,
  Sun,
} from 'lucide-react';
import { toast } from 'sonner';
import { useSelfCareStore } from '@/stores/selfCareStore';
import { useHabitStore } from '@/stores/habitStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { logicalToday, startOfLogicalDayMs } from '@/utils/dayBoundary';
import { parseCompletedStepsForDisplay } from '@/api/firestore/selfCare';
import {
  getDailyEssentialsPreferences,
  subscribeToDailyEssentialsPreferences,
  setHasSeenHint,
  type DailyEssentialsSnapshot,
  DEFAULT_DAILY_ESSENTIALS,
} from '@/api/firestore/dailyEssentialsPreferences';
import { getFirebaseUid } from '@/stores/firebaseUid';
import type { SelfCareLog, SelfCareStep } from '@/api/firestore/selfCare';
import type { Habit } from '@/types/habit';

/**
 * Daily Essentials section — web port of Android's
 * `DailyEssentialsSection.kt` and `DailyEssentialsUseCase.kt` (parity
 * unit 6 of 23, Today screen A).
 *
 * Card order mirrors Android:
 *   1. Morning routine (self-care steps)
 *   2. Housework habit (user-pinned habit)
 *   3. Housework routine (self-care steps)
 *   4. Bedtime routine (self-care steps)
 *
 * Cards hide individually when empty. The whole section collapses to a
 * single "Set Up Your Daily Essentials" hint when nothing is configured
 * AND the user has not dismissed the hint via `has_seen_hint`.
 *
 * Schoolwork is intentionally NOT included here — it already renders
 * inline above the dashboard sections via the standalone
 * `SchoolworkTodayCard`, which mirrors Android's `SchoolworkTodayCard`
 * shape with assignments-due grouping. Duplicating it would split the
 * source of truth.
 *
 * Title Capitalization per CLAUDE.md user-facing strings convention.
 */
export function DailyEssentialsCards() {
  const navigate = useNavigate();
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const selfCareLogs = useSelfCareStore((s) => s.logs);
  const selfCareSteps = useSelfCareStore((s) => s.steps);
  const toggleSelfCareStep = useSelfCareStore((s) => s.toggleStep);

  const habits = useHabitStore((s) => s.habits);
  const toggleHabitCompletion = useHabitStore((s) => s.toggleCompletion);
  const isHabitDoneToday = useHabitStore((s) => s.isTodayCompleted);

  const [prefs, setPrefs] = useState<DailyEssentialsSnapshot>(
    DEFAULT_DAILY_ESSENTIALS,
  );
  const [expanded, setExpanded] = useState<boolean>(true);

  const uid = useMemo(() => {
    try {
      return getFirebaseUid();
    } catch {
      return null;
    }
  }, []);

  // Cross-device pref sync. Best-effort; defaults keep the section
  // functional offline / signed out.
  useEffect(() => {
    if (!uid) return;
    let cancelled = false;
    getDailyEssentialsPreferences(uid)
      .then((p) => {
        if (!cancelled) setPrefs(p);
      })
      .catch(() => {
        // Silent fallback — defaults are sensible.
      });
    const unsub = subscribeToDailyEssentialsPreferences(uid, setPrefs);
    return () => {
      cancelled = true;
      unsub();
    };
  }, [uid]);

  // Self-care routine logical-day natural key. Matches Android's
  // `startOfCurrentDay(startOfDay)` so a web-side toggle merges onto
  // the same (routine_type, date) row Android wrote.
  const todayMs = useMemo(
    // eslint-disable-next-line react-hooks/purity -- parity batch follow-up; see #1573
    () => startOfLogicalDayMs(Date.now(), startOfDayHour),
    [startOfDayHour],
  );

  const morningCard = useMemo(
    () => buildRoutineCard('morning', selfCareSteps, selfCareLogs, todayMs),
    [selfCareSteps, selfCareLogs, todayMs],
  );
  const bedtimeCard = useMemo(
    () => buildRoutineCard('bedtime', selfCareSteps, selfCareLogs, todayMs),
    [selfCareSteps, selfCareLogs, todayMs],
  );
  const houseworkRoutineCard = useMemo(
    () => buildRoutineCard('housework', selfCareSteps, selfCareLogs, todayMs),
    [selfCareSteps, selfCareLogs, todayMs],
  );

  // Housework habit card — the user pins one habit as the housework
  // pointer (Android's `housework_habit_id`). When unset, fall back to
  // the first habit whose name contains "house" or "clean" so the card
  // surfaces something useful for users who haven't configured it yet.
  const houseworkHabit = useMemo(
    () =>
      pickHouseworkHabit(habits, prefs.houseworkHabitId),
    [habits, prefs.houseworkHabitId],
  );

  const visibleCards: VisibleCard[] = useMemo(() => {
    const cards: VisibleCard[] = [];
    if (prefs.showMorningRoutine && morningCard) {
      cards.push({ kind: 'morning', card: morningCard });
    }
    if (prefs.showHouseworkHabit && houseworkHabit) {
      cards.push({ kind: 'housework_habit', habit: houseworkHabit });
    }
    if (prefs.showHouseworkRoutine && houseworkRoutineCard) {
      cards.push({ kind: 'housework_routine', card: houseworkRoutineCard });
    }
    if (prefs.showBedtimeRoutine && bedtimeCard) {
      cards.push({ kind: 'bedtime', card: bedtimeCard });
    }
    return cards;
  }, [
    prefs.showMorningRoutine,
    prefs.showHouseworkHabit,
    prefs.showHouseworkRoutine,
    prefs.showBedtimeRoutine,
    morningCard,
    houseworkHabit,
    houseworkRoutineCard,
    bedtimeCard,
  ]);

  const isEmpty = visibleCards.length === 0;

  // Hide section entirely when empty AND the user dismissed the hint —
  // mirrors Android's `state.isEmpty && state.hasSeenHint` early return.
  if (isEmpty && prefs.hasSeenHint) return null;

  return (
    <section
      className="mb-4 overflow-hidden rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)]"
      aria-label="Daily Essentials"
    >
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="flex w-full items-center gap-2 px-4 py-3 text-left transition-colors hover:bg-[var(--color-bg-secondary)]"
        aria-expanded={expanded}
      >
        {expanded ? (
          <ChevronDown className="h-4 w-4 text-[var(--color-text-secondary)]" />
        ) : (
          <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" />
        )}
        <Sparkles className="h-4 w-4 text-[var(--color-accent)]" />
        <span className="text-sm font-semibold text-[var(--color-text-primary)]">
          Daily Essentials
        </span>
        {visibleCards.length > 0 && (
          <span className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-xs font-medium text-[var(--color-text-secondary)]">
            {visibleCards.length}
          </span>
        )}
      </button>

      {expanded && (
        <div className="space-y-2 px-3 pb-3">
          {isEmpty ? (
            <EmptyHint
              onDismiss={async () => {
                if (uid) {
                  try {
                    await setHasSeenHint(uid, true);
                  } catch {
                    // Optimistic UI is fine here — silently retry next session.
                  }
                }
                setPrefs((prev) => ({ ...prev, hasSeenHint: true }));
              }}
              onSetUp={() => navigate('/settings')}
            />
          ) : (
            visibleCards.map((entry, idx) => {
              if (entry.kind === 'morning' || entry.kind === 'bedtime' || entry.kind === 'housework_routine') {
                return (
                  <RoutineCardView
                    key={`${entry.kind}-${idx}`}
                    card={entry.card}
                    onToggleStep={(stepId) =>
                      handleToggleStep(entry.card.routineType, stepId)
                    }
                  />
                );
              }
              if (entry.kind === 'housework_habit') {
                return (
                  <HabitCardView
                    key={`housework-${entry.habit.id}`}
                    habit={entry.habit}
                    completed={isHabitDoneToday(entry.habit.id)}
                    onToggle={() =>
                      handleToggleHabit(entry.habit.id)
                    }
                  />
                );
              }
              return null;
            })
          )}
        </div>
      )}
    </section>
  );

  async function handleToggleStep(routineType: string, stepId: string) {
    try {
      await toggleSelfCareStep(routineType, todayMs, stepId);
    } catch {
      toast.error('Failed to update step');
    }
  }

  async function handleToggleHabit(habitId: string) {
    try {
      await toggleHabitCompletion(habitId, logicalToday(Date.now(), startOfDayHour));
    } catch {
      toast.error('Failed to update habit');
    }
  }
}

type VisibleCard =
  | { kind: 'morning'; card: RoutineCardState }
  | { kind: 'bedtime'; card: RoutineCardState }
  | { kind: 'housework_routine'; card: RoutineCardState }
  | { kind: 'housework_habit'; habit: Habit };

interface StepState {
  stepId: string;
  label: string;
  completed: boolean;
}

interface RoutineCardState {
  routineType: 'morning' | 'bedtime' | 'housework';
  displayName: string;
  steps: StepState[];
}

// eslint-disable-next-line react-refresh/only-export-components -- parity batch follow-up; see #1573
export function buildRoutineCard(
  routineType: 'morning' | 'bedtime' | 'housework',
  steps: SelfCareStep[],
  logs: SelfCareLog[],
  todayMs: number,
): RoutineCardState | null {
  const routineSteps = steps
    .filter((s) => s.routine_type === routineType)
    .sort((a, b) => a.sort_order - b.sort_order);
  if (routineSteps.length === 0) return null;
  const log =
    logs.find((l) => l.routine_type === routineType && l.date === todayMs) ??
    null;
  const completedIds = new Set(
    log ? parseCompletedStepsForDisplay(log.completed_steps) : [],
  );
  const displayName =
    routineType === 'morning'
      ? 'Morning Routine'
      : routineType === 'bedtime'
        ? 'Bedtime Routine'
        : 'Housework';
  return {
    routineType,
    displayName,
    steps: routineSteps.map((s) => ({
      stepId: s.step_id,
      label: s.label,
      completed: completedIds.has(s.step_id),
    })),
  };
}

/**
 * Resolve the "housework habit" — the habit that powers the Housework
 * card. Android stores a pinned habit id; web mirrors it via Firestore.
 * When unset, fall back to the first active habit whose name contains
 * "house" or "clean" so the card has something to render before the
 * user wires it explicitly.
 */
// eslint-disable-next-line react-refresh/only-export-components -- parity batch follow-up; see #1573
export function pickHouseworkHabit(
  habits: Habit[],
  pinnedId: string | null,
): Habit | null {
  if (pinnedId) {
    const pinned = habits.find((h) => h.id === pinnedId);
    if (pinned && pinned.is_active) return pinned;
  }
  const candidate = habits.find(
    (h) =>
      h.is_active &&
      (h.name.toLowerCase().includes('house') ||
        h.name.toLowerCase().includes('clean')),
  );
  return candidate ?? null;
}

function RoutineCardView({
  card,
  onToggleStep,
}: {
  card: RoutineCardState;
  onToggleStep: (stepId: string) => void;
}) {
  const Icon =
    card.routineType === 'morning'
      ? Sun
      : card.routineType === 'bedtime'
        ? MoonStar
        : Home;
  const done = card.steps.filter((s) => s.completed).length;
  const total = card.steps.length;
  const allDone = done === total && total > 0;

  return (
    <div
      className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3"
      aria-label={card.displayName}
    >
      <header className="mb-2 flex items-center gap-2">
        <Icon className="h-4 w-4 text-[var(--color-accent)]" aria-hidden="true" />
        <span className="text-sm font-semibold text-[var(--color-text-primary)]">
          {card.displayName}
        </span>
        <span className="ml-auto text-xs text-[var(--color-text-secondary)]">
          {done} of {total} done
        </span>
      </header>
      <ul className="space-y-1">
        {card.steps.map((step) => (
          <li key={step.stepId}>
            <button
              type="button"
              onClick={() => onToggleStep(step.stepId)}
              className="flex w-full items-center gap-2 rounded px-1 py-1 text-left text-sm hover:bg-[var(--color-bg-card)]"
              aria-pressed={step.completed}
            >
              {step.completed ? (
                <CheckCircle2 className="h-4 w-4 text-[var(--color-accent)]" />
              ) : (
                <Circle className="h-4 w-4 text-[var(--color-text-secondary)]" />
              )}
              <span
                className={
                  step.completed
                    ? 'text-[var(--color-text-secondary)] line-through'
                    : 'text-[var(--color-text-primary)]'
                }
              >
                {step.label}
              </span>
            </button>
          </li>
        ))}
      </ul>
      {allDone && (
        <p className="mt-1 text-[10px] text-[var(--color-accent)]">All done</p>
      )}
    </div>
  );
}

function HabitCardView({
  habit,
  completed,
  onToggle,
}: {
  habit: Habit;
  completed: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      className="flex w-full items-center gap-3 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3 text-left hover:bg-[var(--color-bg-card)]"
      aria-pressed={completed}
      aria-label={`Housework — ${habit.name}`}
    >
      <Home className="h-4 w-4 text-[var(--color-accent)]" aria-hidden="true" />
      <div className="flex-1">
        <p className="text-sm font-semibold text-[var(--color-text-primary)]">
          Housework
        </p>
        <p
          className={
            completed
              ? 'text-xs text-[var(--color-text-secondary)] line-through'
              : 'text-xs text-[var(--color-text-secondary)]'
          }
        >
          {habit.icon ? `${habit.icon} ` : ''}
          {habit.name}
        </p>
      </div>
      {completed ? (
        <CheckCircle2 className="h-5 w-5 text-[var(--color-accent)]" />
      ) : (
        <Circle className="h-5 w-5 text-[var(--color-text-secondary)]" />
      )}
    </button>
  );
}

function EmptyHint({
  onDismiss,
  onSetUp,
}: {
  onDismiss: () => void;
  onSetUp: () => void;
}) {
  return (
    <div className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3">
      <div className="flex items-start gap-3">
        <Coffee
          className="mt-0.5 h-5 w-5 shrink-0 text-[var(--color-accent)]"
          aria-hidden="true"
        />
        <div className="flex-1">
          <p className="text-sm font-semibold text-[var(--color-text-primary)]">
            Set Up Your Daily Essentials
          </p>
          <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
            Pick the habits and routines you want to see every morning —
            housework, schoolwork, and more.
          </p>
          <div className="mt-2 flex justify-end gap-2">
            <button
              type="button"
              onClick={onDismiss}
              className="rounded px-2 py-1 text-xs text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-card)]"
            >
              Not Now
            </button>
            <button
              type="button"
              onClick={onSetUp}
              className="rounded bg-[var(--color-accent)] px-2 py-1 text-xs font-medium text-white hover:opacity-90"
            >
              Set Up
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

