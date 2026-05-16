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
