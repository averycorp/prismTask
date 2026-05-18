import { useState } from 'react';
import { toast } from 'sonner';
import { useHabitStore } from '@/stores/habitStore';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import type { Habit, HabitFrequency } from '@/types/habit';

/**
 * Frequency options shown in the editor's segmented chooser. Order +
 * labels mirror Android's `AddEditHabitScreen.kt:285-300`. The set is
 * exhaustive across `HabitFrequency`.
 */
const FREQUENCY_OPTIONS: ReadonlyArray<{ value: HabitFrequency; label: string }> = [
  { value: 'daily', label: 'Daily' },
  { value: 'weekly', label: 'Weekly' },
  { value: 'fortnightly', label: 'Fortnightly' },
  { value: 'monthly', label: 'Monthly' },
  { value: 'bimonthly', label: 'Bimonthly' },
  { value: 'quarterly', label: 'Quarterly' },
];

/**
 * Per-frequency upper bound on `target_count`. Mirrors Android's
 * `maxTarget` table at `AddEditHabitScreen.kt:316-324`.
 */
function maxTargetForFrequency(frequency: HabitFrequency): number {
  switch (frequency) {
    case 'weekly':
      return 7;
    case 'fortnightly':
      return 14;
    case 'monthly':
      return 30;
    case 'bimonthly':
      return 60;
    case 'quarterly':
      return 90;
    default:
      return 10;
  }
}

/**
 * Label fragment for the "How many times per X?" / "Target per X"
 * prompts. Mirrors Android's `targetLabel` switch at
 * `AddEditHabitScreen.kt:307-315`.
 */
function periodNounForFrequency(frequency: HabitFrequency): string {
  switch (frequency) {
    case 'weekly':
      return 'week';
    case 'fortnightly':
      return 'fortnight';
    case 'monthly':
      return 'month';
    case 'bimonthly':
      return '2 months';
    case 'quarterly':
      return 'quarter';
    default:
      return 'day';
  }
}

const EMOJI_OPTIONS = [
  '💪', '📚', '🧘', '💧', '🏃', '🎯', '📝', '🎨',
  '🎸', '💤', '🍎', '🧠', '❤️', '🌟', '🔥', '✨',
];

const COLOR_OPTIONS = [
  '#6366f1', '#8b5cf6', '#ec4899', '#ef4444',
  '#f97316', '#f59e0b', '#22c55e', '#10b981',
  '#14b8a6', '#06b6d4', '#3b82f6', '#6b7280',
];

const DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const DAY_VALUES = [1, 2, 3, 4, 5, 6, 7]; // ISO day of week

const TODAY_SKIP_MAX_DAYS = 30;

interface HabitModalProps {
  habit: Habit | null;
  onClose: () => void;
}

interface TodaySkipOverrideRowProps {
  title: string;
  sliderLabel: string;
  overrideAriaLabel: string;
  daysAriaLabel: string;
  overrideEnabled: boolean;
  days: number;
  onOverrideToggle: (enabled: boolean) => void;
  onDaysChange: (days: number) => void;
}

/**
 * Per-habit Today-skip override row (toggle switch + day slider).
 * Used twice in `HabitModal` for "Hide After Completion" and
 * "Hide Before Next Schedule". Mirrors the Android UI shape on
 * `AddEditHabitScreen.kt:747-869`.
 */
