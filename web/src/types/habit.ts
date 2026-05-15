export type HabitFrequency = 'daily' | 'weekly';

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
