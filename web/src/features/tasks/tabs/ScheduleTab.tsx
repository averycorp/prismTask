import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import {
  Clock,
  CalendarDays,
  Bell,
  Repeat,
  X,
  Plus,
  Pencil,
  Trash2,
  Timer,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import {
  AnalogClockPicker,
  useAnalogClockState,
} from '@/components/AnalogClockPicker';
import { useSettingsStore } from '@/stores/settingsStore';
import { useTaskTimingsStore } from '@/stores/taskTimingsStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import {
  updateTaskTiming,
  deleteTaskTiming,
  type TaskTiming,
} from '@/api/firestore/taskTimings';

/**
 * Schedule tab content for the task editor — mirrors Android
 * `addedittask/tabs/ScheduleTab.kt`.
 *
 * Sections (in order):
 *   1. Due Date — quick chips (Today / Tomorrow / Next Week / None) + Pick Date.
 *   2. Due Time — AnalogClockPicker modal, only when a date is set.
 *   3. Reminder — opens a reminder picker dialog (preset offsets).
 *   4. Recurrence — opens a recurrence dialog (daily/weekly/monthly + end mode).
 *   5. Estimated Duration — preset chips + custom input.
 *
 * Per the `feedback-time-input-use-clock-not-slider` memory, EVERY
 * time-of-day picker on this tab renders the 3-hand `AnalogClockPicker`
 * (hour / minute / second). The data model stores `HH:mm`, so the
 * second hand is purely visual on save.
 */

const WEEKDAYS: { idx: number; label: string }[] = [
  { idx: 1, label: 'Mon' },
  { idx: 2, label: 'Tue' },
  { idx: 3, label: 'Wed' },
  { idx: 4, label: 'Thu' },
  { idx: 5, label: 'Fri' },
  { idx: 6, label: 'Sat' },
  { idx: 0, label: 'Sun' },
];

const REMINDER_PRESETS: { value: string; label: string }[] = [
  { value: '0', label: 'At Due Time' },
  { value: '15', label: '15 Minutes Before Due' },
  { value: '30', label: '30 Minutes Before Due' },
  { value: '60', label: '1 Hour Before Due' },
  { value: '120', label: '2 Hours Before Due' },
  { value: '1440', label: '1 Day Before Due' },
];

const RECURRENCE_TYPES: { value: string; label: string }[] = [
  { value: '', label: 'None' },
  { value: 'daily', label: 'Daily' },
  { value: 'weekly', label: 'Weekly' },
  { value: 'biweekly', label: 'Biweekly' },
  { value: 'monthly', label: 'Monthly' },
  { value: 'yearly', label: 'Yearly' },
  { value: 'weekdays', label: 'Weekdays' },
];

/** Returns an ISO-date string (YYYY-MM-DD) for the local calendar day
 *  offset by `daysFromToday` (0=today, 1=tomorrow, 7=next week). */
function isoDateOffset(daysFromToday: number): string {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() + daysFromToday);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function formatReminderLabel(rawMinutes: string): string {
  const m = parseInt(rawMinutes, 10);
  if (!Number.isFinite(m)) return 'No Reminder';
  const found = REMINDER_PRESETS.find((p) => p.value === rawMinutes);
  if (found) return found.label;
  return `${m} Minutes Before Due`;
}

export interface ScheduleTabProps {
  isCreate: boolean;
  /**
   * Firestore doc id of the open task. `null` in create mode (the
   * Logged Time section needs a persisted task to FK against, so it
   * stays hidden until the first save lands a real id).
   */
  taskId?: string | null;
  dueDate: string;
  onDueDateChange: (v: string) => void;
  dueTime: string;
  onDueTimeChange: (v: string) => void;
  plannedDate: string;
  onPlannedDateChange: (v: string) => void;
  reminderOffset: string;
  onReminderOffsetChange: (v: string) => void;
  recurrenceType: string;
  onRecurrenceTypeChange: (v: string) => void;
  recurrenceInterval: number;
  onRecurrenceIntervalChange: (n: number) => void;
  recurrenceDaysOfWeek: number[];
  onRecurrenceDaysOfWeekChange: (days: number[]) => void;
  recurrenceAfterCompletion: boolean;
  onRecurrenceAfterCompletionChange: (v: boolean) => void;
  recurrenceEndMode: 'never' | 'after' | 'on';
  onRecurrenceEndModeChange: (v: 'never' | 'after' | 'on') => void;
  recurrenceEndAfter: number;
  onRecurrenceEndAfterChange: (n: number) => void;
  recurrenceEndDate: string;
  onRecurrenceEndDateChange: (v: string) => void;
  duration: string;
  onDurationChange: (v: string) => void;
}

