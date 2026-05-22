import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  TrendingUp,
  ChevronLeft,
  ChevronRight,
  History,
  Sparkles,
  X,
  Lock,
} from 'lucide-react';
import { toast } from 'sonner';

import { useProFeature } from '@/hooks/useProFeature';
import { getFirebaseUid } from '@/stores/firebaseUid';
import * as firestoreTasks from '@/api/firestore/tasks';
import { upsertWeeklyReview } from '@/api/firestore/weeklyReviews';
import { aiApi, type WeeklyReviewResponse } from '@/api/ai';
import { Button } from '@/components/ui/Button';
import { Spinner } from '@/components/ui/Spinner';
import type { Task } from '@/types/task';

import {
  aggregateWeek,
  computeWeekWindow,
  shiftWeekWindow,
  taskToSummary,
  type WeeklyReviewLocal,
  type WeeklyWindow,
} from './weeklyAggregator';

// Sealed UI state mirrors the Android WeeklyReviewViewModel: Loading /
// Success / Empty / Error. Error carries the local fallback so the
// screen can keep rendering useful content when the backend is
// unreachable.
type UiState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | {
      kind: 'success';
      local: WeeklyReviewLocal;
      backend: WeeklyReviewResponse | null;
    }
  | { kind: 'empty'; local: WeeklyReviewLocal }
  | { kind: 'error'; local: WeeklyReviewLocal; message: string };

/**
 * Persist the computed review to Firestore so it shows up cross-device.
 * Best-effort — a transient Firestore failure is logged but doesn't
 * block the UI. Parity audit C.4a.
 */
async function persistWeeklyReview(
  weekWindow: WeeklyWindow,
  local: WeeklyReviewLocal,
  backend: WeeklyReviewResponse | null,
): Promise<void> {
  try {
    const uid = getFirebaseUid();
    const metrics = {
      completed_count: local.completedCount,
      slipped_count: local.slippedCount,
      wins: local.narrative.wins,
      slips: local.narrative.slips,
      suggestions: local.narrative.suggestions,
      week_end_iso: weekWindow.weekEndIso,
    };
    await upsertWeeklyReview(uid, {
      weekStart: weekWindow.weekStartIso,
      metricsJson: JSON.stringify(metrics),
      aiInsightsJson: backend ? JSON.stringify(backend) : null,
    });
  } catch (err) {
    console.warn('Failed to persist weekly review to Firestore', err);
  }
}

function formatWeekLabel(window: WeeklyWindow): string {
  const start = new Date(window.weekStartMs);
  const end = new Date(window.weekEndMs);
  const startStr = start.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
  });
  const endStr = end.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
  return `Week of ${startStr} – ${endStr}`;
}