function TodaySkipOverrideRow({
  title,
  sliderLabel,
  overrideAriaLabel,
  daysAriaLabel,
  overrideEnabled,
  days,
  onOverrideToggle,
  onDaysChange,
}: TodaySkipOverrideRowProps) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="flex items-center justify-between gap-3">
        <div className="flex flex-col">
          <span className="text-sm text-[var(--color-text-primary)]">{title}</span>
          <span className="text-xs text-[var(--color-text-secondary)]">
            {overrideEnabled
              ? 'Override the global setting for this habit'
              : 'Use the global Today-skip setting'}
          </span>
        </div>
        <input
          type="checkbox"
          role="switch"
          checked={overrideEnabled}
          onChange={(e) => onOverrideToggle(e.target.checked)}
          className="h-5 w-9 cursor-pointer appearance-none rounded-full bg-[var(--color-border)] transition-colors checked:bg-[var(--color-accent)] relative after:absolute after:top-0.5 after:left-0.5 after:h-4 after:w-4 after:rounded-full after:bg-white after:transition-transform checked:after:translate-x-4"
          aria-label={overrideAriaLabel}
        />
      </label>
      {overrideEnabled && (
        <div className="flex flex-col gap-1">
          <div className="flex items-center justify-between">
            <span className="text-xs text-[var(--color-text-secondary)]">
              {sliderLabel}
            </span>
            <span className="text-xs font-medium text-[var(--color-accent)]">
              {days === 0
                ? 'Disabled for this habit'
                : `${days} day${days === 1 ? '' : 's'}`}
            </span>
          </div>
          <input
            type="range"
            min={0}
            max={TODAY_SKIP_MAX_DAYS}
            step={1}
            value={days}
            onChange={(e) => onDaysChange(parseInt(e.target.value, 10))}
            className="w-full accent-[var(--color-accent)]"
            aria-label={daysAriaLabel}
          />
        </div>
      )}
    </div>
  );
}

