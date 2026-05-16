import type { Task } from './task';

export interface ApiError {
  detail: string;
  status_code?: number;
}

export interface DashboardSummary {
  total_tasks: number;
  completed_tasks: number;
  overdue_tasks: number;
  today_tasks: number;
  upcoming_tasks: number;
  completion_rate: number;
}

export interface TaskListResponse {
  tasks: Task[];
  count: number;
}

export interface NLPParseRequest {
  text: string;
}

export interface NLPParseResult {
  title: string;
  project_suggestion: string | null;
  due_date: string | null;
  /**
   * HH:MM 24-hour time-of-day component when the user supplied a time
   * (e.g. "at 3pm" → "15:00"). Backend may omit this field — treat as
   * optional. Mirrors Android's `ParsedTaskResponse.dueTime`.
   */
  due_time?: string | null;
  priority: number | null;
  parent_task_suggestion: string | null;
  /**
   * Tag names suggested by the parser. Currently the backend `ParseResponse`
   * does not emit these; the field is present so callers can opportunistically
   * merge backend tags with locally-parsed ones without an extra type-cast.
   * Mirrors Android's `ParsedTaskResponse.tagSuggestions`.
   */
  tag_suggestions?: string[] | null;
  /**
   * Recurrence hint ("daily", "weekly", "monthly", "yearly"). Same status
   * as [tag_suggestions] — present for forward-compat, currently always
   * null from the live backend.
   */
  recurrence_hint?: string | null;
  confidence: number;
  suggestions: string[];
  needs_confirmation: boolean;
}

export interface EisenhowerCategorization {
  task_id: string;
  quadrant: 'Q1' | 'Q2' | 'Q3' | 'Q4';
  reason: string;
}

export interface EisenhowerResponse {
  categorizations: EisenhowerCategorization[];
  summary: {
    Q1?: number;
    Q2?: number;
    Q3?: number;
    Q4?: number;
  };
}

export interface AnalyticsSummary {
  today: { completed: number; remaining: number; score: number };
  this_week: { completed: number; remaining: number; score: number; trend: string };
  this_month: { completed: number; remaining: number; score: number };
  streaks: { current_productive_days: number; longest_productive_days: number };
  habits: { completion_rate_7d: number; completion_rate_30d: number };
}
