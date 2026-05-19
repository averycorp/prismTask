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
import { useSettingsStore } from '@/stores/settingsStore';
import { useAdvancedTuningStore } from '@/stores/advancedTuningStore';
import { useCourseStore } from '@/stores/courseStore';
import { useAssignmentStore } from '@/stores/assignmentStore';
import { SchoolworkTodayCard } from '@/features/today/SchoolworkTodayCard';
import { startOfLogicalDayMs } from '@/utils/dayBoundary';
import { parseCompletedStepsForDisplay } from '@/api/firestore/selfCare';
import {
  getTierOrder,
  resolveSelectedTier,
  tierIncludes,
} from '@/utils/selfCareTiers';
import type { SelfCareTierDefaults } from '@/api/firestore/advancedTuningPreferences';
import {
  getDailyEssentialsPreferences,
  subscribeToDailyEssentialsPreferences,
  setHasSeenHint,
  type DailyEssentialsSnapshot,
  DEFAULT_DAILY_ESSENTIALS,
} from '@/api/firestore/dailyEssentialsPreferences';
import { getFirebaseUid } from '@/stores/firebaseUid';
import type { SelfCareLog, SelfCareStep } from '@/api/firestore/selfCare';
import type { Assignment, Course } from '@/types/schoolwork';

/**
 * Daily Essentials section — web port of Android's
 * `DailyEssentialsSection.kt` and `DailyEssentialsUseCase.kt` (parity
 * unit 6 of 23, Today screen A).
 *
 * Card order (matches Android's `DailyEssentialsSection.kt`):
 *   1. Morning routine (self-care steps)
 *   2. Housework routine (self-care steps)
 *   3. Schoolwork (active courses + assignments due today)
 *   4. Bedtime routine (self-care steps)
 *
 * Cards hide individually when empty. The whole section collapses to a
 * single "Set Up Your Daily Essentials" hint when nothing is configured
 * AND the user has not dismissed the hint via `has_seen_hint`.
 *
 * The legacy single-checkbox "Housework habit" card was retired here —
 * the multi-step Housework Routine above already surfaces housework
 * work in checkable rows (mirroring how PR #1326 retired the Leisure
 * Minimum card). The `housework_habit_id` / `show_housework_habit`
 * Firestore fields remain readable for Android parity but are unused
 * on web.
 *
 * Schoolwork has no per-section show/hide flag — it gates purely on
 * "has content" (active course or due-today assignment) the same way
 * Android's `SchoolworkCardState.hasContent` does.
 *
 * Title Capitalization per CLAUDE.md user-facing strings convention.
 */
