import { useCallback, useState } from 'react';
import { CalendarClock, Sparkles, Lock, X } from 'lucide-react';
import { toast } from 'sonner';

import { useProFeature } from '@/hooks/useProFeature';
import { aiApi, type TimeBlockResponse } from '@/api/ai';
import { Button } from '@/components/ui/Button';
import { Spinner } from '@/components/ui/Spinner';

// Sealed-like UI state mirroring the Android Auto-Schedule preview:
// Idle / Loading / Success / Empty / Error. "Empty" means the backend
// returned neither scheduled blocks nor unscheduled tasks — don't
// silently render "0 tasks • 0h work" in that case.
type UiState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'success'; response: TimeBlockResponse }
  | { kind: 'empty' }
  | { kind: 'error'; message: string };

function todayIsoDate(): string {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export function TimeBlockScreen() {
  const { isPro } = useProFeature();

  const [date, setDate] = useState<string>(todayIsoDate());
  const [dayStart, setDayStart] = useState('09:00');
  const [dayEnd, setDayEnd] = useState('18:00');
  const [includeBreaks, setIncludeBreaks] = useState(true);
  const [uiState, setUiState] = useState<UiState>({ kind: 'idle' });
  const [errorDismissed, setErrorDismissed] = useState(false);

  const handleGenerate = useCallback(async () => {
    if (!isPro) {
      toast.error(
        'AI Time Blocking is a Pro feature. Upgrade to use AI features.',
      );
      return;
    }
    setUiState({ kind: 'loading' });
    setErrorDismissed(false);
    try {
      const response = await aiApi.timeBlock({
        date,
        day_start: dayStart,
        day_end: dayEnd,
        include_breaks: includeBreaks,
      });
      if (
        response.schedule.length === 0 &&
        response.unscheduled_tasks.length === 0
      ) {
        setUiState({ kind: 'empty' });
        return;
      }
      setUiState({ kind: 'success', response });
    } catch (err) {
      console.warn('Time block generation failed', err);
      setUiState({
        kind: 'error',
        message: 'Failed to generate schedule. Try again later.',
      });
    }
  }, [isPro, date, dayStart, dayEnd, includeBreaks]);

  return (
    <div className="mx-auto max-w-3xl">
      {/* Header */}
      <div className="mb-6 flex items-center gap-3">
        <CalendarClock className="h-7 w-7 text-[var(--color-accent)]" />
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          AI Time Blocking
        </h1>
        {!isPro && (
          <span className="flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700">
            <Lock className="h-3 w-3" />
            Pro
          </span>
        )}
      </div>

      {/* Config */}
      <div className="mb-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-6">
        <h2 className="mb-4 text-lg font-semibold text-[var(--color-text-primary)]">
          Plan Your Day
        </h2>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div>
            <label className="mb-1.5 block text-sm font-medium text-[var(--color-text-secondary)]">
              Date
            </label>
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-primary)] px-3 py-2 text-sm text-[var(--color-text-primary)]"
            />
          </div>

          <div className="flex items-center gap-3">
            <div className="flex-1">
              <label className="mb-1.5 block text-sm font-medium text-[var(--color-text-secondary)]">
                Day Start
              </label>
              <input
                type="time"
                value={dayStart}
                onChange={(e) => setDayStart(e.target.value)}
                className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-primary)] px-3 py-2 text-sm text-[var(--color-text-primary)]"
              />
            </div>
            <div className="flex-1">
              <label className="mb-1.5 block text-sm font-medium text-[var(--color-text-secondary)]">
                Day End
              </label>
              <input
                type="time"
                value={dayEnd}
                onChange={(e) => setDayEnd(e.target.value)}
                className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-primary)] px-3 py-2 text-sm text-[var(--color-text-primary)]"
              />
            </div>
          </div>
        </div>

        <label className="mt-4 flex items-center gap-2 text-sm text-[var(--color-text-primary)]">
          <input
            type="checkbox"
            checked={includeBreaks}
            onChange={(e) => setIncludeBreaks(e.target.checked)}
          />
          Include breaks
        </label>

        <Button
          onClick={handleGenerate}
          loading={uiState.kind === 'loading'}
          disabled={uiState.kind === 'loading' || !isPro}
          className="mt-4 w-full"
        >
          <Sparkles className="h-4 w-4" />
          Generate Schedule
        </Button>
      </div>

      {/* Body */}
      {uiState.kind === 'idle' && (
        <div className="rounded-xl border border-dashed border-[var(--color-border)] px-6 py-10 text-center text-sm text-[var(--color-text-secondary)]">
          Configure your day and click <strong>Generate Schedule</strong> to
          get an AI-planned timeline.
        </div>
      )}

      {uiState.kind === 'loading' && (
        <div className="flex h-40 items-center justify-center">
          <Spinner size="lg" />
        </div>
      )}

      {uiState.kind === 'empty' && (
        <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-6 py-10 text-center">
          <h2 className="text-base font-semibold text-[var(--color-text-primary)]">
            Nothing to schedule right now.
          </h2>
          <p className="mt-2 text-sm text-[var(--color-text-secondary)]">
            Add some tasks or pick a different day, then try again.
          </p>
        </div>
      )}

      {uiState.kind === 'error' && !errorDismissed && (
        <div
          role="status"
          className="flex items-start justify-between gap-3 rounded-lg border border-red-500/20 bg-red-500/5 px-4 py-3"
        >
          <span className="text-sm text-[var(--color-text-primary)]">
            {uiState.message}
          </span>
          <button
            type="button"
            onClick={() => setErrorDismissed(true)}
            className="shrink-0 rounded p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
            aria-label="Dismiss"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      )}

      {uiState.kind === 'success' && <ScheduleView response={uiState.response} />}
    </div>
  );
}

