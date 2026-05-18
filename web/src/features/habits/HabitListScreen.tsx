import { useEffect, useMemo, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Activity,
  BookOpen,
  Plus,
  Flame,
  MoreVertical,
  Pencil,
  PlusCircle,
  Trash2,
  BarChart3,
} from 'lucide-react';
import { toast } from 'sonner';
import { useHabitStore } from '@/stores/habitStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { useLogicalToday } from '@/utils/useLogicalToday';
import { Button } from '@/components/ui/Button';
import { Spinner } from '@/components/ui/Spinner';
import { EmptyState } from '@/components/ui/EmptyState';
import { Dropdown } from '@/components/ui/Dropdown';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { Tooltip } from '@/components/ui/Tooltip';
import { HabitModal } from './HabitModal';
import { BuiltInTemplateUpdateBanner } from './BuiltInTemplateUpdateBanner';
import {
  dismissBuiltInUpdate,
  findPendingUpdates,
  type PendingBuiltInUpdate,
} from '@/utils/builtInHabitReconciler';
import { HabitBookingDialog } from './HabitBookingDialog';
import { HabitLogDialog } from '@/components/HabitLogDialog';
import { isRecurringFrequency, type Habit } from '@/types/habit';

const DAY_LABELS = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];

// Names of the six "meta-habit" rows that Android keeps in Room but
// excludes from the regular Habits list (rendered as Self-Care /
// Built-In / top-level cards elsewhere). Mirrors the literal constants
// in `SelfCareRepository.kt`, `SchoolworkRepository.kt`, and
// `LeisureBudgetRepository.kt`; the Android filter sits in
// `HabitListViewModel.kt:213-252`. Keep this list in sync with those
// constants when they change.
const META_HABIT_NAMES = new Set<string>([
  'Morning Self-Care',
  'Bedtime Self-Care',
  'Medication',
  'Housework',
  'School',
  'Leisure',
]);

