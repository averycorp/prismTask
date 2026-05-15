import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, BookOpen, Plus, Trash2 } from 'lucide-react';
import { format } from 'date-fns';
import { toast } from 'sonner';
import { useHabitStore } from '@/stores/habitStore';
import { useHabitLogStore } from '@/stores/habitLogStore';
import { Button } from '@/components/ui/Button';
import { Spinner } from '@/components/ui/Spinner';
import { EmptyState } from '@/components/ui/EmptyState';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { HabitBookingDialog } from './HabitBookingDialog';
import type { HabitLog } from '@/api/firestore/habitLogs';

/**
 * Per-habit activity history. Mirrors Android's bookable-habit log
 * list (`HabitDetailViewModel.logs`) — newest-first, delete via
 * trailing icon, "Book Activity" CTA at the top reuses
 * `HabitBookingDialog`.
 *
 * Reads from `habitLogStore.logsByHabit` which is fed by the
 * Firestore real-time listener wired in `useFirestoreSync`. Parity
 * audit § B.3b.
 */
export function HabitLogsScreen() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const habitId = id!;

  const { habits, fetchHabits } = useHabitStore();
  const logs = useHabitLogStore((s) => s.logsByHabit[habitId] ?? []);
  const deleteLog = useHabitLogStore((s) => s.deleteLog);

  const [loading, setLoading] = useState(true);
  const [bookingOpen, setBookingOpen] = useState(false);
  const [deleteCandidate, setDeleteCandidate] = useState<HabitLog | null>(null);
  const [deleting, setDeleting] = useState(false);

  const habit = useMemo(
    () => habits.find((h) => h.id === habitId) ?? null,
    [habits, habitId],
  );

  useEffect(() => {
    const load = async () => {
      try {
        if (habits.length === 0) await fetchHabits();
      } finally {
        setLoading(false);
      }
    };
    load();
  }, [habits.length, fetchHabits]);

  const handleConfirmDelete = async () => {
    if (!deleteCandidate) return;
    setDeleting(true);
    try {
      await deleteLog(deleteCandidate.id);
      toast.success('Activity removed');
    } catch {
      toast.error('Failed to remove activity');
    } finally {
      setDeleting(false);
      setDeleteCandidate(null);
    }
  };

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  if (!habit) {
    return (
      <div className="mx-auto max-w-3xl">
        <EmptyState
          icon={<BookOpen className="h-8 w-8" />}
          title="Habit Not Found"
          description="This habit may have been deleted."
          actionLabel="Back to Habits"
          onAction={() => navigate('/habits')}
        />
      </div>
    );
  }

  const habitColor = habit.color || 'var(--color-accent)';

  return (
    <div className="mx-auto max-w-3xl">
      {/* Header */}
      <div className="mb-6 flex items-center gap-3">
        <button
          onClick={() => navigate('/habits')}
          className="rounded-md p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)] transition-colors"
          aria-label="Back to Habits"
        >
          <ArrowLeft className="h-5 w-5" />
        </button>
        <div
          className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-lg"
          style={{ backgroundColor: habitColor + '20', color: habitColor }}
        >
          {habit.icon || '🎯'}
        </div>
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-xl font-bold text-[var(--color-text-primary)]">
            {habit.name}
          </h1>
          <p className="text-sm text-[var(--color-text-secondary)]">
            Activity History
          </p>
        </div>
        {habit.is_bookable && (
          <Button onClick={() => setBookingOpen(true)} size="sm">
            <Plus className="h-4 w-4" />
            Book Activity
          </Button>
        )}
      </div>

      {/* Stats row */}
      <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-3">
        <StatTile label="Total Activities" value={String(logs.length)} />
        <StatTile
          label="Last Activity"
          value={
            logs.length > 0
              ? format(new Date(logs[0].date), 'MMM d, yyyy')
              : '—'
          }
        />
        <StatTile
          label="Average Interval"
          value={formatAverageInterval(logs)}
        />
      </div>

      {/* Log list */}
      {logs.length === 0 ? (
        <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)]">
          <EmptyState
            icon={<BookOpen className="h-8 w-8" />}
            title="No Activity Yet"
            description={
              habit.is_bookable
                ? 'Tap "Book Activity" to record your first session.'
                : 'Activity logs appear here once recorded on any device.'
            }
            actionLabel={habit.is_bookable ? 'Book Activity' : undefined}
            onAction={
              habit.is_bookable ? () => setBookingOpen(true) : undefined
            }
          />
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          {logs.map((log) => (
            <LogRow
              key={log.id}
              log={log}
              accentColor={habitColor}
              onDelete={() => setDeleteCandidate(log)}
            />
          ))}
        </div>
      )}

      {bookingOpen && (
        <HabitBookingDialog
          habit={habit}
          onClose={() => setBookingOpen(false)}
        />
      )}

      <ConfirmDialog
        isOpen={!!deleteCandidate}
        onClose={() => setDeleteCandidate(null)}
        onConfirm={handleConfirmDelete}
        title="Delete Activity"
        message="This will permanently remove the activity log entry."
        confirmLabel="Delete"
        variant="danger"
        loading={deleting}
      />
    </div>
  );
}

function StatTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3">
      <p className="text-xs uppercase tracking-wide text-[var(--color-text-secondary)]">
        {label}
      </p>
      <p className="mt-1 text-base font-semibold text-[var(--color-text-primary)]">
        {value}
      </p>
    </div>
  );
}

function LogRow({
  log,
  accentColor,
  onDelete,
}: {
  log: HabitLog;
  accentColor: string;
  onDelete: () => void;
}) {
  const date = new Date(log.date);
  return (
    <div className="group flex items-start gap-3 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3 transition-colors hover:border-[var(--color-accent)]/30">
      <div
        className="mt-0.5 h-2 w-2 shrink-0 rounded-full"
        style={{ backgroundColor: accentColor }}
        aria-hidden
      />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-[var(--color-text-primary)]">
          {format(date, 'EEEE, MMM d, yyyy')}
        </p>
        <p className="text-xs text-[var(--color-text-secondary)]">
          {format(date, 'h:mm a')}
        </p>
        {log.notes && (
          <p className="mt-1.5 text-sm text-[var(--color-text-primary)] whitespace-pre-wrap">
            {log.notes}
          </p>
        )}
      </div>
      <button
        onClick={onDelete}
        className="shrink-0 rounded-md p-1.5 text-[var(--color-text-secondary)] opacity-0 transition-opacity group-hover:opacity-100 hover:bg-[var(--color-bg-secondary)] hover:text-red-500"
        aria-label="Delete activity"
      >
        <Trash2 className="h-4 w-4" />
      </button>
    </div>
  );
}

/**
 * Average gap between consecutive activity logs, expressed as a
 * rounded day count. Mirrors the
 * `HabitDetailViewModel.HabitDetailStats.averageIntervalDays`
 * computation: dates ascending, zipWithNext to get intervals, mean
 * coerced to >= 1.
 */
function formatAverageInterval(logs: HabitLog[]): string {
  if (logs.length < 2) return '—';
  const sortedAsc = [...logs].sort((a, b) => a.date - b.date);
  let totalMs = 0;
  for (let i = 1; i < sortedAsc.length; i++) {
    totalMs += sortedAsc[i].date - sortedAsc[i - 1].date;
  }
  const avgMs = totalMs / (sortedAsc.length - 1);
  const days = Math.max(1, Math.round(avgMs / (24 * 60 * 60 * 1000)));
  return `${days} day${days === 1 ? '' : 's'}`;
}
