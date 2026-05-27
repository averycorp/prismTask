export type TaskStatus = 'todo' | 'in_progress' | 'done' | 'cancelled';
export type TaskPriority = 1 | 2 | 3 | 4; // 1=urgent, 2=high, 3=medium, 4=low

export interface Task {
  id: string;
  project_id: string;
  user_id: string;
  parent_id: string | null;
  title: string;
  description: string | null;
  notes: string | null;
  status: TaskStatus;
  priority: TaskPriority;
  due_date: string | null;
  due_time: string | null;
  planned_date: string | null;
  completed_at: string | null;
  urgency_score: number;
  recurrence_json: string | null;
  eisenhower_quadrant: string | null;
  eisenhower_updated_at: string | null;
  estimated_duration: number | null;
  actual_duration: number | null;
  sort_order: number;
  depth: number;
  created_at: string;
  updated_at: string;
  subtasks?: Task[];
  tags?: import('./tag').Tag[];
  /** Firestore-persisted tag IDs. Separate from `tags` (which is
   *  hydrated by the UI's tag store) so the list of IDs survives
   *  without a per-render lookup. */
  tag_ids?: string[];
  /** Optional Work-Life Balance category. See `LifeCategory`. */
  life_category?: LifeCategory | null;
  /**
   * Optional reward / output mode (Work / Play / Relax). Orthogonal to
   * `life_category` — see `docs/WORK_PLAY_RELAX.md`.
   */
  task_mode?: TaskMode | null;
  /**
   * Optional cognitive load (Easy / Medium / Hard) — start-friction
   * dimension orthogonal to `life_category`, `task_mode`, and the
   * Eisenhower quadrant. See `docs/COGNITIVE_LOAD.md`.
   */
  cognitive_load?: CognitiveLoad | null;
  /** Whether the user manually pinned an Eisenhower quadrant. Round-tripped
   *  from Android so web doesn't undo the pin on the next save. */
  user_overrode_quadrant?: boolean;
  /** Whether the task is flagged. Round-tripped from Android. */
  is_flagged?: boolean;
  /**
   * Fractional progress in `0..100`. `null` / undefined means the task
   * is binary — its status field (`done` / not) is the source of truth
   * for burndown and progress UIs. Non-null values are only authored on
   * tasks under a project (PrismTask-timeline-class scope, PR-4; audit
   * P9 option a).
   */
  progress_percent?: number | null;
  /**
   * Optional parent-phase id (Firestore doc id of a row in
   * `project_phases`, mirroring Android `tasks.phase_id`). `null` /
   * undefined means the task belongs to the project but no phase. Set
   * from the Roadmap surface; carried through Firestore as `phaseId`.
   */
  phase_id?: string | null;
  /**
   * Focus-Release per-task override: Good-Enough Timer threshold in
   * minutes. `null` / undefined = use the global default from
   * `ndPreferencesStore`. Mirrors Android
   * `TaskEntity.good_enough_minutes_override`. Parity audit § B.8.
   */
  good_enough_minutes_override?: number | null;
  /**
   * Focus-Release per-task override: max number of revisions before
   * the anti-rework guard kicks in. `null` / undefined = use the
   * global default. Mirrors Android `TaskEntity.max_revisions_override`.
   * Parity audit § B.8.
   */
  max_revisions_override?: number | null;
  /**
   * Dormancy Re-Entry (v1.9.x). Epoch-millis timestamp of the user's last
   * session-end engagement with this task. `null` / undefined = never engaged
   * (never-engaged is NOT dormant). Mirrors Android `TaskEntity.last_engagement_at`;
   * carried through Firestore as `lastEngagementAt`.
   */
  last_engagement_at?: number | null;
  /**
   * Dormancy Re-Entry: optional one-line "where you stopped" note (≤280 chars).
   * Mirrors Android `TaskEntity.re_entry_context`; Firestore `reEntryContext`.
   */
  re_entry_context?: string | null;
  /**
   * Dormancy Re-Entry: per-task override of the global dormancy threshold in
   * days. `null` / undefined = use the global default. Mirrors Android
   * `TaskEntity.dormancy_threshold_days_override`; Firestore `dormancyThresholdDaysOverride`.
   */
  dormancy_threshold_days_override?: number | null;
}