export function WeeklyReviewScreen() {
  const { isPro } = useProFeature();

  // Default to the current ongoing week. The user can navigate backward
  // to retrospective weeks via the prev/next buttons.
  const [weekWindow, setWeekWindow] = useState<WeeklyWindow>(() =>
    computeWeekWindow(),
  );
  const [uiState, setUiState] = useState<UiState>({ kind: 'idle' });
  const [errorDismissed, setErrorDismissed] = useState(false);

  const weekLabel = useMemo(() => formatWeekLabel(weekWindow), [weekWindow]);

  const runReview = useCallback(
    async (targetWindow: WeeklyWindow, signal?: AbortSignal) => {
      setUiState({ kind: 'loading' });
      setErrorDismissed(false);

      let tasks: Task[];
      try {
        const uid = getFirebaseUid();
        tasks = await firestoreTasks.getTasksForWeeklyReview(uid, targetWindow);
      } catch {
        if (signal?.aborted) return;
        toast.error('Failed to load tasks');
        setUiState({ kind: 'idle' });
        return;
      }

      if (signal?.aborted) return;

      // Step 1: local aggregation (fast, deterministic, always succeeds).
      const local = aggregateWeek(tasks, targetWindow);

      // Step 2: Free users stop here with local-only content. If everything
      // is empty, surface the Empty state so the screen isn't a blank card.
      if (!isPro) {
        if (local.completedCount === 0 && local.slippedCount === 0) {
          setUiState({ kind: 'empty', local });
        } else {
          setUiState({ kind: 'success', local, backend: null });
          // Persist Free-tier reviews too — Android picks them up on next
          // pull and renders the same metrics breakdown.
          void persistWeeklyReview(targetWindow, local, null);
        }
        return;
      }

      // Step 3: Pro users additionally call the backend. On success,
      // render the richer narrative; on failure, fall back to local.
      try {
        const response = await aiApi.weeklyReview({
          week_start: targetWindow.weekStartIso,
          week_end: targetWindow.weekEndIso,
          completed_tasks: local.completedTasks.map((t) =>
            taskToSummary(t, { completed: true }),
          ),
          slipped_tasks: local.slippedTasks.map((t) =>
            taskToSummary(t, { completed: false }),
          ),
          // habit_summary / pomodoro_summary: intentionally omitted. The
          // web app doesn't have persistent pomodoro stats, and computing
          // a habit_summary requires iterating completions per habit,
          // which is scoped for a dedicated follow-up. Backend treats
          // missing fields as "not provided" — no prompt section for
          // either.
          notes: null,
        });

        if (signal?.aborted) return;

        const backendIsEmpty =
          response.wins.length === 0 &&
          response.slips.length === 0 &&
          response.patterns.length === 0 &&
          response.next_week_focus.length === 0 &&
          !response.narrative.trim();

        if (
          backendIsEmpty &&
          local.completedCount === 0 &&
          local.slippedCount === 0
        ) {
          setUiState({ kind: 'empty', local });
        } else {
          setUiState({ kind: 'success', local, backend: response });
          // Fire-and-forget persistence to Firestore so Android picks up
          // the review on next pull. Parity audit C.4a.
          void persistWeeklyReview(targetWindow, local, response);
        }
      } catch (err) {
        if (signal?.aborted) return;

        console.warn('AI weekly review fell back to local summary', err);
        setUiState({
          kind: 'error',
          local,
          message: 'AI review unavailable — showing local summary.',
        });
        // Still persist the local-only review so cross-device users see
        // the same week breakdown without re-running the aggregator.
        void persistWeeklyReview(targetWindow, local, null);
      }
    },
    [isPro]
  );

  // Auto-run on mount / week change once tasks are loaded. The eslint
  // rule against synchronous setState-in-effect doesn't fit here: we
  // deliberately trigger the local-then-remote review pipeline when the
  // selected week changes or tasks first load. Splitting this into a
  // pure useMemo + a separate fetch effect wouldn't compose well
  // because the fetch outcome feeds back into the same ui state.
  useEffect(() => {
    const controller = new AbortController();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void runReview(weekWindow, controller.signal);
    return () => {
      controller.abort();
    };
  }, [weekWindow, runReview]);

  const onPrevWeek = () => setWeekWindow((w) => shiftWeekWindow(w, -1));
  const onNextWeek = () => setWeekWindow((w) => shiftWeekWindow(w, 1));
  const onThisWeek = () => setWeekWindow(computeWeekWindow());

  // Derive the local content to render (same for Success fallback and
  // explicit Error state).
  const localForRender: WeeklyReviewLocal | null =
    uiState.kind === 'success' || uiState.kind === 'error' || uiState.kind === 'empty'
      ? uiState.local
      : null;
  const backendForRender: WeeklyReviewResponse | null =
    uiState.kind === 'success' ? uiState.backend : null;

  return (
    <div className="mx-auto max-w-3xl">
      {/* Header */}
      <div className="mb-6 flex items-center gap-3">
        <TrendingUp className="h-7 w-7 text-[var(--color-accent)]" />
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          Weekly Review
        </h1>
        {!isPro && (
          <span className="flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700">
            <Lock className="h-3 w-3" />
            Pro adds AI insights
          </span>
        )}
        <div className="ml-auto">
          <Link
            to="/weekly-review/history"
            className="flex items-center gap-1 text-sm text-[var(--color-text-secondary)] hover:text-[var(--color-accent)]"
            aria-label="View History"
          >
            <History className="h-4 w-4" />
            History
          </Link>
        </div>
      </div>

      {/* Week selector */}
      <div className="mb-4 flex items-center justify-between rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3">
        <Button variant="ghost" size="sm" onClick={onPrevWeek}>
          <ChevronLeft className="h-4 w-4" />
          Previous
        </Button>
        <div className="flex flex-col items-center">
          <span className="text-sm font-semibold text-[var(--color-text-primary)]">
            {weekLabel}
          </span>
          <button
            type="button"
            onClick={onThisWeek}
            className="text-xs text-[var(--color-text-secondary)] hover:text-[var(--color-accent)]"
          >
            Jump to this week
          </button>
        </div>
        <Button variant="ghost" size="sm" onClick={onNextWeek}>
          Next
          <ChevronRight className="h-4 w-4" />
        </Button>
      </div>

      {/* Body */}
      {uiState.kind === 'idle' && (
        <div className="flex h-40 items-center justify-center text-sm text-[var(--color-text-secondary)]">
          Loading tasks…
        </div>
      )}

      {uiState.kind === 'loading' && (
        <div className="flex h-40 items-center justify-center">
          <Spinner size="lg" />
        </div>
      )}

      {uiState.kind === 'empty' && (
        <EmptyCard />
      )}

      {(uiState.kind === 'success' || uiState.kind === 'error') && localForRender && (
        <>
          {uiState.kind === 'error' && !errorDismissed && (
            <ErrorBanner
              message={uiState.message}
              onDismiss={() => setErrorDismissed(true)}
            />
          )}
          <MetricsRow local={localForRender} />
          {backendForRender ? (
            <BackendNarrative response={backendForRender} />
          ) : (
            <LocalNarrative local={localForRender} />
          )}
          <CarryForward tasks={localForRender.slippedTasks} />
          {!isPro && <FreeTierUpsell />}
        </>
      )}

      {/* Action footer: manual generate / re-run. Available to Free users
          too — they get a fresh local recompute + a persisted Firestore
          row so cross-device pulls land the same week's snapshot. */}
      {uiState.kind !== 'loading' && uiState.kind !== 'idle' && (
        <div className="mt-6 flex justify-end">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => { void runReview(weekWindow); }}
            disabled={uiState.kind === 'loading'}
            aria-label="Generate Now"
          >
            <Sparkles className="h-4 w-4" />
            Generate Now
          </Button>
        </div>
      )}
    </div>
  );
}