export function DailyEssentialsCards() {
  const navigate = useNavigate();
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const selfCareLogs = useSelfCareStore((s) => s.logs);
  const selfCareSteps = useSelfCareStore((s) => s.steps);
  const toggleSelfCareStep = useSelfCareStore((s) => s.toggleStep);

  const tierDefaults = useAdvancedTuningStore((s) => s.prefs.selfCareTierDefaults);

  // Schoolwork slot — Android's `SchoolworkCardState.hasContent` rule:
  // surface the card if there's any active course OR any not-yet-completed
  // assignment due today. The `SchoolworkTodayCard` already does its own
  // bucketing; we precompute `hasSchoolworkContent` here so the slot
  // counts toward `visibleCards.length` (header chip + empty-state gating).
  const courses = useCourseStore((s) => s.courses);
  const assignments = useAssignmentStore((s) => s.assignments);

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
    () =>
      buildRoutineCard('morning', selfCareSteps, selfCareLogs, todayMs, tierDefaults),
    [selfCareSteps, selfCareLogs, todayMs, tierDefaults],
  );
  const bedtimeCard = useMemo(
    () =>
      buildRoutineCard('bedtime', selfCareSteps, selfCareLogs, todayMs, tierDefaults),
    [selfCareSteps, selfCareLogs, todayMs, tierDefaults],
  );
  const houseworkRoutineCard = useMemo(
    () =>
      buildRoutineCard('housework', selfCareSteps, selfCareLogs, todayMs, tierDefaults),
    [selfCareSteps, selfCareLogs, todayMs, tierDefaults],
  );

  // `SchoolworkTodayCard` buckets due-today assignments by calendar
  // midnight (not logical-day midnight) — mirror that here so the
  // visibility predicate matches what the card actually renders.
  const todayMidnightMs = useMemo(() => {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    return d.getTime();
  }, []);

  const hasSchoolworkContent = useMemo(
    () => schoolworkHasContent(courses, assignments, todayMidnightMs),
    [courses, assignments, todayMidnightMs],
  );

  const visibleCards: VisibleCard[] = useMemo(() => {
    const cards: VisibleCard[] = [];
    if (prefs.showMorningRoutine && morningCard) {
      cards.push({ kind: 'morning', card: morningCard });
    }
    if (prefs.showHouseworkRoutine && houseworkRoutineCard) {
      cards.push({ kind: 'housework_routine', card: houseworkRoutineCard });
    }
    if (hasSchoolworkContent) {
      cards.push({ kind: 'schoolwork' });
    }
    if (prefs.showBedtimeRoutine && bedtimeCard) {
      cards.push({ kind: 'bedtime', card: bedtimeCard });
    }
    return cards;
  }, [
    prefs.showMorningRoutine,
    prefs.showHouseworkRoutine,
    prefs.showBedtimeRoutine,
    morningCard,
    houseworkRoutineCard,
    bedtimeCard,
    hasSchoolworkContent,
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
              if (entry.kind === 'schoolwork') {
                return <SchoolworkTodayCard key={`schoolwork-${idx}`} />;
              }
              return (
                <RoutineCardView
                  key={`${entry.kind}-${idx}`}
                  card={entry.card}
                  onToggleStep={(stepId) =>
                    handleToggleStep(entry.card.routineType, stepId)
                  }
                />
              );
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
}

type VisibleCard =
  | { kind: 'morning'; card: RoutineCardState }
  | { kind: 'bedtime'; card: RoutineCardState }
  | { kind: 'housework_routine'; card: RoutineCardState }
  | { kind: 'schoolwork' };

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
  tierDefaults: SelfCareTierDefaults,
): RoutineCardState | null {
  const routineSteps = steps
    .filter((s) => s.routine_type === routineType)
    .sort((a, b) => a.sort_order - b.sort_order);
  if (routineSteps.length === 0) return null;
  const log =
    logs.find((l) => l.routine_type === routineType && l.date === todayMs) ??
    null;
  const tierOrder = getTierOrder(routineType);
  const selectedTier = resolveSelectedTier(
    log?.selected_tier,
    tierOrder,
    tierDefaults[routineType],
  );
  if (!selectedTier) return null;
  const visibleSteps = routineSteps.filter((s) =>
    tierIncludes(tierOrder, selectedTier, s.tier),
  );
  if (visibleSteps.length === 0) return null;
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
    steps: visibleSteps.map((s) => ({
      stepId: s.step_id,
      label: s.label,
      completed: completedIds.has(s.step_id),
    })),
  };
}

/**
 * Schoolwork visibility predicate. Mirrors Android's
 * `SchoolworkCardState.hasContent`: true when there's at least one
 * active course, OR at least one not-yet-completed assignment due
 * within the current calendar day (matches `SchoolworkTodayCard`'s
 * own bucketing).
 */
// eslint-disable-next-line react-refresh/only-export-components -- pure helper for unit testing
export function schoolworkHasContent(
  courses: Course[],
  assignments: Assignment[],
  todayMidnightMs: number,
): boolean {
  if (courses.some((c) => c.active)) return true;
  const tomorrowMidnight = todayMidnightMs + 24 * 60 * 60 * 1000;
  return assignments.some(
    (a) =>
      !a.completed &&
      a.dueDate != null &&
      a.dueDate >= todayMidnightMs &&
      a.dueDate < tomorrowMidnight,
  );
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