export function ScheduleTab(props: ScheduleTabProps) {
  const {
    isCreate,
    taskId,
    dueDate,
    onDueDateChange,
    dueTime,
    onDueTimeChange,
    plannedDate,
    onPlannedDateChange,
    reminderOffset,
    onReminderOffsetChange,
    recurrenceType,
    onRecurrenceTypeChange,
    recurrenceInterval,
    onRecurrenceIntervalChange,
    recurrenceDaysOfWeek,
    onRecurrenceDaysOfWeekChange,
    recurrenceAfterCompletion,
    onRecurrenceAfterCompletionChange,
    recurrenceEndMode,
    onRecurrenceEndModeChange,
    recurrenceEndAfter,
    onRecurrenceEndAfterChange,
    recurrenceEndDate,
    onRecurrenceEndDateChange,
    duration,
    onDurationChange,
  } = props;

  const [showDatePicker, setShowDatePicker] = useState(false);
  const [showTimePicker, setShowTimePicker] = useState(false);
  const [showReminderDialog, setShowReminderDialog] = useState(false);
  const [showRecurrenceDialog, setShowRecurrenceDialog] = useState(false);
  const [loggedTimeEditing, setLoggedTimeEditing] = useState<
    TaskTiming | 'create' | null
  >(null);
  const [loggedTimeDeleting, setLoggedTimeDeleting] =
    useState<TaskTiming | null>(null);
  const [loggedTimeBusy, setLoggedTimeBusy] = useState(false);

  // Zustand v5 stable selector — primitive return, fine to subscribe directly.
  const timeFormat = useSettingsStore((s) => s.timeFormat);
  const is24Hour = timeFormat === '24h';

  // Zustand v5: subscribe to the full timings array (already a stable
  // ref between snapshots) and filter outside the selector so we don't
  // return a fresh array on every unrelated state change.
  // Memory PR #1521: fresh-object selectors trigger React #185.
  const allTimings = useTaskTimingsStore((s) => s.timings);
  const logTiming = useTaskTimingsStore((s) => s.logTiming);
  const taskTimings = useMemo(() => {
    if (!taskId) return [];
    return allTimings
      .filter((t) => t.task_id === taskId)
      .sort((a, b) => {
        // Sort by start_at DESC, falling back to created_at for manual
        // entries that didn't record a wall-clock window.
        const aKey = a.started_at ?? a.created_at;
        const bKey = b.started_at ?? b.created_at;
        return bKey - aKey;
      });
  }, [allTimings, taskId]);

  // Memoize the shortcut dates so the chip-row comparison doesn't allocate
  // a fresh string trio on every keystroke.
  const { today, tomorrow, nextWeek } = useMemo(
    () => ({
      today: isoDateOffset(0),
      tomorrow: isoDateOffset(1),
      nextWeek: isoDateOffset(7),
    }),
    [],
  );
  const hasDate = !!dueDate;
  const matchesShortcut =
    dueDate === today || dueDate === tomorrow || dueDate === nextWeek;

  return (
    <div className="flex flex-col gap-5" data-testid="task-editor-schedule-tab">
      {/* Due Date */}
      <div>
        <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
          Due Date
        </label>
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="Due Date Shortcuts">
          <DateChip
            label="Today"
            selected={dueDate === today}
            onClick={() => onDueDateChange(today)}
          />
          <DateChip
            label="Tomorrow"
            selected={dueDate === tomorrow}
            onClick={() => onDueDateChange(tomorrow)}
          />
          <DateChip
            label="Next Week"
            selected={dueDate === nextWeek}
            onClick={() => onDueDateChange(nextWeek)}
          />
          <DateChip
            label="None"
            selected={dueDate === ''}
            onClick={() => onDueDateChange('')}
          />
          {hasDate && !matchesShortcut && (
            <DateChip
              label={dueDate}
              selected
              onClick={() => setShowDatePicker(true)}
              onClear={() => onDueDateChange('')}
            />
          )}
        </div>
        <button
          type="button"
          onClick={() => setShowDatePicker(true)}
          className="mt-2 inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-[var(--color-accent)] hover:bg-[var(--color-accent)]/10"
        >
          <CalendarDays className="h-3.5 w-3.5" />
          Pick Date…
        </button>
      </div>

      {/* Due Time — only when date is set, AnalogClockPicker dialog */}
      {hasDate && (
        <div>
          <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
            Due Time
          </label>
          {dueTime ? (
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setShowTimePicker(true)}
                className="inline-flex items-center gap-1.5 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm font-medium text-[var(--color-text-primary)] hover:border-[var(--color-accent)]"
              >
                <Clock className="h-3.5 w-3.5" />
                {dueTime}
              </button>
              <button
                type="button"
                onClick={() => onDueTimeChange('')}
                aria-label="Clear Due Time"
                className="rounded-full p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setShowTimePicker(true)}
              className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-[var(--color-accent)] hover:bg-[var(--color-accent)]/10"
            >
              <Clock className="h-3.5 w-3.5" />
              Add Time
            </button>
          )}
        </div>
      )}

      {/* Planned Date */}
      <div>
        <label
          htmlFor="task-planned-date"
          className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
        >
          Planned Date (Optional)
        </label>
        <input
          id="task-planned-date"
          type="date"
          value={plannedDate}
          onChange={(e) => onPlannedDateChange(e.target.value)}
          className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        />
        <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
          Surfaces this task on the Today screen for the chosen day,
          independent of the due date.
        </p>
      </div>

      {/* Reminder */}
      <div>
        <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
          Reminders
        </label>
        {reminderOffset ? (
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setShowReminderDialog(true)}
              className="inline-flex items-center gap-1.5 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm font-medium text-[var(--color-text-primary)] hover:border-[var(--color-accent)]"
            >
              <Bell className="h-3.5 w-3.5" />
              {formatReminderLabel(reminderOffset)}
            </button>
            <button
              type="button"
              onClick={() => onReminderOffsetChange('')}
              aria-label="Clear Reminder"
              className="rounded-full p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        ) : (
          <button
            type="button"
            onClick={() => setShowReminderDialog(true)}
            className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-[var(--color-accent)] hover:bg-[var(--color-accent)]/10"
          >
            <Bell className="h-3.5 w-3.5" />
            Add Reminder
          </button>
        )}
        {reminderOffset && (
          <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
            Web notifications are local-only — reminders don't fire on
            Android until cross-device reminder scheduling lands.
          </p>
        )}
      </div>

      {/* Recurrence */}
      <div>
        <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
          Recurrence
        </label>
        {recurrenceType ? (
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => setShowRecurrenceDialog(true)}
              className="inline-flex items-center gap-1.5 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm font-medium text-[var(--color-text-primary)] hover:border-[var(--color-accent)]"
            >
              <Repeat className="h-3.5 w-3.5" />
              {summarizeRecurrence(
                recurrenceType,
                recurrenceInterval,
                recurrenceDaysOfWeek,
              )}
            </button>
            <button
              type="button"
              onClick={() => onRecurrenceTypeChange('')}
              aria-label="Clear Recurrence"
              className="rounded-full p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        ) : (
          <button
            type="button"
            onClick={() => setShowRecurrenceDialog(true)}
            className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-[var(--color-accent)] hover:bg-[var(--color-accent)]/10"
          >
            <Repeat className="h-3.5 w-3.5" />
            Set Recurrence…
          </button>
        )}
      </div>

      {/* Estimated Duration */}
      <div>
        <label
          htmlFor="task-duration-input"
          className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
        >
          Estimated Duration (Minutes)
        </label>
        <input
          id="task-duration-input"
          type="number"
          min={0}
          value={duration}
          onChange={(e) => onDurationChange(e.target.value)}
          placeholder="e.g. 30"
          className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        />
      </div>

      {/* Logged Time — edit mode only. Mirrors Android
          `ScheduleTab.kt` Logged Time section (~line 247). Manual rows
          write through `addTaskTiming` (source="manual"); Pomodoro /
          timer-sourced rows still come from Android and are rendered
          here too (sorted by start_at DESC). */}
      {!isCreate && taskId && (
        <div data-testid="task-editor-logged-time">
          <div className="mb-1 flex items-center justify-between">
            <label className="block text-xs font-medium text-[var(--color-text-secondary)]">
              Logged Time
            </label>
            <button
              type="button"
              onClick={() => setLoggedTimeEditing('create')}
              className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-[var(--color-accent)] hover:bg-[var(--color-accent)]/10"
              aria-label="Log Time"
            >
              <Plus className="h-3.5 w-3.5" />
              Log Time
            </button>
          </div>
          {taskTimings.length === 0 ? (
            <p className="text-xs text-[var(--color-text-secondary)]">
              No time logged yet. Tap "Log Time" to add a manual entry, or
              start a Pomodoro to log automatically.
            </p>
          ) : (
            <>
              <p className="mb-2 text-xs text-[var(--color-text-secondary)]">
                Total:{' '}
                <span className="font-medium text-[var(--color-text-primary)]">
                  {formatMinutes(
                    taskTimings.reduce(
                      (sum, t) => sum + t.duration_minutes,
                      0,
                    ),
                  )}
                </span>
                {' · '}
                {taskTimings.length}{' '}
                {taskTimings.length === 1 ? 'Entry' : 'Entries'}
              </p>
              <ul className="flex flex-col gap-1.5">
                {taskTimings.map((timing) => (
                  <LoggedTimeRow
                    key={timing.id}
                    timing={timing}
                    onEdit={() => setLoggedTimeEditing(timing)}
                    onDelete={() => setLoggedTimeDeleting(timing)}
                  />
                ))}
              </ul>
            </>
          )}
        </div>
      )}

      {/* Native date dialog (the browser <input type="date"> picker is the
          closest equivalent to Android's Material DatePicker; we surface
          it via a hidden <input> here for the "Pick Date…" entrypoint). */}
      {showDatePicker && (
        <DatePickModal
          value={dueDate}
          onClose={() => setShowDatePicker(false)}
          onPick={(v) => {
            onDueDateChange(v);
            setShowDatePicker(false);
          }}
        />
      )}

      {/* Analog clock dialog for Due Time */}
      {showTimePicker && (
        <TimePickerModal
          initialTime={dueTime}
          is24Hour={is24Hour}
          onClose={() => setShowTimePicker(false)}
          onSave={(hhmm) => {
            onDueTimeChange(hhmm);
            setShowTimePicker(false);
          }}
        />
      )}

      {/* Reminder dialog */}
      {showReminderDialog && (
        <ReminderDialog
          current={reminderOffset}
          onClose={() => setShowReminderDialog(false)}
          onSelect={(v) => {
            onReminderOffsetChange(v);
            setShowReminderDialog(false);
          }}
        />
      )}

      {/* Recurrence dialog */}
      {showRecurrenceDialog && (
        <RecurrenceDialog
          recurrenceType={recurrenceType}
          onTypeChange={onRecurrenceTypeChange}
          recurrenceInterval={recurrenceInterval}
          onIntervalChange={onRecurrenceIntervalChange}
          recurrenceDaysOfWeek={recurrenceDaysOfWeek}
          onDaysOfWeekChange={onRecurrenceDaysOfWeekChange}
          recurrenceAfterCompletion={recurrenceAfterCompletion}
          onAfterCompletionChange={onRecurrenceAfterCompletionChange}
          recurrenceEndMode={recurrenceEndMode}
          onEndModeChange={onRecurrenceEndModeChange}
          recurrenceEndAfter={recurrenceEndAfter}
          onEndAfterChange={onRecurrenceEndAfterChange}
          recurrenceEndDate={recurrenceEndDate}
          onEndDateChange={onRecurrenceEndDateChange}
          onClose={() => setShowRecurrenceDialog(false)}
        />
      )}

      {/* Logged Time create / edit dialog */}
      {loggedTimeEditing !== null && taskId && (
        <LoggedTimeDialog
          existing={loggedTimeEditing === 'create' ? null : loggedTimeEditing}
          busy={loggedTimeBusy}
          onClose={() => setLoggedTimeEditing(null)}
          onSave={async (input) => {
            setLoggedTimeBusy(true);
            try {
              const uid = getFirebaseUid();
              if (loggedTimeEditing === 'create') {
                // Reuse the store's optimistic `logTiming` action so
                // the new row paints into the list before the
                // Firestore snapshot lands.
                await logTiming(uid, {
                  taskCloudId: taskId,
                  startedAt: input.startedAt,
                  endedAt: input.endedAt,
                  durationMinutes: input.durationMinutes,
                  notes: input.notes,
                  source: 'manual',
                });
                toast.success('Time logged');
              } else {
                await updateTaskTiming(uid, loggedTimeEditing.id, {
                  startedAt: input.startedAt,
                  endedAt: input.endedAt,
                  durationMinutes: input.durationMinutes,
                  notes: input.notes,
                });
                toast.success('Updated entry');
              }
              setLoggedTimeEditing(null);
            } catch (e) {
              toast.error((e as Error).message || 'Failed to save entry');
            } finally {
              setLoggedTimeBusy(false);
            }
          }}
        />
      )}

      {/* Logged Time delete confirm */}
      <ConfirmDialog
        isOpen={loggedTimeDeleting !== null}
        onClose={() => setLoggedTimeDeleting(null)}
        onConfirm={async () => {
          if (!loggedTimeDeleting) return;
          setLoggedTimeBusy(true);
          try {
            const uid = getFirebaseUid();
            await deleteTaskTiming(uid, loggedTimeDeleting.id);
            toast.success('Deleted entry');
            setLoggedTimeDeleting(null);
          } catch (e) {
            toast.error((e as Error).message || 'Failed to delete entry');
          } finally {
            setLoggedTimeBusy(false);
          }
        }}
        title="Delete Logged Time?"
        message="This entry will be removed from your time-tracking history on every device. This cannot be undone."
        confirmLabel="Delete"
        variant="danger"
        loading={loggedTimeBusy}
      />
    </div>
  );
}