function parseActiveDays(json: string | null): number[] | null {
  if (!json) return null;
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function HabitListScreen() {
  const navigate = useNavigate();
  const {
    habits,
    isLoading,
    fetchHabits,
    toggleCompletion,
    deleteHabit,
    getStreakData,
    isTodayCompleted,
    getTodayCount,
    getTodayProgress,
    getWeekCompletions,
  } = useHabitStore();

  const [modalOpen, setModalOpen] = useState(false);
  const [editingHabit, setEditingHabit] = useState<Habit | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<Habit | null>(null);
  const [deleting, setDeleting] = useState(false);
  // Daily vs Recurring filter — mirrors Android's segmented control at
  // `HabitListScreen.kt:171-186`. `daily` shows `frequency === 'daily'`;
  // `recurring` shows weekly / fortnightly / monthly / bimonthly /
  // quarterly. Defaults to `daily` like Android.
  const [filter, setFilter] = useState<'daily' | 'recurring'>('daily');
  // Booking dialog target — parity audit § B.3b. Surfaced from the
  // card context menu so Android-bookable habits get a quick-log path
  // without diving into analytics first.
  const [bookingHabit, setBookingHabit] = useState<Habit | null>(null);
  // Log-entry dialog target — non-bookable habits (parity unit 14)
  // get a "Log Entry" context-menu item instead of "Book Activity",
  // mirroring Android's `HabitLogDialog` quick-log flow.
  const [logHabit, setLogHabit] = useState<Habit | null>(null);

  // Built-in habit template-version detector (parity B.4). `dismissalTick`
  // bumps after a localStorage dismissal so the memoized list refreshes
  // without forcing a full habit re-fetch.
  const [dismissalTick, setDismissalTick] = useState(0);

  // Hide archived habits from the list to match Android's
  // `HabitDao.getActiveHabits()` (`WHERE is_archived = 0`). Without this
  // filter, habits archived on the phone leak through to web as "extras"
  // because `firestoreHabits.getHabits` returns every habit doc, and the
  // mapper computes `is_active = !data.isArchived` but no consumer here
  // was honouring it. The Today screen + `getTodayProgress` already
  // filter on `is_active`; only this Habits list was missing it.
  //
  // Also drop the six meta-habit rows (Morning/Bedtime Self-Care,
  // Medication, Housework, School, Leisure). Android renders these as
  // dedicated cards on the Habits screen + as top-level destinations,
  // so excluding them from the regular list matches the phone — see
  // `HabitListViewModel.kt:213-252`. Without this, cloud-synced rows
  // leak into web's list and make it longer than mobile's.
  const activeHabits = useMemo(
    () => habits.filter((h) => h.is_active && !META_HABIT_NAMES.has(h.name)),
    [habits],
  );

  const pendingUpdates = useMemo<PendingBuiltInUpdate[]>(
    () => findPendingUpdates(activeHabits),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [activeHabits, dismissalTick],
  );
  const handleDismissUpdate = useCallback(
    (templateKey: string, version: number) => {
      dismissBuiltInUpdate(templateKey, version);
      setDismissalTick((t) => t + 1);
    },
    [],
  );

  // SoD-aware logical-today ISO. Toggling a habit at 02:00 with SoD = 4
  // must write to *yesterday's* logical date so the doc collapses with
  // any phone-side completion via the same `${habitCloudId}__${date}`
  // canonical key (see `web/src/api/firestore/habits.ts`).
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const todayIso = useLogicalToday(startOfDayHour);

  useEffect(() => {
    fetchHabits();
  }, [fetchHabits]);

  const todayProgress = getTodayProgress();
  const progressPct =
    todayProgress.total > 0
      ? Math.round((todayProgress.completed / todayProgress.total) * 100)
      : 0;

  const handleToggle = useCallback(
    async (habitId: string) => {
      try {
        await toggleCompletion(habitId, todayIso);
      } catch {
        toast.error('Failed to update completion');
      }
    },
    [toggleCompletion, todayIso],
  );

  const handleDelete = useCallback(async () => {
    if (!deleteConfirm) return;
    setDeleting(true);
    try {
      await deleteHabit(deleteConfirm.id);
      toast.success('Habit deleted');
    } catch {
      toast.error('Failed to delete habit');
    } finally {
      setDeleting(false);
      setDeleteConfirm(null);
    }
  }, [deleteConfirm, deleteHabit]);

  const handleEdit = useCallback((habit: Habit) => {
    setEditingHabit(habit);
    setModalOpen(true);
  }, []);

  const handleCreate = useCallback(() => {
    setEditingHabit(null);
    setModalOpen(true);
  }, []);

  // Apply the Daily/Recurring filter. Same predicate Android uses in
  // `HabitListScreen.kt:73-86`. Memoized to keep the JSX list reference
  // stable across re-renders when neither input changes.
  const filteredHabits = useMemo(
    () =>
      activeHabits.filter((h) =>
        filter === 'daily'
          ? h.frequency === 'daily'
          : isRecurringFrequency(h.frequency),
      ),
    [activeHabits, filter],
  );

  if (isLoading && activeHabits.length === 0) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Activity className="h-7 w-7 text-[var(--color-accent)]" />
          <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
            Habits
          </h1>
        </div>
        <Button onClick={handleCreate} size="sm">
          <Plus className="h-4 w-4" />
          New Habit
        </Button>
      </div>

      {/* Built-in template update banner (parity B.4). Renders above the
          progress header so it's the first thing the user sees when a new
          registry version is available. Banner self-hides when there are
          no pending updates. */}
      <BuiltInTemplateUpdateBanner
        pending={pendingUpdates}
        onDismiss={handleDismissUpdate}
      />

      {/* Progress Header */}
      {todayProgress.total > 0 && (
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
            <p className="text-sm font-medium text-[var(--color-text-primary)]">
              {todayProgress.completed} of {todayProgress.total} habits
              completed today
            </p>
            <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-[var(--color-bg-secondary)]">
              <div
                className="h-full rounded-full bg-[var(--color-accent)] transition-all duration-500"
                style={{ width: `${progressPct}%` }}
              />
            </div>
          </div>
        </div>
      )}

      {/* Daily / Recurring filter — parity with Android's segmented
          control at `HabitListScreen.kt:171-186`. Hidden when there are
          no habits at all (the empty state already CTA's the user to
          create one). */}
      {activeHabits.length > 0 && (
        <div
          className="mb-4 inline-flex rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] p-1"
          role="tablist"
          aria-label="Habit frequency filter"
        >
          {(['daily', 'recurring'] as const).map((f) => (
            <button
              key={f}
              type="button"
              role="tab"
              aria-selected={filter === f}
              onClick={() => setFilter(f)}
              className={`rounded-md px-4 py-1.5 text-sm font-medium transition-colors ${
                filter === f
                  ? 'bg-[var(--color-accent)] text-white'
                  : 'text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
              }`}
            >
              {f === 'daily' ? 'Daily' : 'Recurring'}
            </button>
          ))}
        </div>
      )}

      {/* Empty States — distinct copy for "no habits at all" vs "no
          habits in the current filter bucket". Mirrors Android's empty
          state at `HabitListScreen.kt:188-207`. */}
      {activeHabits.length === 0 ? (
        <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)]">
          <EmptyState
            icon={<Activity className="h-8 w-8" />}
            title="No Habits Yet"
            description="Build consistent routines by tracking your daily habits."
            actionLabel="Create Your First Habit"
            onAction={handleCreate}
          />
        </div>
      ) : filteredHabits.length === 0 ? (
        <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)]">
          <EmptyState
            icon={<Activity className="h-8 w-8" />}
            title={
              filter === 'daily'
                ? 'Start Building Habits'
                : 'No Recurring Habits'
            }
            description={
              filter === 'daily'
                ? 'Track daily routines at your own pace.'
                : 'Add a weekly, monthly, or other recurring habit.'
            }
            actionLabel="Create Habit"
            onAction={handleCreate}
          />
        </div>
      ) : null}

      {/* Habit Cards */}
      <div className="flex flex-col gap-3">
        {filteredHabits.map((habit) => (
          <HabitCard
            key={habit.id}
            habit={habit}
            streakData={getStreakData(habit.id)}
            todayCompleted={isTodayCompleted(habit.id)}
            todayCount={getTodayCount(habit.id)}
            weekCompletions={getWeekCompletions(habit.id)}
            onToggle={() => handleToggle(habit.id)}
            onEdit={() => handleEdit(habit)}
            onDelete={() => setDeleteConfirm(habit)}
            onViewAnalytics={() => navigate(`/habits/${habit.id}/analytics`)}
            onViewHistory={() => navigate(`/habits/${habit.id}/logs`)}
            onBookActivity={
              habit.is_bookable ? () => setBookingHabit(habit) : undefined
            }
            onLogEntry={
              habit.is_bookable ? undefined : () => setLogHabit(habit)
            }
          />
        ))}
      </div>

      {/* Create/Edit Modal */}
      {modalOpen && (
        <HabitModal
          habit={editingHabit}
          onClose={() => {
            setModalOpen(false);
            setEditingHabit(null);
          }}
        />
      )}

      {/* Book Activity dialog for bookable habits — parity § B.3b. */}
      {bookingHabit && (
        <HabitBookingDialog
          habit={bookingHabit}
          onClose={() => setBookingHabit(null)}
        />
      )}

      {/* Log-entry dialog for ordinary habits — parity unit 14. */}
      {logHabit && (
        <HabitLogDialog
          habit={logHabit}
          onClose={() => setLogHabit(null)}
        />
      )}

      {/* Delete Confirmation */}
      <ConfirmDialog
        isOpen={!!deleteConfirm}
        onClose={() => setDeleteConfirm(null)}
        onConfirm={handleDelete}
        title="Delete Habit"
        message={`Are you sure you want to delete "${deleteConfirm?.name}"? This will also remove all completion history.`}
        confirmLabel="Delete"
        variant="danger"
        loading={deleting}
      />
    </div>
  );
}

