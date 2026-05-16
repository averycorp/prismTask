import { describe, expect, it } from 'vitest';
import {
  formatWeekOfLabel,
  parseInsights,
  parseMetrics,
  summaryLine,
  toContent,
} from '../weeklyReviewContent';
import type { WeeklyReview } from '@/api/firestore/weeklyReviews';

describe('parseMetrics', () => {
  it('returns zero summary for null/empty input', () => {
    expect(parseMetrics(null)).toEqual({
      completed: 0,
      slipped: 0,
      rescheduled: 0,
      byCategory: {},
    });
    expect(parseMetrics('')).toEqual({
      completed: 0,
      slipped: 0,
      rescheduled: 0,
      byCategory: {},
    });
  });

  it('reads backend cron / web persist shape (snake_case)', () => {
    const json = JSON.stringify({
      completed_count: 5,
      slipped_count: 2,
      by_category: { work: 3, self_care: 2 },
      habit_hits: 7,
    });
    const out = parseMetrics(json);
    expect(out.completed).toBe(5);
    expect(out.slipped).toBe(2);
    expect(out.byCategory).toEqual({ work: 3, self_care: 2 });
    expect(out.rescheduled).toBe(0);
  });

  it('reads Android shape (camelCase / unsuffixed)', () => {
    const json = JSON.stringify({
      completed: 4,
      slipped: 1,
      rescheduled: 6,
      byCategory: { work: 2 },
    });
    const out = parseMetrics(json);
    expect(out.completed).toBe(4);
    expect(out.slipped).toBe(1);
    expect(out.rescheduled).toBe(6);
    expect(out.byCategory).toEqual({ work: 2 });
  });

  it('returns zero summary on malformed JSON', () => {
    expect(parseMetrics('{not json')).toEqual({
      completed: 0,
      slipped: 0,
      rescheduled: 0,
      byCategory: {},
    });
  });
});

describe('parseInsights', () => {
  it('returns empty body for null', () => {
    const body = parseInsights(null);
    expect(body.narrative).toBe('');
    expect(body.wins).toEqual([]);
    expect(body.slips).toEqual([]);
    expect(body.patterns).toEqual([]);
    expect(body.nextWeekFocus).toEqual([]);
  });

  it('reads WeeklyReviewResponse shape', () => {
    const json = JSON.stringify({
      narrative: 'A solid week.',
      wins: ['Shipped the cron'],
      slips: ['Skipped Tuesday review'],
      patterns: ['Morning focus is strongest'],
      next_week_focus: ['Lock the deep work blocks'],
    });
    const body = parseInsights(json);
    expect(body.narrative).toBe('A solid week.');
    expect(body.wins).toEqual(['Shipped the cron']);
    expect(body.slips).toEqual(['Skipped Tuesday review']);
    expect(body.patterns).toEqual(['Morning focus is strongest']);
    expect(body.nextWeekFocus).toEqual(['Lock the deep work blocks']);
  });

  it('reads legacy local-narrative shape (misses/suggestions)', () => {
    const json = JSON.stringify({
      wins: ['Showed up'],
      misses: ['Missed the gym'],
      suggestions: ['Try a lighter plan'],
    });
    const body = parseInsights(json);
    expect(body.narrative).toBe('');
    expect(body.wins).toEqual(['Showed up']);
    expect(body.slips).toEqual(['Missed the gym']);
    expect(body.nextWeekFocus).toEqual(['Try a lighter plan']);
    expect(body.patterns).toEqual([]);
  });

  it('filters out empty strings in list payloads', () => {
    const json = JSON.stringify({
      narrative: '',
      wins: ['', 'real win', '   '],
    });
    const body = parseInsights(json);
    expect(body.wins).toEqual(['real win']);
  });
});

describe('toContent', () => {
  it('handles null review', () => {
    const content = toContent(null);
    expect(content.narrative).toBe('');
    expect(content.activitySummary.completed).toBe(0);
  });

  it('combines metrics + insights', () => {
    const review: WeeklyReview = {
      id: '2026-05-04',
      weekStartDate: '2026-05-04',
      weekStartMs: 0,
      metricsJson: JSON.stringify({ completed_count: 3, slipped_count: 1 }),
      aiInsightsJson: JSON.stringify({
        narrative: 'Nice rhythm.',
        wins: ['Stayed consistent'],
      }),
      createdAt: 0,
      updatedAt: 0,
    };
    const content = toContent(review);
    expect(content.activitySummary.completed).toBe(3);
    expect(content.activitySummary.slipped).toBe(1);
    expect(content.narrative).toBe('Nice rhythm.');
    expect(content.wins).toEqual(['Stayed consistent']);
  });
});

describe('formatWeekOfLabel', () => {
  it('renders Week of <start> – <end>', () => {
    const label = formatWeekOfLabel('2026-05-04');
    expect(label).toContain('Week of');
    expect(label).toContain('May 4');
    expect(label).toContain('May 10');
    expect(label).toContain('2026');
  });

  it('falls back to raw ISO for malformed input', () => {
    expect(formatWeekOfLabel('not-a-date')).toBe('not-a-date');
  });
});

describe('summaryLine', () => {
  it('renders "No activity logged" when both counts are zero', () => {
    expect(
      summaryLine({ completed: 0, slipped: 0, rescheduled: 0, byCategory: {} }),
    ).toBe('No activity logged');
  });

  it('renders the count summary otherwise', () => {
    expect(
      summaryLine({ completed: 3, slipped: 1, rescheduled: 0, byCategory: {} }),
    ).toBe('3 completed · 1 slipped');
  });
});
