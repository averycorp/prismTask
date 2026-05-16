import { useMemo, useState } from 'react';
import { Clock, CalendarDays, Bell, Repeat, X } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';
import {
  AnalogClockPicker,
  useAnalogClockState,
} from '@/components/AnalogClockPicker';
import { useSettingsStore } from '@/stores/settingsStore';

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

  // Zustand v5 stable selector — primitive return, fine to subscribe directly.
  const timeFormat = useSettingsStore((s) => s.timeFormat);
  const is24Hour = timeFormat === '24h';

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
