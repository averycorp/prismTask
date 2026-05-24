import apiClient from './client';
import type { EisenhowerResponse } from '@/types/api';
import type {
  DailyBriefingRequest,
  DailyBriefingResponse,
  WeeklyPlanRequest,
  WeeklyPlanResponse,
} from '@/types/briefingPlanner';
import type {
  ExtractFromTextRequest,
  ExtractFromTextResponse,
} from '@/types/extract';
import type {
  PomodoroCoachingRequest,
  PomodoroCoachingResponse,
} from '@/types/pomodoroCoaching';
import type {
  EisenhowerClassifyTextRequest,
  EisenhowerClassifyTextResponse,
} from '@/types/eisenhowerClassifyText';

export interface PomodoroRequest {
  available_minutes: number;
  session_length?: number;
  break_length?: number;
  long_break_length?: number;
  focus_preference?: 'balanced' | 'deep_work' | 'quick_wins' | 'deadline_driven';
}

export interface PomodoroSessionTask {
  task_id: string;
  title: string;
  allocated_minutes: number;
}

export interface PomodoroSession {
  session_number: number;
  tasks: PomodoroSessionTask[];
  rationale: string;
}

export interface SkippedTask {
  task_id: string;
  reason: string;
}

export interface PomodoroResponse {
  sessions: PomodoroSession[];
  total_sessions: number;
  total_work_minutes: number;
  total_break_minutes: number;
  skipped_tasks: SkippedTask[];
}

// --- Time Block ---
//
// Backend schema: backend/app/schemas/ai.py:142-172. The request field is
// `date` (not `target_date` as some prompts suggested) and the response's
// unscheduled list is `unscheduled_tasks`. task_id is a Firestore doc id
// (string) on every AI endpoint.

export interface TimeBlockRequest {
  date: string; // ISO date (YYYY-MM-DD)
  day_start?: string; // HH:mm, default 09:00
  day_end?: string; // HH:mm, default 18:00
  block_size_minutes?: number;
  include_breaks?: boolean;
  break_frequency_minutes?: number;
  break_duration_minutes?: number;
}

export interface ScheduleBlock {
  start: string; // HH:mm
  end: string; // HH:mm
  type: 'task' | 'event' | 'break';
  task_id: string | null;
  title: string;
  reason: string;
}

export interface TimeBlockUnscheduledTask {
  task_id: string;
  title: string;
  reason: string;
}

export interface TimeBlockStats {
  total_work_minutes: number;
  total_break_minutes: number;
  total_free_minutes: number;
  tasks_scheduled: number;
  tasks_deferred: number;
}

export interface TimeBlockResponse {
  schedule: ScheduleBlock[];
  unscheduled_tasks: TimeBlockUnscheduledTask[];
  stats: TimeBlockStats;
}

// --- Weekly Review (schema v2) ---
//
// Hybrid pattern: the client sends completed + slipped task lists and the
// backend enriches with open tasks from Firestore. See
// backend/app/schemas/ai.py for the authoritative schema.

export interface WeeklyTaskSummary {
  task_id: string;
  title: string;
  /** ISO datetime for completed_tasks; null for slipped_tasks. */
  completed_at: string | null;
  priority: number;
  eisenhower_quadrant: string | null;
  life_category: string | null;
  project_id: string | null;
}

export interface WeeklyReviewRequest {
  /** ISO date (YYYY-MM-DD) — Monday of the week being reviewed. */
  week_start: string;
  /** ISO date — Sunday, inclusive. */
  week_end: string;
  completed_tasks: WeeklyTaskSummary[];
  slipped_tasks: WeeklyTaskSummary[];
  /** Opaque pass-through. Omit when unavailable rather than sending {}. */
  habit_summary?: Record<string, unknown>;
  pomodoro_summary?: Record<string, unknown>;
  notes?: string | null;
}

export interface WeeklyReviewResponse {
  week_start: string;
  week_end: string;
  wins: string[];
  slips: string[];
  patterns: string[];
  next_week_focus: string[];
  narrative: string;
}

export const aiApi = {
  eisenhowerCategorize(taskIds?: string[]): Promise<EisenhowerResponse> {
    return apiClient
      .post('/ai/eisenhower', { task_ids: taskIds || null })
      .then((r) => r.data);
  },

  pomodoroPlan(data: PomodoroRequest): Promise<PomodoroResponse> {
    return apiClient
      .post('/ai/pomodoro-plan', data)
      .then((r) => r.data);
  },

  timeBlock(data: TimeBlockRequest): Promise<TimeBlockResponse> {
    return apiClient
      .post('/ai/time-block', data)
      .then((r) => r.data);
  },

  weeklyReview(data: WeeklyReviewRequest): Promise<WeeklyReviewResponse> {
    return apiClient
      .post('/ai/weekly-review', data)
      .then((r) => r.data);
  },

  dailyBriefing(
    data: DailyBriefingRequest = {},
    opts: { suppressErrorToast?: boolean } = {},
  ): Promise<DailyBriefingResponse> {
    return apiClient
      .post('/ai/daily-briefing', data, {
        suppressErrorToast: opts.suppressErrorToast,
      })
      .then((r) => r.data);
  },

  weeklyPlan(data: WeeklyPlanRequest = {}): Promise<WeeklyPlanResponse> {
    return apiClient
      .post('/ai/weekly-plan', data)
      .then((r) => r.data);
  },

  extractFromText(
    data: ExtractFromTextRequest,
  ): Promise<ExtractFromTextResponse> {
    return apiClient
      .post('/ai/parse-text', data)
      .then((r) => r.data);
  },

  pomodoroCoaching(
    data: PomodoroCoachingRequest,
  ): Promise<PomodoroCoachingResponse> {
    return apiClient
      .post('/ai/pomodoro-coaching', data)
      .then((r) => r.data);
  },

  eisenhowerClassifyText(
    data: EisenhowerClassifyTextRequest,
  ): Promise<EisenhowerClassifyTextResponse> {
    return apiClient
      .post('/ai/eisenhower/classify_text', data)
      .then((r) => r.data);
  },
};