function DateChip({
  label,
  selected,
  onClick,
  onClear,
}: {
  label: string;
  selected: boolean;
  onClick: () => void;
  onClear?: () => void;
}) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
        selected
          ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
          : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:border-[var(--color-accent)] hover:text-[var(--color-text-primary)]'
      }`}
    >
      <button
        type="button"
        onClick={onClick}
        aria-pressed={selected}
        className="bg-transparent outline-none"
      >
        {label}
      </button>
      {onClear && (
        <button
          type="button"
          onClick={onClear}
          aria-label={`Clear ${label}`}
          className="ml-0.5 rounded-full text-current hover:opacity-70"
        >
          <X className="h-3 w-3" />
        </button>
      )}
    </span>
  );
}

function DatePickModal({
  value,
  onClose,
  onPick,
}: {
  value: string;
  onClose: () => void;
  onPick: (v: string) => void;
}) {
  const [draft, setDraft] = useState(value);
  return (
    <Modal isOpen onClose={onClose} title="Pick Date" size="sm">
      <div className="flex flex-col gap-3">
        <input
          type="date"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
          autoFocus
        />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => onPick(draft)}>Set Date</Button>
        </div>
      </div>
    </Modal>
  );
}

function TimePickerModal({
  initialTime,
  is24Hour,
  onClose,
  onSave,
}: {
  initialTime: string;
  is24Hour: boolean;
  onClose: () => void;
  onSave: (hhmm: string) => void;
}) {
  const [h, m] = parseHhMm(initialTime);
  const api = useAnalogClockState({
    initialHour: h,
    initialMinute: m,
    initialSecond: 0,
    is24Hour,
  });
  return (
    <Modal isOpen onClose={onClose} title="Pick Due Time" size="sm">
      <div className="flex flex-col items-center gap-4">
        <AnalogClockPicker api={api} />
        <div className="flex w-full justify-end gap-2 pt-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            onClick={() => {
              const hh = String(api.state.hour).padStart(2, '0');
              const mm = String(api.state.minute).padStart(2, '0');
              onSave(`${hh}:${mm}`);
            }}
          >
            Set Time
          </Button>
        </div>
      </div>
    </Modal>
  );
}

function parseHhMm(v: string): [number, number] {
  if (!v) return [9, 0];
  const m = v.match(/^(\d{1,2}):(\d{2})/);
  if (!m) return [9, 0];
  const h = Math.min(23, Math.max(0, parseInt(m[1], 10)));
  const mm = Math.min(59, Math.max(0, parseInt(m[2], 10)));
  return [h, mm];
}

function ReminderDialog({
  current,
  onClose,
  onSelect,
}: {
  current: string;
  onClose: () => void;
  onSelect: (v: string) => void;
}) {
  return (
    <Modal isOpen onClose={onClose} title="Reminder" size="sm">
      <div className="flex flex-col gap-1.5">
        {REMINDER_PRESETS.map((p) => (
          <button
            key={p.value}
            type="button"
            onClick={() => onSelect(p.value)}
            aria-pressed={current === p.value}
            className={`rounded-md border px-3 py-2 text-left text-sm transition-colors ${
              current === p.value
                ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                : 'border-[var(--color-border)] text-[var(--color-text-primary)] hover:border-[var(--color-accent)]'
            }`}
          >
            {p.label}
          </button>
        ))}
        <button
          type="button"
          onClick={() => onSelect('')}
          className="mt-1 rounded-md border border-dashed border-[var(--color-border)] px-3 py-2 text-sm text-[var(--color-text-secondary)] hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]"
        >
          No Reminder
        </button>
      </div>
    </Modal>
  );
}

function RecurrenceDialog({
  recurrenceType,
  onTypeChange,
  recurrenceInterval,
  onIntervalChange,
  recurrenceDaysOfWeek,
  onDaysOfWeekChange,
  recurrenceAfterCompletion,
  onAfterCompletionChange,
  recurrenceEndMode,
  onEndModeChange,
  recurrenceEndAfter,
  onEndAfterChange,
  recurrenceEndDate,
  onEndDateChange,
  onClose,
}: {
  recurrenceType: string;
  onTypeChange: (v: string) => void;
  recurrenceInterval: number;
  onIntervalChange: (n: number) => void;
  recurrenceDaysOfWeek: number[];
  onDaysOfWeekChange: (days: number[]) => void;
  recurrenceAfterCompletion: boolean;
  onAfterCompletionChange: (v: boolean) => void;
  recurrenceEndMode: 'never' | 'after' | 'on';
  onEndModeChange: (v: 'never' | 'after' | 'on') => void;
  recurrenceEndAfter: number;
  onEndAfterChange: (n: number) => void;
  recurrenceEndDate: string;
  onEndDateChange: (v: string) => void;
  onClose: () => void;
}) {
  return (
    <Modal isOpen onClose={onClose} title="Set Recurrence" size="md">
      <div className="flex flex-col gap-4">
        <div>
          <label
            htmlFor="recurrence-type-select"
            className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
          >
            Repeat
          </label>
          <select
            id="recurrence-type-select"
            value={recurrenceType}
            onChange={(e) => onTypeChange(e.target.value)}
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
          >
            {RECURRENCE_TYPES.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>

        {recurrenceType && (
          <>
            <div>
              <label
                htmlFor="recurrence-interval"
                className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
              >
                Every
              </label>
              <div className="flex items-center gap-2">
                <input
                  id="recurrence-interval"
                  type="number"
                  min={1}
                  value={recurrenceInterval}
                  onChange={(e) =>
                    onIntervalChange(parseInt(e.target.value) || 1)
                  }
                  className="w-20 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
                <span className="text-sm text-[var(--color-text-secondary)]">
                  {recurrenceType === 'daily'
                    ? 'Day(s)'
                    : recurrenceType === 'weekly' ||
                        recurrenceType === 'biweekly'
                      ? 'Week(s)'
                      : recurrenceType === 'monthly'
                        ? 'Month(s)'
                        : 'Year(s)'}
                </span>
              </div>
            </div>

            {(recurrenceType === 'weekly' ||
              recurrenceType === 'biweekly') && (
              <div>
                <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                  Days
                </label>
                <div className="flex flex-wrap gap-1">
                  {WEEKDAYS.map(({ idx, label }) => {
                    const selected = recurrenceDaysOfWeek.includes(idx);
                    return (
                      <button
                        key={idx}
                        type="button"
                        onClick={() =>
                          onDaysOfWeekChange(
                            selected
                              ? recurrenceDaysOfWeek.filter((d) => d !== idx)
                              : [...recurrenceDaysOfWeek, idx],
                          )
                        }
                        className={`rounded-md border px-2 py-1 text-xs font-medium transition-colors ${
                          selected
                            ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                            : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
                        }`}
                        aria-pressed={selected}
                      >
                        {label}
                      </button>
                    );
                  })}
                </div>
              </div>
            )}

            <label className="flex items-center gap-2 text-sm text-[var(--color-text-primary)]">
              <input
                type="checkbox"
                checked={recurrenceAfterCompletion}
                onChange={(e) =>
                  onAfterCompletionChange(e.target.checked)
                }
                className="h-4 w-4 rounded border-[var(--color-border)] text-[var(--color-accent)]"
              />
              Schedule Next Occurrence From When I Complete This One
            </label>

            <div>
              <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                Ends
              </label>
              <div className="flex flex-col gap-1.5">
                {(
                  [
                    { key: 'never', label: 'Never' },
                    { key: 'after', label: 'After N Occurrences' },
                    { key: 'on', label: 'On a Specific Date' },
                  ] as const
                ).map(({ key, label }) => (
                  <label
                    key={key}
                    className="flex items-center gap-2 text-sm text-[var(--color-text-primary)]"
                  >
                    <input
                      type="radio"
                      name="recurrence-end"
                      checked={recurrenceEndMode === key}
                      onChange={() => onEndModeChange(key)}
                      className="text-[var(--color-accent)]"
                    />
                    {label}
                  </label>
                ))}
              </div>
              {recurrenceEndMode === 'after' && (
                <input
                  type="number"
                  min={1}
                  value={recurrenceEndAfter}
                  onChange={(e) =>
                    onEndAfterChange(Math.max(1, Number(e.target.value) || 1))
                  }
                  className="mt-1 w-24 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
              )}
              {recurrenceEndMode === 'on' && (
                <input
                  type="date"
                  value={recurrenceEndDate}
                  onChange={(e) => onEndDateChange(e.target.value)}
                  className="mt-1 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
              )}
            </div>
          </>
        )}

        <div className="flex justify-end gap-2 pt-2">
          <Button onClick={onClose}>Done</Button>
        </div>
      </div>
    </Modal>
  );
}

function formatMinutes(totalMinutes: number): string {
  if (totalMinutes <= 0) return '0 Minutes';
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes} ${minutes === 1 ? 'Minute' : 'Minutes'}`;
  if (minutes === 0) return `${hours} ${hours === 1 ? 'Hour' : 'Hours'}`;
  return `${hours} ${hours === 1 ? 'Hour' : 'Hours'} ${minutes} ${
    minutes === 1 ? 'Minute' : 'Minutes'
  }`;
}