export interface TaskCreate {
  title: string;
  description?: string;
  status?: TaskStatus;
  priority?: TaskPriority;
  due_date?: string;
  /** `HH:mm` wall-clock time. Combined with `due_date` on save. */
  due_time?: string | null;
  planned_date?: string;
  sort_order?: number;
  recurrence_json?: string;
  estimated_duration?: number;
  /** Android `is_flagged`. Only set when user explicitly toggles the
   * flag UI — omitted from the Firestore write payload otherwise. */
  isFlagged?: boolean;
  /**
   * Work-Life Balance category — must match Android `LifeCategory` enum
   * names (`WORK` | `PERSONAL` | `SELF_CARE` | `HEALTH` | `UNCATEGORIZED`).
   * Only set when the user explicitly picks one in the editor; the Firestore
   * payload omits the field otherwise so existing Android-side state isn't
   * clobbered.
   */
  lifeCategory?: LifeCategory | null;
  /**
   * Reward / output mode — must match Android `TaskMode` enum names
   * (`WORK` | `PLAY` | `RELAX` | `UNCATEGORIZED`). Same omit-when-null
   * Firestore semantics as `lifeCategory` to avoid clobbering Android.
   */
  taskMode?: TaskMode | null;
  /**
   * Cognitive load — must match Android `CognitiveLoad` enum names
   * (`EASY` | `MEDIUM` | `HARD` | `UNCATEGORIZED`). Same omit-when-null
   * Firestore semantics as `lifeCategory` to avoid clobbering Android.
   */
  cognitiveLoad?: CognitiveLoad | null;
}

export interface TaskUpdate {
  title?: string;
  description?: string;
  notes?: string;
  status?: TaskStatus;
  priority?: TaskPriority;
  due_date?: string;
  /** `HH:mm` wall-clock time, or `null` to clear. */
  due_time?: string | null;
  planned_date?: string;
  sort_order?: number;
  eisenhower_quadrant?: string;
  /** When the user manually moves a task between Eisenhower quadrants on
   *  web, callers must pass `userOverrodeQuadrant: true` so the Android
   *  auto-classifier doesn't undo the move on the next sync. */
  userOverrodeQuadrant?: boolean;
  eisenhowerReason?: string | null;
  recurrence_json?: string;
  estimated_duration?: number;
  isFlagged?: boolean;
  lifeCategory?: LifeCategory | null;
  taskMode?: TaskMode | null;
  cognitiveLoad?: CognitiveLoad | null;
  project_id?: string;
  /** Set from the Roadmap surface to attach (or detach) a task from a
   *  phase. `null` clears the phase link; `undefined` leaves it
   *  untouched (omit-on-undefined merge semantics, see PR #836). */
  phase_id?: string | null;
  /** Fractional progress 0..100 inclusive; `null` clears it. */
  progress_percent?: number | null;
  /**
   * Focus-Release per-task overrides (parity audit § B.8). `null`
   * clears the override (fall back to global default); `undefined`
   * leaves the field untouched (omit-on-undefined merge). See
   * `Task.good_enough_minutes_override` / `Task.max_revisions_override`.
   */
  good_enough_minutes_override?: number | null;
  max_revisions_override?: number | null;
}

/** Mirrors the Android `LifeCategory` enum (`domain/model/LifeCategory.kt`). */
export type LifeCategory = 'WORK' | 'PERSONAL' | 'SELF_CARE' | 'HEALTH' | 'UNCATEGORIZED';

/** Mirrors the Android `TaskMode` enum (`domain/model/TaskMode.kt`). */
export type TaskMode = 'WORK' | 'PLAY' | 'RELAX' | 'UNCATEGORIZED';

/** Mirrors the Android `CognitiveLoad` enum (`domain/model/CognitiveLoad.kt`). */
export type CognitiveLoad = 'EASY' | 'MEDIUM' | 'HARD' | 'UNCATEGORIZED';

export interface SubtaskCreate {
  title: string;
  description?: string;
  status?: TaskStatus;
  priority?: TaskPriority;
  due_date?: string;
  sort_order?: number;
}

export interface RecurrenceRule {
  type: 'daily' | 'weekly' | 'biweekly' | 'monthly' | 'yearly' | 'weekdays' | 'custom';
  interval?: number;
  days_of_week?: number[];
  days_of_month?: number[];
  end_date?: string;
  after_completion?: boolean;
  end_after_count?: number;
  occurrence_count?: number;
  skip_weekends?: boolean;
}