// Individual habit card
function HabitCard({
  habit,
  streakData,
  todayCompleted,
  todayCount,
  weekCompletions,
  onToggle,
  onEdit,
  onDelete,
  onViewAnalytics,
  onViewHistory,
  onBookActivity,
  onLogEntry,
}: {
  habit: Habit;
  streakData: ReturnType<typeof useHabitStore.getState>['getStreakData'] extends (
    id: string,
  ) => infer R
    ? R
    : never;
  todayCompleted: boolean;
  todayCount: number;
  weekCompletions: boolean[];
  onToggle: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onViewAnalytics: () => void;
  onViewHistory: () => void;
  /** Only set for `is_bookable` habits — parity § B.3b. */
  onBookActivity?: () => void;
  /** Only set for non-bookable habits — parity unit 14. */
  onLogEntry?: () => void;
}) {
  const currentStreak = streakData?.currentStreak ?? 0;
  const activeDays = parseActiveDays(habit.active_days_json);
  const habitColor = habit.color || 'var(--color-accent)';

  return (
    <div
      className="group flex items-center gap-3 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3 transition-colors hover:border-[var(--color-accent)]/30 cursor-pointer"
      onClick={onViewAnalytics}
    >
      {/* Icon */}
      <div
        className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-lg"
        style={{ backgroundColor: habitColor + '20', color: habitColor }}
      >
        {habit.icon || '🎯'}
      </div>

      {/* Info */}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-sm font-medium text-[var(--color-text-primary)]">
            {habit.name}
          </span>
          {currentStreak > 0 && (
            <span className="inline-flex items-center gap-0.5 rounded-full bg-orange-500/10 px-1.5 py-0.5 text-xs font-medium text-orange-500">
              <Flame className="h-3 w-3" />
              {currentStreak}
            </span>
          )}
          {habit.category && (
            <span className="hidden rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-xs text-[var(--color-text-secondary)] sm:inline">
              {habit.category}
            </span>
          )}
        </div>

        {/* Weekly progress dots */}
        <div className="mt-1.5 flex items-center gap-1">
          {DAY_LABELS.map((label, idx) => {
            const isoDay = idx === 6 ? 7 : idx + 1;
            const isActive =
              !activeDays || activeDays.length === 0 || activeDays.includes(isoDay);
            const completed = weekCompletions[idx];
            return (
              <Tooltip key={idx} content={`${label}${completed ? ' - Done' : isActive ? '' : ' - Not Active'}`} delay={200}>
                <div
                  className={`h-2.5 w-2.5 rounded-full transition-colors ${
                    !isActive
                      ? 'bg-[var(--color-bg-secondary)]'
                      : completed
                        ? ''
                        : 'border border-[var(--color-border)]'
                  }`}
                  style={
                    isActive && completed
                      ? { backgroundColor: habitColor }
                      : undefined
                  }
                />
              </Tooltip>
            );
          })}
        </div>
      </div>

      {/* Completion Toggle */}
      <div
        className="shrink-0"
        onClick={(e) => e.stopPropagation()}
      >
        {habit.target_count > 1 ? (
          <button
            onClick={onToggle}
            className={`flex h-10 w-10 items-center justify-center rounded-full border-2 text-xs font-bold transition-all duration-200 ${
              todayCompleted
                ? 'border-transparent text-white'
                : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:border-[var(--color-accent)]'
            }`}
            style={
              todayCompleted
                ? { backgroundColor: habitColor }
                : undefined
            }
            aria-label={`${todayCount}/${habit.target_count} completions`}
          >
            {todayCount}/{habit.target_count}
          </button>
        ) : (
          <button
            onClick={onToggle}
            className={`flex h-8 w-8 items-center justify-center rounded-full border-2 transition-all duration-200 ${
              todayCompleted
                ? 'border-transparent'
                : 'border-[var(--color-border)] hover:border-[var(--color-accent)]'
            }`}
            style={
              todayCompleted
                ? { backgroundColor: habitColor }
                : undefined
            }
            aria-label={todayCompleted ? 'Mark incomplete' : 'Mark complete'}
          >
            {todayCompleted && (
              <svg
                className="h-4 w-4 text-white"
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
        )}
      </div>

      {/* Context Menu */}
      <div onClick={(e) => e.stopPropagation()}>
        <Dropdown
          align="right"
          trigger={
            <button className="rounded-md p-1 text-[var(--color-text-secondary)] opacity-0 transition-opacity group-hover:opacity-100 hover:bg-[var(--color-bg-secondary)]">
              <MoreVertical className="h-4 w-4" />
            </button>
          }
          sections={[
            {
              items: [
                {
                  key: 'analytics',
                  label: 'View Analytics',
                  icon: <BarChart3 className="h-4 w-4" />,
                  onClick: onViewAnalytics,
                },
                {
                  key: 'history',
                  label: 'Logs',
                  icon: <BookOpen className="h-4 w-4" />,
                  onClick: onViewHistory,
                },
                ...(onBookActivity
                  ? [
                      {
                        key: 'book',
                        label: 'Book Habit',
                        icon: <PlusCircle className="h-4 w-4" />,
                        onClick: onBookActivity,
                      },
                    ]
                  : []),
                ...(onLogEntry
                  ? [
                      {
                        key: 'log',
                        label: 'Log Entry',
                        icon: <PlusCircle className="h-4 w-4" />,
                        onClick: onLogEntry,
                      },
                    ]
                  : []),
                {
                  key: 'edit',
                  label: 'Edit',
                  icon: <Pencil className="h-4 w-4" />,
                  onClick: onEdit,
                },
              ],
            },
            {
              items: [
                {
                  key: 'delete',
                  label: 'Delete',
                  icon: <Trash2 className="h-4 w-4" />,
                  onClick: onDelete,
                  danger: true,
                },
              ],
            },
          ]}
        />
      </div>
    </div>
  );
}