function formatLoggedTimeStart(timing: TaskTiming): string {
  // Manual entries with no wall-clock window fall back to created_at
  // so the row still has a sortable / readable anchor.
  const ms = timing.started_at ?? timing.created_at;
  if (!ms) return 'Unspecified';
  const d = new Date(ms);
  return d.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

function loggedTimeSourceLabel(source: string): string {
  switch (source.toLowerCase()) {
    case 'pomodoro':
      return 'Pomodoro';
    case 'timer':
      return 'Timer';
    case 'manual':
    default:
      return 'Manual';
  }
}

function LoggedTimeRow({
  timing,
  onEdit,
  onDelete,
}: {
  timing: TaskTiming;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const sourceLabel = loggedTimeSourceLabel(timing.source);
  return (
    <li
      className="flex items-start justify-between gap-2 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2"
      data-testid="logged-time-row"
    >
      <button
        type="button"
        onClick={onEdit}
        className="flex flex-1 flex-col items-start gap-0.5 text-left"
        aria-label={`Edit logged time from ${formatLoggedTimeStart(timing)}`}
      >
        <div className="flex items-center gap-2 text-sm font-medium text-[var(--color-text-primary)]">
          <Timer className="h-3.5 w-3.5 text-[var(--color-accent)]" />
          {formatMinutes(timing.duration_minutes)}
          <span className="rounded-full bg-[var(--color-bg-primary)] px-1.5 py-0.5 text-[10px] font-normal uppercase tracking-wide text-[var(--color-text-secondary)]">
            {sourceLabel}
          </span>
        </div>
        <div className="text-xs text-[var(--color-text-secondary)]">
          {formatLoggedTimeStart(timing)}
        </div>
        {timing.notes && (
          <div className="text-xs italic text-[var(--color-text-secondary)]">
            {timing.notes}
          </div>
        )}
      </button>
      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={onEdit}
          aria-label="Edit Logged Time"
          className="rounded-full p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-primary)] hover:text-[var(--color-text-primary)]"
        >
          <Pencil className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          onClick={onDelete}
          aria-label="Delete Logged Time"
          className="rounded-full p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-danger)]/10 hover:text-[var(--color-danger)]"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>
    </li>
  );
}

interface LoggedTimeDialogInput {
  startedAt: number | null;
  endedAt: number | null;
  durationMinutes: number;
  notes: string | null;
}

function LoggedTimeDialog({
  existing,
  busy,
  onClose,
  onSave,
}: {
  existing: TaskTiming | null;
  busy: boolean;
  onClose: () => void;
  onSave: (input: LoggedTimeDialogInput) => void | Promise<void>;
}) {
  const isCreate = existing === null;
  // Seed start datetime from the existing row (or now, rounded to the
  // current minute) so the <input type="datetime-local"> renders a
  // sensible default that the user can keep as-is.
  // `Date.now()` is impure, so we read it through a lazy initializer
  // (React invokes it once per mount) instead of inside `useMemo`.
  const [startStr, setStartStr] = useState(() =>
    toDatetimeLocalValue(
      existing?.started_at ?? existing?.created_at ?? Date.now(),
    ),
  );
  const [durationStr, setDurationStr] = useState(
    existing ? String(existing.duration_minutes) : '',
  );
  const [notesStr, setNotesStr] = useState(existing?.notes ?? '');

  const minutes = Number.parseInt(durationStr, 10);
  const minutesValid = Number.isFinite(minutes) && minutes > 0;

  function handleSave() {
    if (!minutesValid) {
      toast.error('Duration must be at least 1 minute');
      return;
    }
    const startedAt = startStr ? fromDatetimeLocalValue(startStr) : null;
    const endedAt =
      startedAt != null ? startedAt + minutes * 60_000 : null;
    void onSave({
      startedAt,
      endedAt,
      durationMinutes: minutes,
      notes: notesStr.trim() ? notesStr.trim() : null,
    });
  }

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={isCreate ? 'Log Time' : 'Edit Logged Time'}
      size="sm"
    >
      <div className="flex flex-col gap-3">
        <div>
          <label
            htmlFor="logged-time-start"
            className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
          >
            Start
          </label>
          <input
            id="logged-time-start"
            type="datetime-local"
            value={startStr}
            onChange={(e) => setStartStr(e.target.value)}
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
          />
        </div>
        <div>
          <label
            htmlFor="logged-time-duration"
            className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
          >
            Duration (Minutes)
          </label>
          <input
            id="logged-time-duration"
            type="number"
            min={1}
            value={durationStr}
            onChange={(e) =>
              // Filter to digits only — same shape as Android's
              // `CustomDurationDialog`. Keep up to 4 chars (max ~166h).
              setDurationStr(e.target.value.replace(/\D/g, '').slice(0, 4))
            }
            placeholder="e.g. 30"
            autoFocus={isCreate}
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
          />
        </div>
        <div>
          <label
            htmlFor="logged-time-notes"
            className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
          >
            Notes (Optional)
          </label>
          <input
            id="logged-time-notes"
            type="text"
            value={notesStr}
            onChange={(e) => setNotesStr(e.target.value)}
            placeholder="What did you work on?"
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
          />
        </div>
        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button onClick={handleSave} loading={busy} disabled={!minutesValid}>
            {isCreate ? 'Log Time' : 'Save Changes'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

/**
 * Returns a `YYYY-MM-DDTHH:mm` string in the LOCAL timezone, suitable
 * for the `<input type="datetime-local">` value attribute. The browser
 * input strips seconds, so we do too.
 */
function toDatetimeLocalValue(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}`
  );
}

/** Parse a `YYYY-MM-DDTHH:mm` LOCAL-timezone string back to epoch ms. */
function fromDatetimeLocalValue(v: string): number | null {
  // `new Date(v)` interprets a datetime-local string in the local
  // timezone (per spec), which is exactly what we want here.
  const t = Date.parse(v);
  return Number.isFinite(t) ? t : null;
}

function summarizeRecurrence(
  type: string,
  interval: number,
  daysOfWeek: number[],
): string {
  const i = Math.max(1, interval);
  switch (type) {
    case 'daily':
      return i === 1 ? 'Every Day' : `Every ${i} Days`;
    case 'weekly': {
      const base = i === 1 ? 'Every Week' : `Every ${i} Weeks`;
      if (daysOfWeek.length === 0) return base;
      const labels = [
        'Sun',
        'Mon',
        'Tue',
        'Wed',
        'Thu',
        'Fri',
        'Sat',
      ];
      const list = [...daysOfWeek]
        .sort()
        .map((d) => labels[d])
        .filter(Boolean)
        .join(', ');
      return `${base} on ${list}`;
    }
    case 'biweekly':
      return 'Every Other Week';
    case 'monthly':
      return i === 1 ? 'Every Month' : `Every ${i} Months`;
    case 'yearly':
      return i === 1 ? 'Every Year' : `Every ${i} Years`;
    case 'weekdays':
      return 'Every Weekday';
    default:
      return 'Does Not Repeat';
  }
}
