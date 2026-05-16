import type { WeeklyReview } from '@/api/firestore/weeklyReviews';

/**
 * UI projection of a persisted `WeeklyReview` row. Web mirrors Android's
 * `WeeklyReviewContent.kt` (parity unit 12 of 23) so the History + Detail
 * screens can render *either* a cron-written row, a client-persisted
 * Free-tier row, or an AI-narrative row.
 *
 * Three producers write into `weekly_reviews` today:
 *  - `app/tasks/weekly_review_generator.py` (backend cron) — writes
 *    `metrics_json` with `completed_count` / `slipped_count` /
 *    `habit_hits` / `completion_rate` / `by_category` / `generated_by` /
 *    `generated_at_ms`. Skips `ai_insights_json` on purpose so it
 *    doesn't clobber a client-supplied narrative.
 *  - Web `WeeklyReviewScreen.tsx`'s `persistWeeklyReview` — writes
 *    `metrics_json` with `completed_count` / `slipped_count` / `wins` /
 *    `slips` / `suggestions` / `week_end_iso`, plus `ai_insights_json`
 *    when the AI call succeeded.
 *  - Android `WeeklyReviewViewModel` / `WeeklyReviewGenerator` — writes
 *    `metrics_json` with `completed` / `slipped` / `rescheduled` /
 *    `byCategory`, plus an AI-shaped or legacy `aiInsightsJson`.
 *
 * This parser detects the shape by key presence and emits a single
 * unified projection. It deliberately never throws — bad JSON falls
 * back to empty content so the screen still renders.
 */
export interface WeeklyReviewContent {
  /** AI prose narrative when present, else empty. */
  narrative: string;
  /** Aggregated counts the metric-card row renders. */
  activitySummary: WeeklyActivitySummary;
  /** Top-level AI sections, all optional. */
  wins: string[];
  slips: string[];
  patterns: string[];
  nextWeekFocus: string[];
}

export interface WeeklyActivitySummary {
  completed: number;
  slipped: number;
  /** Cron-only metric. Web persistence path doesn't write this — falls back to 0. */
  rescheduled: number;
  /** Cron-only. Bucketed by life-category. Empty when not provided. */
  byCategory: Record<string, number>;
}

function emptySummary(): WeeklyActivitySummary {
  return {
    completed: 0,
    slipped: 0,
    rescheduled: 0,
    byCategory: {},
  };
}

function emptyContent(): WeeklyReviewContent {
  return {
    narrative: '',
    activitySummary: emptySummary(),
    wins: [],
    slips: [],
    patterns: [],
    nextWeekFocus: [],
  };
}

function asInt(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value);
  if (typeof value === 'string') {
    const n = parseInt(value, 10);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function asStringList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const out: string[] = [];
  for (const item of value) {
    if (typeof item === 'string' && item.trim().length > 0) out.push(item);
  }
  return out;
}

function asNumberMap(value: unknown): Record<string, number> {
  if (value === null || value === undefined || typeof value !== 'object') {
    return {};
  }
  const out: Record<string, number> = {};
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    const n = asInt(v);
    out[k] = n;
  }
  return out;
}

export function parseMetrics(metricsJson: string | null | undefined): WeeklyActivitySummary {
  if (!metricsJson) return emptySummary();
  try {
    const obj = JSON.parse(metricsJson) as Record<string, unknown>;
    // Android shape uses `completed`/`slipped`/`rescheduled`/`byCategory`.
    // Backend cron + web persist use `completed_count`/`slipped_count`/`by_category`.
    const completed =
      'completed' in obj ? asInt(obj.completed) : asInt(obj.completed_count);
    const slipped =
      'slipped' in obj ? asInt(obj.slipped) : asInt(obj.slipped_count);
    const rescheduled = asInt(obj.rescheduled);
    const byCategory = asNumberMap(
      'byCategory' in obj ? obj.byCategory : obj.by_category,
    );
    return { completed, slipped, rescheduled, byCategory };
  } catch {
    return emptySummary();
  }
}

interface InsightsBody {
  narrative: string;
  wins: string[];
  slips: string[];
  patterns: string[];
  nextWeekFocus: string[];
}

export function parseInsights(aiInsightsJson: string | null | undefined): InsightsBody {
  const empty: InsightsBody = {
    narrative: '',
    wins: [],
    slips: [],
    patterns: [],
    nextWeekFocus: [],
  };
  if (!aiInsightsJson) return empty;
  try {
    const obj = JSON.parse(aiInsightsJson) as Record<string, unknown>;
    // Backend WeeklyReviewResponse shape ships `next_week_focus` /
    // `patterns` / `narrative`. Legacy local-narrative shape ships
    // `misses` + `suggestions`. Take whichever is present.
    const isResponseShape =
      'next_week_focus' in obj || 'patterns' in obj || 'narrative' in obj;
    if (isResponseShape) {
      return {
        narrative: asString(obj.narrative),
        wins: asStringList(obj.wins),
        slips: asStringList(obj.slips),
        patterns: asStringList(obj.patterns),
        nextWeekFocus: asStringList(obj.next_week_focus),
      };
    }
    return {
      narrative: '',
      wins: asStringList(obj.wins),
      slips: asStringList(obj.misses),
      patterns: [],
      nextWeekFocus: asStringList(obj.suggestions),
    };
  } catch {
    return empty;
  }
}

/**
 * Combined projection used by the Detail screen. The History row only
 * needs the activity summary, so callers there should use `parseMetrics`
 * directly.
 */
export function toContent(review: WeeklyReview | null | undefined): WeeklyReviewContent {
  if (!review) return emptyContent();
  const summary = parseMetrics(review.metricsJson);
  const insights = parseInsights(review.aiInsightsJson);
  return {
    narrative: insights.narrative,
    activitySummary: summary,
    wins: insights.wins,
    slips: insights.slips,
    patterns: insights.patterns,
    nextWeekFocus: insights.nextWeekFocus,
  };
}

/**
 * Pretty-print the week-of label using the row's `weekStartDate` ISO
 * (YYYY-MM-DD). Local-time formatting so the date stays anchored to the
 * user's Monday rather than UTC drifting.
 */
export function formatWeekOfLabel(weekStartDateIso: string): string {
  const [y, m, d] = weekStartDateIso.split('-').map((s) => parseInt(s, 10));
  if (!y || !m || !d) return weekStartDateIso;
  const start = new Date(y, m - 1, d);
  const end = new Date(start);
  end.setDate(end.getDate() + 6);
  const startStr = start.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
  });
  const endStr = end.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
  return `Week of ${startStr} – ${endStr}`;
}

/**
 * Short summary line used on the history list row.
 *
 * Renders empty as "No activity logged" so an empty cron-seeded week
 * doesn't look like a parse failure.
 */
export function summaryLine(summary: WeeklyActivitySummary): string {
  const { completed, slipped } = summary;
  if (completed === 0 && slipped === 0) return 'No activity logged';
  const c = `${completed} completed`;
  const s = `${slipped} slipped`;
  return `${c} · ${s}`;
}
