import { describe, it, expect, vi, beforeEach } from 'vitest';

const { getMock, postMock, patchMock, deleteMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  patchMock: vi.fn(),
  deleteMock: vi.fn(),
}));

vi.mock('@/api/client', () => ({
  default: {
    get: getMock,
    post: postMock,
    patch: patchMock,
    delete: deleteMock,
  },
}));

import { leisureApi } from '@/api/leisure';

describe('leisureApi.listActivities', () => {
  beforeEach(() => {
    getMock.mockReset();
  });

  it('GETs /leisure/activities without params by default', async () => {
    getMock.mockResolvedValueOnce({ data: [] });
    await leisureApi.listActivities();
    expect(getMock).toHaveBeenCalledWith('/leisure/activities', {
      params: undefined,
    });
  });

  it('forwards enabled_only=true when requested', async () => {
    getMock.mockResolvedValueOnce({ data: [] });
    await leisureApi.listActivities(true);
    expect(getMock).toHaveBeenCalledWith('/leisure/activities', {
      params: { enabled_only: true },
    });
  });
});

describe('leisureApi.createActivity', () => {
  beforeEach(() => {
    postMock.mockReset();
  });

  it('POSTs the activity payload through', async () => {
    postMock.mockResolvedValueOnce({
      data: {
        id: 'abc',
        name: 'Yoga',
        category: 'PHYSICAL',
        default_duration_minutes: 30,
        enabled: true,
        created_at: '2026-05-13T00:00:00Z',
        updated_at: '2026-05-13T00:00:00Z',
        last_completed_at: null,
      },
    });
    await leisureApi.createActivity({
      id: 'abc',
      name: 'Yoga',
      category: 'PHYSICAL',
      default_duration_minutes: 30,
    });
    expect(postMock).toHaveBeenCalledWith('/leisure/activities', {
      id: 'abc',
      name: 'Yoga',
      category: 'PHYSICAL',
      default_duration_minutes: 30,
    });
  });

  it('rejects custom-category activities without hitting the backend', async () => {
    await expect(
      leisureApi.createActivity({
        id: 'abc',
        name: 'Origami',
        category: 'custom:abcd1234' as unknown as 'PHYSICAL',
      }),
    ).rejects.toThrow(/custom category/);
    expect(postMock).not.toHaveBeenCalled();
  });
});

describe('leisureApi.updateActivity', () => {
  beforeEach(() => {
    patchMock.mockReset();
  });

  it('PATCHes /leisure/activities/{id}', async () => {
    patchMock.mockResolvedValueOnce({
      data: {
        id: 'abc',
        name: 'Yoga',
        category: 'PHYSICAL',
        default_duration_minutes: 45,
        enabled: true,
        created_at: '2026-05-13T00:00:00Z',
        updated_at: '2026-05-13T00:01:00Z',
        last_completed_at: null,
      },
    });
    await leisureApi.updateActivity('abc', { default_duration_minutes: 45 });
    expect(patchMock).toHaveBeenCalledWith('/leisure/activities/abc', {
      default_duration_minutes: 45,
    });
  });

  it('rejects reassignment to a custom category', async () => {
    await expect(
      leisureApi.updateActivity('abc', {
        category: 'custom:wxyz' as unknown as 'PHYSICAL',
      }),
    ).rejects.toThrow(/custom category/);
    expect(patchMock).not.toHaveBeenCalled();
  });
});

describe('leisureApi.deleteActivity', () => {
  beforeEach(() => {
    deleteMock.mockReset();
  });

  it('DELETEs /leisure/activities/{id}', async () => {
    deleteMock.mockResolvedValueOnce({ status: 204 });
    await leisureApi.deleteActivity('abc');
    expect(deleteMock).toHaveBeenCalledWith('/leisure/activities/abc');
  });
});

describe('leisureApi.listSessions', () => {
  beforeEach(() => {
    getMock.mockReset();
  });

  it('forwards since + limit when provided', async () => {
    getMock.mockResolvedValueOnce({ data: [] });
    await leisureApi.listSessions({
      since: '2026-05-01T00:00:00Z',
      limit: 50,
    });
    expect(getMock).toHaveBeenCalledWith('/leisure/sessions', {
      params: { since: '2026-05-01T00:00:00Z', limit: 50 },
    });
  });

  it('omits params keys not supplied', async () => {
    getMock.mockResolvedValueOnce({ data: [] });
    await leisureApi.listSessions();
    expect(getMock).toHaveBeenCalledWith('/leisure/sessions', {
      params: {},
    });
  });
});

describe('leisureApi.createSession', () => {
  beforeEach(() => {
    postMock.mockReset();
  });

  it('POSTs the session payload', async () => {
    postMock.mockResolvedValueOnce({
      data: {
        id: 'sess',
        activity_id: 'abc',
        category: 'PHYSICAL',
        duration_minutes: 30,
        logged_at: '2026-05-13T12:00:00Z',
        source: 'MANUAL',
        created_at: '2026-05-13T12:00:00Z',
      },
    });
    await leisureApi.createSession({
      id: 'sess',
      activity_id: 'abc',
      category: 'PHYSICAL',
      duration_minutes: 30,
      logged_at: '2026-05-13T12:00:00Z',
      source: 'MANUAL',
    });
    expect(postMock).toHaveBeenCalledTimes(1);
  });

  it('rejects custom-category sessions', async () => {
    await expect(
      leisureApi.createSession({
        id: 'sess',
        category: 'custom:wxyz' as unknown as 'PHYSICAL',
        duration_minutes: 30,
        logged_at: '2026-05-13T12:00:00Z',
        source: 'MANUAL',
      }),
    ).rejects.toThrow(/custom category/);
    expect(postMock).not.toHaveBeenCalled();
  });
});

describe('leisureApi settings', () => {
  beforeEach(() => {
    getMock.mockReset();
    patchMock.mockReset();
  });

  it('GETs /leisure/settings', async () => {
    getMock.mockResolvedValueOnce({
      data: {
        daily_target_minutes: 60,
        weekend_target_minutes: null,
        enforcement_mode: 'SOFT',
        refresh_limit: 3,
        enabled_categories: ['PHYSICAL', 'SOCIAL', 'CREATIVE', 'PASSIVE'],
        pending_enforcement_mode: null,
        pending_enforcement_effective_date: null,
        updated_at: '2026-05-13T00:00:00Z',
      },
    });
    await leisureApi.getSettings();
    expect(getMock).toHaveBeenCalledWith('/leisure/settings');
  });

  it('PATCHes /leisure/settings', async () => {
    patchMock.mockResolvedValueOnce({
      data: {
        daily_target_minutes: 90,
        weekend_target_minutes: 120,
        enforcement_mode: 'SOFT',
        refresh_limit: 3,
        enabled_categories: ['PHYSICAL', 'SOCIAL', 'CREATIVE', 'PASSIVE'],
        pending_enforcement_mode: null,
        pending_enforcement_effective_date: null,
        updated_at: '2026-05-13T00:00:00Z',
      },
    });
    await leisureApi.updateSettings({
      daily_target_minutes: 90,
      weekend_target_minutes: 120,
    });
    expect(patchMock).toHaveBeenCalledWith('/leisure/settings', {
      daily_target_minutes: 90,
      weekend_target_minutes: 120,
    });
  });
});