export function HabitModal({ habit, onClose }: HabitModalProps) {
  const { createHabit, updateHabit } = useHabitStore();
  const isEditing = !!habit;

  const [name, setName] = useState(habit?.name || '');
  const [description, setDescription] = useState(habit?.description || '');
  const [icon, setIcon] = useState(habit?.icon || '🎯');
  const [color, setColor] = useState(habit?.color || '#6366f1');
  const [category, setCategory] = useState(habit?.category || '');
  const [frequency, setFrequency] = useState<HabitFrequency>(
    habit?.frequency || 'daily',
  );
  const [targetCount, setTargetCount] = useState(habit?.target_count || 1);
  const [activeDays, setActiveDays] = useState<number[]>(() => {
    if (!habit?.active_days_json) return [1, 2, 3, 4, 5, 6, 7];
    try {
      const parsed = JSON.parse(habit.active_days_json);
      return Array.isArray(parsed) ? parsed : [1, 2, 3, 4, 5, 6, 7];
    } catch {
      return [1, 2, 3, 4, 5, 6, 7];
    }
  });
  // Per-habit Today-skip overrides. Mirrors Android `AddEditHabitScreen`
  // (parity audit § B.5): the override switch maps to a >= 0 stored
  // value (and -1 when off, meaning "inherit global"). The slider
  // controls the day count when override is on; 0 means "explicitly
  // disabled for this habit", 1..30 means "use this many days". Initial
  // state: switch is on iff the stored value is >= 0 (Android polarity,
  // see `AddEditHabitViewModel.kt:135` / `:137`).
  const [skipAfterCompleteOverride, setSkipAfterCompleteOverride] = useState(
    (habit?.today_skip_after_complete_days ?? -1) >= 0,
  );
  const [skipAfterCompleteDays, setSkipAfterCompleteDays] = useState(
    Math.max(0, habit?.today_skip_after_complete_days ?? 0),
  );
  const [skipBeforeScheduleOverride, setSkipBeforeScheduleOverride] = useState(
    (habit?.today_skip_before_schedule_days ?? -1) >= 0,
  );
  const [skipBeforeScheduleDays, setSkipBeforeScheduleDays] = useState(
    Math.max(0, habit?.today_skip_before_schedule_days ?? 0),
  );

  // Per-habit streak-forgiveness overrides (parity audit § B.6 / spec
  // `docs/superpowers/specs/2026-05-18-per-habit-forgiveness-design.md`).
  // Mirrors Android's `AddEditHabitScreen` toggle-reveals-slider pattern.
  // Storage shape: `undefined` ⇔ "inherit global"; a number means "use
  // this value for this habit". The override switches are on iff the
  // stored value is set (numeric) — same polarity as the Today-skip
  // rows above.
  const [streakGraceOverride, setStreakGraceOverride] = useState(
    habit?.streak_max_missed_days !== undefined &&
      habit?.streak_max_missed_days !== null &&
      habit.streak_max_missed_days >= 1,
  );
  const [streakGraceDays, setStreakGraceDays] = useState(
    Math.min(7, Math.max(1, habit?.streak_max_missed_days ?? 1)),
  );

  // Forgiveness-window override is a single switch that reveals the
  // on/off subswitch + two paired sliders (allowed misses + grace window
  // days). The switch is on iff ANY of the three Firestore fields is
  // numerically set on this habit.
  const initialForgivenessOverride =
    (habit?.forgiveness_enabled !== undefined &&
      habit?.forgiveness_enabled !== null) ||
    (habit?.forgiveness_allowed_misses !== undefined &&
      habit?.forgiveness_allowed_misses !== null &&
      habit.forgiveness_allowed_misses >= 0) ||
    (habit?.forgiveness_grace_period_days !== undefined &&
      habit?.forgiveness_grace_period_days !== null &&
      habit.forgiveness_grace_period_days >= 1);
  const [forgivenessOverride, setForgivenessOverride] = useState(
    initialForgivenessOverride,
  );
  // `forgiveness_enabled` is a three-state int on the wire (0/1/null).
  // The reveal sub-switch is bound to a boolean; treat 0/null as OFF and
  // 1 as ON. When the master override switch is OFF, this value is
  // ignored on save (we persist `null`).
  const [forgivenessEnabled, setForgivenessEnabled] = useState(
    habit?.forgiveness_enabled === 1,
  );
  const [forgivenessAllowedMisses, setForgivenessAllowedMisses] = useState(
    Math.min(
      5,
      Math.max(0, habit?.forgiveness_allowed_misses ?? 1),
    ),
  );
  const [forgivenessGraceDays, setForgivenessGraceDays] = useState(
    Math.min(
      30,
      Math.max(1, habit?.forgiveness_grace_period_days ?? 7),
    ),
  );

  const [saving, setSaving] = useState(false);

  // Get unique categories from existing habits for autocomplete
  const existingCategories = useHabitStore(
    (s) => [...new Set(s.habits.map((h) => h.category).filter(Boolean))] as string[],
  );

  const toggleDay = (day: number) => {
    setActiveDays((prev) =>
      prev.includes(day) ? prev.filter((d) => d !== day) : [...prev, day],
    );
  };

  const handleSubmit = async () => {
    if (!name.trim()) {
      toast.error('Habit name is required');
      return;
    }

    setSaving(true);
    try {
      // Resolve the Today-skip override values to the Android storage
      // shape: -1 when the override switch is off (inherit global),
      // otherwise the user-chosen day count (0 = explicitly disabled,
      // >= 1 = N-day window). Mirrors
      // `AddEditHabitViewModel.kt:340-350` `effectiveSkip*` resolution.
      const effectiveSkipAfterComplete = skipAfterCompleteOverride
        ? skipAfterCompleteDays
        : -1;
      const effectiveSkipBeforeSchedule = skipBeforeScheduleOverride
        ? skipBeforeScheduleDays
        : -1;
      // Per-habit streak-forgiveness overrides. When the override switch
      // is OFF we persist `null` so the Firestore doc carries NULL,
      // which `docToHabit` reads back as `undefined` — the TS contract
      // for "inherit global". When ON we write the slider value.
      const effectiveStreakMaxMissed = streakGraceOverride
        ? streakGraceDays
        : null;
      const effectiveForgivenessEnabled = forgivenessOverride
        ? forgivenessEnabled
          ? 1
          : 0
        : null;
      const effectiveForgivenessAllowedMisses = forgivenessOverride
        ? forgivenessAllowedMisses
        : null;
      const effectiveForgivenessGracePeriodDays = forgivenessOverride
        ? forgivenessGraceDays
        : null;
      const data = {
        name: name.trim(),
        description: description.trim() || undefined,
        icon,
        color,
        category: category.trim() || undefined,
        frequency,
        target_count: targetCount,
        active_days_json:
          frequency === 'daily' && activeDays.length < 7
            ? JSON.stringify(activeDays.sort((a, b) => a - b))
            : undefined,
        today_skip_after_complete_days: effectiveSkipAfterComplete,
        today_skip_before_schedule_days: effectiveSkipBeforeSchedule,
        streak_max_missed_days: effectiveStreakMaxMissed,
        forgiveness_enabled: effectiveForgivenessEnabled,
        forgiveness_allowed_misses: effectiveForgivenessAllowedMisses,
        forgiveness_grace_period_days: effectiveForgivenessGracePeriodDays,
      };

      if (isEditing) {
        await updateHabit(habit.id, data);
        toast.success('Habit updated');
      } else {
        await createHabit(data);
        toast.success('Habit created');
      }
      onClose();
    } catch {
      toast.error(isEditing ? 'Failed to update habit' : 'Failed to create habit');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={isEditing ? 'Edit Habit' : 'New Habit'}
      size="md"
      footer={
        <div className="flex justify-end gap-3">
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} loading={saving}>
            {isEditing ? 'Save Changes' : 'Create Habit'}
          </Button>
        </div>
      }
    >
      <div className="flex flex-col gap-5">
        {/* Name */}
        <Input
          label="Name"
          placeholder="e.g., Morning Exercise"
          value={name}
          onChange={(e) => setName(e.target.value)}
          autoFocus
        />

        {/* Description */}
        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-[var(--color-text-primary)]">
            Description
          </label>
          <textarea
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] placeholder-[var(--color-text-secondary)] outline-none transition-colors focus:border-[var(--color-accent)] focus:ring-1 focus:ring-[var(--color-accent)]"
            placeholder="Optional description..."
            rows={2}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </div>

        {/* Icon Picker */}
        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-[var(--color-text-primary)]">
            Icon
          </label>
          <div className="flex flex-wrap gap-2">
            {EMOJI_OPTIONS.map((emoji) => (
              <button
                key={emoji}
                type="button"
                onClick={() => setIcon(emoji)}
                className={`flex h-9 w-9 items-center justify-center rounded-lg text-lg transition-all ${
                  icon === emoji
                    ? 'bg-[var(--color-accent)]/15 ring-2 ring-[var(--color-accent)]'
                    : 'bg-[var(--color-bg-secondary)] hover:bg-[var(--color-bg-primary)]'
                }`}
              >
                {emoji}
              </button>
            ))}
          </div>
        </div>

        {/* Color Picker */}
        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-[var(--color-text-primary)]">
            Color
          </label>
          <div className="flex flex-wrap gap-2">
            {COLOR_OPTIONS.map((c) => (
              <button
                key={c}
                type="button"
                onClick={() => setColor(c)}
                className={`h-8 w-8 rounded-full transition-all ${
                  color === c
                    ? 'ring-2 ring-offset-2 ring-offset-[var(--color-bg-card)]'
                    : 'hover:scale-110'
                }`}
                style={{
                  backgroundColor: c,
                  ...(color === c ? { '--tw-ring-color': c } as React.CSSProperties : {}),
                }}
              />
            ))}
          </div>
        </div>

        {/* Category */}
        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-[var(--color-text-primary)]">
            Category
          </label>
          <input
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] placeholder-[var(--color-text-secondary)] outline-none transition-colors focus:border-[var(--color-accent)] focus:ring-1 focus:ring-[var(--color-accent)]"
            placeholder="e.g., Health, Fitness, Learning"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            list="habit-categories"
          />
          <datalist id="habit-categories">
            {existingCategories.map((cat) => (
              <option key={cat} value={cat} />
            ))}
          </datalist>
        </div>

        {/* Frequency — 6 options matching Android
            `AddEditHabitScreen.kt:285-300`. `daily` keeps the legacy
            "Active Days" picker below for weekday-only habits; the five
            recurring values surface under the "Recurring" tab on the
            list screen. */}
        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-[var(--color-text-primary)]">
            Frequency
          </label>
          <div className="flex flex-wrap gap-2">
            {FREQUENCY_OPTIONS.map(({ value, label }) => (
              <button
                key={value}
                type="button"
                onClick={() => {
                  setFrequency(value);
                  // Clamp target_count to the new period's max — mirrors
                  // Android `onFrequencyPeriodChange` at
                  // `AddEditHabitViewModel.kt:175-194`.
                  setTargetCount((prev) =>
                    Math.min(prev, maxTargetForFrequency(value)),
                  );
                }}
                className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
                  frequency === value
                    ? 'bg-[var(--color-accent)] text-white'
                    : 'bg-[var(--color-bg-secondary)] text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        {/* Target Count */}
        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-[var(--color-text-primary)]">
            How many times per {periodNounForFrequency(frequency)}?
          </label>
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => setTargetCount(Math.max(1, targetCount - 1))}
              className="flex h-8 w-8 items-center justify-center rounded-lg bg-[var(--color-bg-secondary)] text-[var(--color-text-primary)] hover:bg-[var(--color-border)] transition-colors"
            >
              -
            </button>
            <span className="w-8 text-center text-sm font-medium text-[var(--color-text-primary)]">
              {targetCount}
            </span>
            <button
              type="button"
              onClick={() =>
                setTargetCount(
                  Math.min(maxTargetForFrequency(frequency), targetCount + 1),
                )
              }
              className="flex h-8 w-8 items-center justify-center rounded-lg bg-[var(--color-bg-secondary)] text-[var(--color-text-primary)] hover:bg-[var(--color-border)] transition-colors"
            >
              +
            </button>
          </div>
        </div>

        {/* Active Days (daily only) */}
        {frequency === 'daily' && (
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-[var(--color-text-primary)]">
              Active Days
            </label>
            <div className="flex gap-2">
              {DAY_LABELS.map((label, idx) => {
                const dayValue = DAY_VALUES[idx];
                const isActive = activeDays.includes(dayValue);
                return (
                  <button
                    key={dayValue}
                    type="button"
                    onClick={() => toggleDay(dayValue)}
                    className={`flex h-9 w-9 items-center justify-center rounded-full text-xs font-medium transition-colors ${
                      isActive
                        ? 'bg-[var(--color-accent)] text-white'
                        : 'bg-[var(--color-bg-secondary)] text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
                    }`}
                  >
                    {label}
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {/* Today Page Visibility (per-habit overrides; parity audit § B.5). */}
        {/* Mirrors Android `AddEditHabitScreen` Today-skip section
            (`app/.../ui/screens/habits/AddEditHabitScreen.kt:747-869`). */}
        <div className="flex flex-col gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)]/40 p-3">
          <div className="flex flex-col gap-0.5">
            <span className="text-sm font-medium text-[var(--color-text-primary)]">
              Today Page Visibility
            </span>
            <span className="text-xs text-[var(--color-text-secondary)]">
              Hide this habit from the Today screen around recent
              completions or upcoming scheduled occurrences.
            </span>
          </div>

          <TodaySkipOverrideRow
            title="Hide After Completion"
            sliderLabel="Hide on Today for"
            overrideAriaLabel="Override Today-skip after completion"
            daysAriaLabel="Today-skip days after completion"
            overrideEnabled={skipAfterCompleteOverride}
            days={skipAfterCompleteDays}
            onOverrideToggle={(next) => {
              setSkipAfterCompleteOverride(next);
              // Switching on with a 0-day window means "disabled for
              // this habit" (no effect) — seed to 1 so the slider lands
              // somewhere useful. Mirrors Android
              // `AddEditHabitViewModel.kt:279-284`.
              if (next && skipAfterCompleteDays === 0) {
                setSkipAfterCompleteDays(1);
              }
            }}
            onDaysChange={setSkipAfterCompleteDays}
          />

          <TodaySkipOverrideRow
            title="Hide Before Next Schedule"
            sliderLabel="Hide if next occurrence within"
            overrideAriaLabel="Override Today-skip before next schedule"
            daysAriaLabel="Today-skip days before next schedule"
            overrideEnabled={skipBeforeScheduleOverride}
            days={skipBeforeScheduleDays}
            onOverrideToggle={(next) => {
              setSkipBeforeScheduleOverride(next);
              if (next && skipBeforeScheduleDays === 0) {
                setSkipBeforeScheduleDays(1);
              }
            }}
            onDaysChange={setSkipBeforeScheduleDays}
          />
        </div>

        {/* Streak Forgiveness (per-habit overrides; parity audit § B.6).
            Mirrors Android `AddEditHabitScreen` Streak Forgiveness
            section. Three blocks total: (1) override Streak Grace Period,
            (2) override Forgiveness Window (forgiveness on/off + allowed
            misses + grace window paired sliders), (3) footer caption. */}
        <div className="flex flex-col gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)]/40 p-3">
          <div className="flex flex-col gap-0.5">
            <span className="text-sm font-medium text-[var(--color-text-primary)]">
              Streak Forgiveness
            </span>
            <span className="text-xs text-[var(--color-text-secondary)]">
              Override the global streak tolerance for this habit only.
            </span>
          </div>

          {/* Block 1 — Streak Grace Period (1..7) */}
          <div className="flex flex-col gap-1.5">
            <label className="flex items-center justify-between gap-3">
              <div className="flex flex-col">
                <span className="text-sm text-[var(--color-text-primary)]">
                  Override Streak Grace Period
                </span>
                <span className="text-xs text-[var(--color-text-secondary)]">
                  {streakGraceOverride
                    ? 'Use this many missed days before the streak breaks'
                    : 'Using global setting'}
                </span>
              </div>
              <input
                type="checkbox"
                role="switch"
                checked={streakGraceOverride}
                onChange={(e) => setStreakGraceOverride(e.target.checked)}
                className="h-5 w-9 cursor-pointer appearance-none rounded-full bg-[var(--color-border)] transition-colors checked:bg-[var(--color-accent)] relative after:absolute after:top-0.5 after:left-0.5 after:h-4 after:w-4 after:rounded-full after:bg-white after:transition-transform checked:after:translate-x-4"
                aria-label="Override streak grace period"
              />
            </label>
            {streakGraceOverride && (
              <div className="flex flex-col gap-1">
                <div className="flex items-center justify-between">
                  <span className="text-xs text-[var(--color-text-secondary)]">
                    Missed days tolerated
                  </span>
                  <span className="text-xs font-medium text-[var(--color-accent)]">
                    {streakGraceDays} day{streakGraceDays === 1 ? '' : 's'}
                  </span>
                </div>
                <input
                  type="range"
                  min={1}
                  max={7}
                  step={1}
                  value={streakGraceDays}
                  onChange={(e) =>
                    setStreakGraceDays(parseInt(e.target.value, 10))
                  }
                  className="w-full accent-[var(--color-accent)]"
                  aria-label="Streak grace period days"
                />
              </div>
            )}
          </div>

          {/* Block 2 — Forgiveness Window (on/off + allowed misses + grace
              window). The two sliders reveal together because they pair
              semantically (mirrors Android Settings → Forgiveness-First
              Streaks). */}
          <div className="flex flex-col gap-1.5">
            <label className="flex items-center justify-between gap-3">
              <div className="flex flex-col">
                <span className="text-sm text-[var(--color-text-primary)]">
                  Override Forgiveness Window
                </span>
                <span className="text-xs text-[var(--color-text-secondary)]">
                  {forgivenessOverride
                    ? 'Use a custom forgiveness window for this habit'
                    : 'Using global setting'}
                </span>
              </div>
              <input
                type="checkbox"
                role="switch"
                checked={forgivenessOverride}
                onChange={(e) => setForgivenessOverride(e.target.checked)}
                className="h-5 w-9 cursor-pointer appearance-none rounded-full bg-[var(--color-border)] transition-colors checked:bg-[var(--color-accent)] relative after:absolute after:top-0.5 after:left-0.5 after:h-4 after:w-4 after:rounded-full after:bg-white after:transition-transform checked:after:translate-x-4"
                aria-label="Override forgiveness window"
              />
            </label>
            {forgivenessOverride && (
              <div className="flex flex-col gap-3 pl-1">
                {/* Forgiveness on/off subswitch */}
                <label className="flex items-center justify-between gap-3">
                  <span className="text-xs text-[var(--color-text-secondary)]">
                    Forgiveness {forgivenessEnabled ? 'enabled' : 'disabled'}
                  </span>
                  <input
                    type="checkbox"
                    role="switch"
                    checked={forgivenessEnabled}
                    onChange={(e) => setForgivenessEnabled(e.target.checked)}
                    className="h-5 w-9 cursor-pointer appearance-none rounded-full bg-[var(--color-border)] transition-colors checked:bg-[var(--color-accent)] relative after:absolute after:top-0.5 after:left-0.5 after:h-4 after:w-4 after:rounded-full after:bg-white after:transition-transform checked:after:translate-x-4"
                    aria-label="Forgiveness enabled for this habit"
                  />
                </label>

                {/* Allowed Misses slider (0..5) */}
                <div className="flex flex-col gap-1">
                  <div className="flex items-center justify-between">
                    <span className="text-xs text-[var(--color-text-secondary)]">
                      Allowed misses
                    </span>
                    <span className="text-xs font-medium text-[var(--color-accent)]">
                      {forgivenessAllowedMisses}
                    </span>
                  </div>
                  <input
                    type="range"
                    min={0}
                    max={5}
                    step={1}
                    value={forgivenessAllowedMisses}
                    onChange={(e) =>
                      setForgivenessAllowedMisses(parseInt(e.target.value, 10))
                    }
                    className="w-full accent-[var(--color-accent)]"
                    aria-label="Allowed misses inside the grace window"
                    disabled={!forgivenessEnabled}
                  />
                </div>

                {/* Grace Window slider (1..30) */}
                <div className="flex flex-col gap-1">
                  <div className="flex items-center justify-between">
                    <span className="text-xs text-[var(--color-text-secondary)]">
                      Grace window
                    </span>
                    <span className="text-xs font-medium text-[var(--color-accent)]">
                      {forgivenessGraceDays} day
                      {forgivenessGraceDays === 1 ? '' : 's'}
                    </span>
                  </div>
                  <input
                    type="range"
                    min={1}
                    max={30}
                    step={1}
                    value={forgivenessGraceDays}
                    onChange={(e) =>
                      setForgivenessGraceDays(parseInt(e.target.value, 10))
                    }
                    className="w-full accent-[var(--color-accent)]"
                    aria-label="Forgiveness grace window days"
                    disabled={!forgivenessEnabled}
                  />
                </div>
              </div>
            )}
          </div>

          {/* Block 3 — footer caption */}
          <p className="text-xs text-[var(--color-text-secondary)]">
            Defaults live in Settings.
          </p>
        </div>
      </div>
    </Modal>
  );
}
