import { describe, it, expect, vi, beforeEach } from 'vitest';

const {
  getDocMock,
  setDocMock,
  docMock,
  collectionMock,
  queryMock,
  orderByMock,
  getDocsMock,
  onSnapshotMock,
} = vi.hoisted(() => ({
  getDocMock: vi.fn(),
  setDocMock: vi.fn(),
  docMock: vi.fn(),
  collectionMock: vi.fn(),
  queryMock: vi.fn((..._args) => ({})),
  orderByMock: vi.fn((..._args) => ({})),
  getDocsMock: vi.fn(),
  onSnapshotMock: vi.fn(),
}));

vi.mock('firebase/firestore', () => ({
  doc: docMock,
  getDoc: getDocMock,
  setDoc: setDocMock,
  collection: collectionMock,
  query: queryMock,
  orderBy: orderByMock,
  getDocs: getDocsMock,
  onSnapshot: onSnapshotMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: {} }));

import {
  getWeeklyReview,
  getRecentWeeklyReviews,
  upsertWeeklyReview,
} from '@/api/firestore/weeklyReviews';

beforeEach(() => {
  getDocMock.mockReset();
  setDocMock.mockReset();
  docMock.mockReset();
  collectionMock.mockReset();
  getDocsMock.mockReset();
  onSnapshotMock.mockReset();
  docMock.mockReturnValue({});
  collectionMock.mockReturnValue({});
});

describe('getWeeklyReview', () => {
  it('returns null when the doc does not exist', async () => {
    getDocMock.mockResolvedValueOnce({ id: '2026-05-11', exists: () => false, data: () => undefined });
    expect(await getWeeklyReview('uid-1', '2026-05-11')).toBeNull();
  });

  it('reads the persisted fields', async () => {
    getDocMock.mockResolvedValueOnce({
      id: '2026-05-11',
      exists: () => true,
      data: () => ({
        week_start_date: '2026-05-11',
        week_start_ms: 1747008000000,
        metrics_json: '{"completed_count":3}',
        ai_insights_json: '{"narrative":"..."}',
        created_at: 1,
        updated_at: 2,
      }),
    });
    const r = await getWeeklyReview('uid-1', '2026-05-11');
    expect(r).toEqual({
      id: '2026-05-11',
      weekStartDate: '2026-05-11',
      weekStartMs: 1747008000000,
      metricsJson: '{"completed_count":3}',
      aiInsightsJson: '{"narrative":"..."}',
      createdAt: 1,
      updatedAt: 2,
    });
  });

  it('targets users/{uid}/weekly_reviews/{date}', async () => {
    getDocMock.mockResolvedValueOnce({ id: '2026-05-11', exists: () => false, data: () => undefined });
    await getWeeklyReview('uid-X', '2026-05-11');
    expect(docMock).toHaveBeenCalledWith({}, 'users', 'uid-X', 'weekly_reviews', '2026-05-11');
  });
});

describe('upsertWeeklyReview', () => {
  it('writes a new doc with the given week-start as the id (Pattern A)', async () => {
    getDocMock.mockResolvedValueOnce({ exists: () => false, data: () => undefined });
    setDocMock.mockResolvedValueOnce(undefined);

    const result = await upsertWeeklyReview('uid-1', {
      weekStart: '2026-05-11',
      metricsJson: '{"completed_count":4}',
      aiInsightsJson: null,
    });

    expect(docMock).toHaveBeenLastCalledWith(
      {},
      'users',
      'uid-1',
      'weekly_reviews',
      '2026-05-11',
    );
    expect(setDocMock).toHaveBeenCalledTimes(1);
    const payload = setDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.week_start_date).toBe('2026-05-11');
    expect(payload.metrics_json).toBe('{"completed_count":4}');
    expect(payload.ai_insights_json).toBe(null);
    expect(result.weekStartDate).toBe('2026-05-11');
  });

  it('preserves createdAt on subsequent upserts', async () => {
    getDocMock.mockResolvedValueOnce({
      exists: () => true,
      data: () => ({ created_at: 12345 }),
    });
    setDocMock.mockResolvedValueOnce(undefined);

    const result = await upsertWeeklyReview('uid-1', {
      weekStart: '2026-05-11',
      metricsJson: '{}',
    });
    expect(result.createdAt).toBe(12345);
    const payload = setDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.created_at).toBe(12345);
  });
});

describe('getRecentWeeklyReviews', () => {
  it('returns docs ordered by week_start_date desc, capped at the limit', async () => {
    const docs = ['2026-05-11', '2026-05-04', '2026-04-27'].map((id) => ({
      id,
      data: () => ({
        week_start_date: id,
        week_start_ms: Date.parse(id),
        metrics_json: '{}',
        ai_insights_json: null,
        created_at: 0,
        updated_at: 0,
      }),
    }));
    getDocsMock.mockResolvedValueOnce({
      forEach: (cb: (d: (typeof docs)[number]) => void) => docs.forEach(cb),
    });
    const out = await getRecentWeeklyReviews('uid-1', 2);
    expect(out).toHaveLength(2);
    expect(out[0].weekStartDate).toBe('2026-05-11');
  });
});
