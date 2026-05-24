import { describe, it, expect, vi, beforeEach } from 'vitest';

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn() }));
vi.mock('@/api/client', () => ({ default: { get: getMock } }));

import { analyticsApi } from '@/api/analytics';

describe('analyticsApi.productivityScore', () => {
  beforeEach(() => getMock.mockReset());

  it('GETs /analytics/productivity-score with query params', async () => {
    getMock.mockResolvedValueOnce({
      data: {
        scores: [],
        average_score: 0,
        trend: 'stable',
        best_day: null,
        worst_day: null,
      },
    });
    await analyticsApi.productivityScore({
      period: 'daily',
      start_date: '2026-04-01',
      end_date: '2026-04-23',
    });
    expect(getMock).toHaveBeenCalledWith('/analytics/productivity-score', {
      params: {
        period: 'daily',
        start_date: '2026-04-01',
        end_date: '2026-04-23',
      },
    });
  });

  it('defaults to empty params when no args passed', async () => {
    getMock.mockResolvedValueOnce({
      data: {
        scores: [],
        average_score: 0,
        trend: 'stable',
        best_day: null,
        worst_day: null,
      },
    });
    await analyticsApi.productivityScore();
    expect(getMock).toHaveBeenCalledWith('/analytics/productivity-score', {
      params: {},
    });
  });
});

describe('analyticsApi.timeTracking', () => {
  beforeEach(() => getMock.mockReset());

  it('forwards group_by and date range', async () => {
    getMock.mockResolvedValueOnce({
      data: {
        entries: [],
        total_tracked_minutes: 0,
        total_estimated_minutes: 0,
        overall_accuracy_pct: 0,
        most_time_consuming_project: null,
        most_accurate_estimates: null,
      },
    });
    await analyticsApi.timeTracking({
      group_by: 'project',
      start_date: '2026-03-24',
      end_date: '2026-04-23',
    });
    expect(getMock).toHaveBeenCalledWith('/analytics/time-tracking', {
      params: {
        group_by: 'project',
        start_date: '2026-03-24',
        end_date: '2026-04-23',
      },
    });
  });
});

describe('analyticsApi.habitCorrelations', () => {
  beforeEach(() => getMock.mockReset());

  it('GETs /analytics/habit-correlations with no query params', async () => {
    getMock.mockResolvedValueOnce({
      data: { correlations: [], top_insight: '', recommendation: '' },
    });
    await analyticsApi.habitCorrelations();
    expect(getMock).toHaveBeenCalledWith('/analytics/habit-correlations', {});
  });
});
