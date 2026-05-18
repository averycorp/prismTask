import { describe, it, expect, vi, beforeEach } from 'vitest';

const { addDocMock, collectionMock, onSnapshotMock, queryMock, orderByMock } =
  vi.hoisted(() => ({
    addDocMock: vi.fn(),
    collectionMock: vi.fn(
      (_db: unknown, ...segments: string[]) => ({ path: segments.join('/') }),
    ),
    onSnapshotMock: vi.fn(),
    queryMock: vi.fn((col: unknown, ..._rest: unknown[]) => col),
    orderByMock: vi.fn((field: string, dir: string) => ({
      field,
      dir,
      __orderBy: true,
    })),
  }));

vi.mock('firebase/firestore', () => ({
  addDoc: addDocMock,
  collection: collectionMock,
  onSnapshot: onSnapshotMock,
  orderBy: orderByMock,
  query: queryMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: {} }));

import {
  addTaskTiming,
  subscribeToTaskTimings,
} from '@/api/firestore/taskTimings';

beforeEach(() => {
  addDocMock.mockReset();
  addDocMock.mockResolvedValue({ id: 'generated-doc-id' });
  collectionMock.mockClear();
  onSnapshotMock.mockReset();
});

describe('addTaskTiming (Pomodoro write path)', () => {
  it('writes Android-compatible camelCase keys mirroring SyncMapper.taskTimingToMap', async () => {
    await addTaskTiming('uid-1', {
      taskCloudId: 'task-abc',
      durationMinutes: 25,
      source: 'pomodoro',
      startedAt: 1_700_000_000_000,
      endedAt: 1_700_001_500_000,
      notes: 'session 1',
      createdAt: 1_700_001_500_000,
    });

    expect(addDocMock).toHaveBeenCalledTimes(1);
    const colRef = (addDocMock.mock.calls[0] as unknown[])[0] as {
      path: string;
    };
    expect(colRef.path).toBe('users/uid-1/task_timings');
    const payload = (addDocMock.mock.calls[0] as unknown[])[1] as Record<
      string,
      unknown
    >;
    expect(payload).toEqual({
      taskId: 'task-abc',
      startedAt: 1_700_000_000_000,
      endedAt: 1_700_001_500_000,
      durationMinutes: 25,
      source: 'pomodoro',
      notes: 'session 1',
      createdAt: 1_700_001_500_000,
    });
  });

  it('defaults source to "manual" and started/ended/notes to null', async () => {
    await addTaskTiming('uid-1', {
      taskCloudId: 'task-abc',
      durationMinutes: 5,
      createdAt: 1_700_000_000_000,
    });
    const payload = (addDocMock.mock.calls[0] as unknown[])[1] as Record<
      string,
      unknown
    >;
    expect(payload.source).toBe('manual');
    expect(payload.startedAt).toBeNull();
    expect(payload.endedAt).toBeNull();
    expect(payload.notes).toBeNull();
  });

  it('uses Firestore auto-generated doc ids (no deterministic key) so multiple timings per task append cleanly', async () => {
    addDocMock.mockResolvedValueOnce({ id: 'doc-1' });
    const a = await addTaskTiming('uid-1', {
      taskCloudId: 'task-abc',
      durationMinutes: 25,
    });
    addDocMock.mockResolvedValueOnce({ id: 'doc-2' });
    const b = await addTaskTiming('uid-1', {
      taskCloudId: 'task-abc',
      durationMinutes: 25,
    });
    expect(a.id).toBe('doc-1');
    expect(b.id).toBe('doc-2');
  });

  it('throws when durationMinutes is not > 0 (mirrors TaskTimingRepository.logTime invariant)', async () => {
    await expect(
      addTaskTiming('uid-1', {
        taskCloudId: 'task-abc',
        durationMinutes: 0,
      }),
    ).rejects.toThrow(/durationMinutes/);
    expect(addDocMock).not.toHaveBeenCalled();
  });

  it('throws when taskCloudId is empty so we never write an orphaned timing', async () => {
    await expect(
      addTaskTiming('uid-1', {
        taskCloudId: '',
        durationMinutes: 25,
      }),
    ).rejects.toThrow(/taskCloudId/);
    expect(addDocMock).not.toHaveBeenCalled();
  });

  it('returns the saved TaskTiming with the generated doc id', async () => {
    addDocMock.mockResolvedValueOnce({ id: 'doc-xyz' });
    const out = await addTaskTiming('uid-1', {
      taskCloudId: 'task-abc',
      durationMinutes: 25,
      source: 'pomodoro',
      createdAt: 1_700_000_000_000,
    });
    expect(out.id).toBe('doc-xyz');
    expect(out.task_id).toBe('task-abc');
    expect(out.duration_minutes).toBe(25);
    expect(out.source).toBe('pomodoro');
    expect(out.created_at).toBe(1_700_000_000_000);
  });
});

describe('subscribeToTaskTimings (read path regression — unchanged by write parity)', () => {
  it('still queries task_timings ordered by createdAt desc', () => {
    onSnapshotMock.mockImplementation(() => () => undefined);
    subscribeToTaskTimings('uid-1', () => undefined);
    expect(orderByMock).toHaveBeenCalledWith('createdAt', 'desc');
    expect(onSnapshotMock).toHaveBeenCalledTimes(1);
  });
});
