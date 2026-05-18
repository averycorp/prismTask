/**
 * Habit frequency period. Mirrors Android `HabitEntity.frequencyPeriod`
 * (`data/local/entity/HabitEntity.kt`). Android's
 * `AddEditHabitScreen.kt:285-300` exposes all six values in its
 * segmented chooser, and `HabitListScreen.kt:71-86` splits them into a
 * "Daily" bucket (`daily`) and a "Recurring" bucket (everything else)
 * via a segmented filter at the top of the list.
 *
 * Web previously narrowed this to `'daily' | 'weekly'`, which collapsed
 * Android-created fortnightly/monthly/bimonthly/quarterly habits into
 * `daily` at read time (see `mapFrequency` in `api/firestore/habits.ts`)
 * and offered no way to express the recurring cadence in the web
 * editor. Widening here restores cross-platform parity.
 */
export type HabitFrequency =
  | 'daily'
  | 'weekly'
  | 'fortnightly'
  | 'monthly'
  | 'bimonthly'
  | 'quarterly';

/**
 * The five non-daily frequencies that Android groups under the
 * "Recurring" filter tab on the Habits screen.
 */
export const RECURRING_FREQUENCIES: readonly HabitFrequency[] = [
  'weekly',
  'fortnightly',
  'monthly',
  'bimonthly',
  'quarterly',
] as const;

export function isRecurringFrequency(frequency: HabitFrequency): boolean {
  return frequency !== 'daily';
}

export interface Habit {
  id: string;
  user_id: string;
  name: string;
  description: string | null;
  icon: string | null;
  color: string | null;
  category: string | null;
  frequency: HabitFrequency;
  target_count: number;
  active_days_json: string | null;
  is_active: boolean;
  /**
   * Mirrors Android's `HabitEntity.isBookable`. Web doesn't currently
   * write the flag from its create/update payloads (see § "Why omission
   * instead of writing-defaults" in `web/src/api/firestore/habits.ts`),
   * but Android-authored habits propagate it through Firestore so the
   * web UI reads it to surface the "Book Activity" action only on
   * bookable habits. Parity audit § B.3b.
   */
  is_bookable?: boolean;
  created_at: string;
  updated_at: string;
  // Built-in template identity (parity B.4). Optional because user-created
  // habits never carry these. Mirrors Android `HabitEntity.isBuiltIn` /
  // `templateKey` / `sourceVersion` / `isUserModified` /
  // `isDetachedFromTemplate`.
  is_built_in?: boolean;
  template_key?: string | null;
  source_version?: number;
  is_user_modified?: boolean;
  is_detached_from_template?: boolean;
  /**
   * Per-habit override for the Today-screen "skip if completed within N
   * days" window (parity B.5). Mirrors Android
   * `HabitEntity.today_skip_after_complete_days`
   * (`data/local/entity/HabitEntity.kt`). `-1` = inherit the global
   * Today-skip default; `0` = explicitly disabled for this habit; `>=1` =
   * use this many days as the window. Missing values are treated as `-1`.
   */
  today_skip_after_complete_days?: number | null;
  /**
   * Per-habit override for the Today-screen "skip if next scheduled
   * occurrence is within N days" window. Mirrors Android
   * `HabitEntity.today_skip_before_schedule_days`. Same `-1` / `0` /
   * `>=1` semantics as `today_skip_after_complete_days`.
   */
  today_skip_before_schedule_days?: number | null;
  /**
   * Per-habit override for the strict streak-grace window (1..7 days).
   * Mirrors Android `HabitEntity.streak_max_missed_days`. Sentinel-aligned
   * with Android: `undefined` ⇔ NULL on server ⇔ Android `-1` ⇔ "inherit
   * the global `streakMaxMissedDays` from `HabitListPreferences`". A value
   * `>= 1` means "use this many missed days as this habit's tolerance".
   */
  streak_max_missed_days?: number;
  /**
   * Per-habit override for the forgiveness-on/off master switch. Three
   * states (matches Android `HabitEntity.forgiveness_enabled`):
   *   `undefined` / NULL ⇔ inherit global `ForgivenessConfig.enabled`,
   *   `0` ⇔ force forgiveness OFF for this habit,
   *   `1` ⇔ force forgiveness ON for this habit.
   * The three-state shape is required because the global toggle is
   * independent of the slider — Android stores `-1` for inherit.
   */
  forgiveness_enabled?: number;
  /**
   * Per-habit override for `ForgivenessConfig.allowedMisses` (0..5).
   * Mirrors Android `HabitEntity.forgiveness_allowed_misses`. `undefined`
   * ⇔ inherit the global; `>= 0` means "use this many allowed misses".
   */
  forgiveness_allowed_misses?: number;
  /**
   * Per-habit override for `ForgivenessConfig.gracePeriodDays` (1..30).
   * Mirrors Android `HabitEntity.forgiveness_grace_period_days`.
   * `undefined` ⇔ inherit the global; `>= 1` means "use this many days".
   */
  forgiveness_grace_period_days?: number;
}

export interface HabitCreate {
  name: string;
  description?: string;
  icon?: string;
  color?: string;
  category?: string;
  frequency?: HabitFrequency;
  target_count?: number;
  active_days_json?: string;
  today_skip_after_complete_days?: number;
  today_skip_before_schedule_days?: number;
  // Per-habit streak-forgiveness overrides. See the corresponding fields
  // on `Habit` for sentinel semantics. `undefined` / `null` ⇔ inherit
  // global; `null` is the explicit "clear" signal on cross-platform
  // writes (Firestore stores NULL, Android resolves to inherit).
  streak_max_missed_days?: number | null;
  forgiveness_enabled?: number | null;
  forgiveness_allowed_misses?: number | null;
  forgiveness_grace_period_days?: number | null;
}

export interface HabitUpdate {
  name?: string;
  description?: string;
  icon?: string;
  color?: string;
  category?: string;
  frequency?: HabitFrequency;
  target_count?: number;
  active_days_json?: string;
  is_active?: boolean;
  // Built-in identity (parity B.4). Reconciler-only — never set from the
  // standard `HabitModal` editor.
  source_version?: number;
  is_user_modified?: boolean;
  is_detached_from_template?: boolean;
  /** -1 / 0 / >=1 — see `Habit.today_skip_after_complete_days` (parity B.5). */
  today_skip_after_complete_days?: number;
  /** -1 / 0 / >=1 — see `Habit.today_skip_before_schedule_days`. */
  today_skip_before_schedule_days?: number;
  // Per-habit streak-forgiveness overrides. `undefined` ⇔ "don't touch
  // the field" on update; `null` is the explicit "clear" signal — both
  // resolve to "inherit global" downstream. See the corresponding fields
  // on `Habit` for full sentinel semantics.
  streak_max_missed_days?: number | null;
  forgiveness_enabled?: number | null;
  forgiveness_allowed_misses?: number | null;
  forgiveness_grace_period_days?: number | null;
}

export interface HabitCompletion {
  id: string;
  habit_id: string;
  date: string;
  count: number;
  created_at: string;
}

export interface HabitCompletionCreate {
  date: string;
  count?: number;
}

export interface HabitWithCompletions extends Habit {
  completions?: HabitCompletion[];
}

export interface HabitStats {
  habit_id: string;
  current_streak: number;
  longest_streak: number;
  total_completions: number;
  completion_rate: number;
  completions_this_week: number;
}