function ScheduleView({ response }: { response: TimeBlockResponse }) {
  const { schedule, unscheduled_tasks, stats } = response;
  return (
    <div className="flex flex-col gap-4">
      {/* Stats */}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard label="Scheduled" value={stats.tasks_scheduled.toString()} />
        <StatCard
          label="Work"
          value={`${Math.round(stats.total_work_minutes / 60)}h`}
        />
        <StatCard label="Breaks" value={`${stats.total_break_minutes}m`} />
        <StatCard label="Free" value={`${stats.total_free_minutes}m`} />
      </div>

      {/* Timeline */}
      <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
        <div className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
          Your Schedule
        </div>
        <ul className="flex flex-col gap-2">
          {schedule.map((block, idx) => {
            const toneClass =
              block.type === 'task'
                ? 'border-[var(--color-accent)]/30 bg-[var(--color-accent)]/5'
                : block.type === 'event'
                ? 'border-purple-500/20 bg-purple-500/5'
                : 'border-green-500/20 bg-green-500/5';
            return (
              <li
                key={idx}
                className={`flex items-start gap-3 rounded-lg border px-3 py-2 ${toneClass}`}
              >
                <div className="min-w-[5.5rem] text-xs font-medium text-[var(--color-text-secondary)]">
                  {block.start} – {block.end}
                </div>
                <div className="flex-1">
                  <div className="text-sm font-medium text-[var(--color-text-primary)]">
                    {block.title}
                  </div>
                  {block.reason && (
                    <div className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
                      {block.reason}
                    </div>
                  )}
                </div>
                <span className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">
                  {block.type}
                </span>
              </li>
            );
          })}
        </ul>
      </div>

      {/* Unscheduled */}
      {unscheduled_tasks.length > 0 && (
        <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
          <div className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
            Unscheduled ({unscheduled_tasks.length})
          </div>
          <ul className="flex flex-col gap-1">
            {unscheduled_tasks.map((task) => (
              <li key={task.task_id} className="flex flex-col">
                <span className="text-sm text-[var(--color-text-primary)]">
                  {task.title}
                </span>
                <span className="text-xs text-[var(--color-text-secondary)]">
                  {task.reason}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2">
      <div className="text-[10px] font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">
        {label}
      </div>
      <div className="mt-0.5 text-xl font-bold text-[var(--color-text-primary)]">
        {value}
      </div>
    </div>
  );
}