function MetricsRow({ local }: { local: WeeklyReviewLocal }) {
  const total = local.completedCount + local.slippedCount;
  const rate = total === 0 ? 0 : Math.round((local.completedCount / total) * 100);
  return (
    <div className="mb-4 grid grid-cols-3 gap-3">
      <MetricCard label="Completed" value={local.completedCount.toString()} />
      <MetricCard label="Slipped" value={local.slippedCount.toString()} />
      <MetricCard label="Rate" value={`${rate}%`} />
    </div>
  );
}

function MetricCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3">
      <div className="text-[10px] font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">
        {label}
      </div>
      <div className="mt-1 text-2xl font-bold text-[var(--color-text-primary)]">
        {value}
      </div>
    </div>
  );
}

function BackendNarrative({ response }: { response: WeeklyReviewResponse }) {
  return (
    <div className="flex flex-col gap-3">
      {response.narrative && (
        <div className="rounded-xl border border-[var(--color-accent)]/20 bg-[var(--color-accent)]/5 px-4 py-3">
          <p className="text-sm text-[var(--color-text-primary)]">
            {response.narrative}
          </p>
        </div>
      )}
      <NarrativeSection title="Wins" items={response.wins} tone="positive" />
      <NarrativeSection title="Slips" items={response.slips} tone="neutral" />
      <NarrativeSection title="Patterns" items={response.patterns} tone="neutral" />
      <NarrativeSection
        title="Focus For Next Week"
        items={response.next_week_focus}
        tone="accent"
      />
    </div>
  );
}

function LocalNarrative({ local }: { local: WeeklyReviewLocal }) {
  return (
    <div className="flex flex-col gap-3">
      <NarrativeSection
        title="What Went Well"
        items={local.narrative.wins}
        tone="positive"
      />
      <NarrativeSection
        title="What Slipped"
        items={local.narrative.slips}
        tone="neutral"
      />
      <NarrativeSection
        title="Suggestions For Next Week"
        items={local.narrative.suggestions}
        tone="accent"
      />
    </div>
  );
}

function NarrativeSection({
  title,
  items,
  tone,
}: {
  title: string;
  items: string[];
  tone: 'positive' | 'neutral' | 'accent';
}) {
  if (items.length === 0) return null;
  const toneClass =
    tone === 'positive'
      ? 'border-green-500/20 bg-green-500/5'
      : tone === 'accent'
      ? 'border-[var(--color-accent)]/20 bg-[var(--color-accent)]/5'
      : 'border-[var(--color-border)] bg-[var(--color-bg-card)]';
  return (
    <div className={`rounded-xl border px-4 py-3 ${toneClass}`}>
      <div className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
        {title}
      </div>
      <ul className="flex flex-col gap-1">
        {items.map((item, idx) => (
          <li
            key={idx}
            className="text-sm text-[var(--color-text-primary)]"
          >
            • {item}
          </li>
        ))}
      </ul>
    </div>
  );
}

function CarryForward({ tasks }: { tasks: Task[] }) {
  if (tasks.length === 0) return null;
  return (
    <div className="mt-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3">
      <div className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
        Carry Forward
      </div>
      <ul className="flex flex-col gap-1">
        {tasks.slice(0, 5).map((task) => (
          <li
            key={task.id}
            className="truncate text-sm text-[var(--color-text-primary)]"
          >
            • {task.title}
          </li>
        ))}
        {tasks.length > 5 && (
          <li className="text-xs text-[var(--color-text-secondary)]">
            …and {tasks.length - 5} more
          </li>
        )}
      </ul>
    </div>
  );
}

function ErrorBanner({
  message,
  onDismiss,
}: {
  message: string;
  onDismiss: () => void;
}) {
  return (
    <div
      role="status"
      className="mb-4 flex items-start justify-between gap-3 rounded-lg border border-red-500/20 bg-red-500/5 px-4 py-3"
    >
      <span className="text-sm text-[var(--color-text-primary)]">{message}</span>
      <button
        type="button"
        onClick={onDismiss}
        className="shrink-0 rounded p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
        aria-label="Dismiss"
      >
        <X className="h-4 w-4" />
      </button>
    </div>
  );
}

function EmptyCard() {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-6 py-10 text-center">
      <h2 className="text-base font-semibold text-[var(--color-text-primary)]">
        Nothing to review this week yet.
      </h2>
      <p className="mt-2 text-sm text-[var(--color-text-secondary)]">
        Come back after you complete some tasks — or scroll back to see a
        past week.
      </p>
    </div>
  );
}

function FreeTierUpsell() {
  return (
    <div className="mt-4 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-2 text-xs text-[var(--color-text-secondary)]">
      Upgrade to Pro for AI-generated insights.
    </div>
  );
}
