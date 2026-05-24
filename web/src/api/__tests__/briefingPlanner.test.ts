import { describe, it, expect, vi, beforeEach } from 'vitest';

const { postMock } = vi.hoisted(() => ({ postMock: vi.fn() }));
vi.mock('@/api/client', () => ({ default: { post: postMock } }));

import { aiApi } from '@/api/ai';

describe('aiApi.dailyBriefing', () => {
  beforeEach(() => postMock.mockReset());

  it('posts empty body by default and unwraps .data', async () => {
    postMock.mockResolvedValueOnce({
      data: {
        greeting: 'Good morning',
        top_priorities: [],
        heads_up: [],
        suggested_order: [],
        habit_reminders: [],
        day_type: 'light',
      },
    });
    const res = await aiApi.dailyBriefing();
    expect(postMock).toHaveBeenCalledWith('/ai/daily-briefing', {}, {});
    expect(res.day_type).toBe('light');
  });

  it('forwards the date param when provided', async () => {
    postMock.mockResolvedValueOnce({ data: {} });
    await aiApi.dailyBriefing({ date: '2026-04-23' });
    expect(postMock).toHaveBeenCalledWith(
      '/ai/daily-briefing',
      { date: '2026-04-23' },
      {},
    );
  });
});

describe('aiApi.weeklyPlan', () => {
  beforeEach(() => postMock.mockReset());

  it('posts preferences when supplied', async () => {
    postMock.mockResolvedValueOnce({
      data: {
        plan: {},
        unscheduled: [],
        week_summary: '',
        tips: [],
      },
    });
    await aiApi.weeklyPlan({
      preferences: {
        work_days: ['MO', 'TU'],
        focus_hours_per_day: 4,
        prefer_front_loading: false,
      },
    });
    expect(postMock).toHaveBeenCalledWith('/ai/weekly-plan', {
      preferences: {
        work_days: ['MO', 'TU'],
        focus_hours_per_day: 4,
        prefer_front_loading: false,
      },
    });
  });

  it('posts an empty body when called with no args (server defaults apply)', async () => {
    postMock.mockResolvedValueOnce({ data: {} });
    await aiApi.weeklyPlan();
    expect(postMock).toHaveBeenCalledWith('/ai/weekly-plan', {});
  });
});
