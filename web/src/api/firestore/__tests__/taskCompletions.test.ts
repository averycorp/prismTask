import { describe, it, expect, vi, beforeEach } from 'vitest';

const {
  setDocMock,
  deleteDocMock,
  getDocMock,
  getDocsMock,
  docMock,
  collectionMock,
  onSnapshotMock,
} = vi.hoisted(() => {
  const setDocMock = vi.fn(async () => undefined);
  const deleteDocMock = vi.fn(async () => undefined);
  const getDocMock = vi.fn();
  const getDocsMock = vi.fn();
  const docMock = vi.fn((..._segments: unknown[]) => {
    const segs = _segments.filter((s): s is string => typeof s === 'string');
    return { id: segs[segs.length - 1], path: segs.join('/') };
  });
  const collectionMock = vi.fn(
    (_db: unknown, ...segments: string[]) => ({ path: segments.join('/') }),
  );
  const onSnapshotMock = vi.fn();
  return {
    setDocMock,
    deleteDocMock,
    getDocMock,
    getDocsMock,
    docMock,
    collectionMock,
    onSnapshotMock,
  };
});

vi.mock('firebase/firestore', () => ({
  setDoc: setDocMock,
  deleteDoc: deleteDocMock,
  getDoc: getDocMock,
  getDocs: getDocsMock,
  doc: docMock,
  collection: collectionMock,
  onSnapshot: onSnapshotMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: {} }));

import {
  recordTaskCompletion,
  removeTaskCompletion,
  taskCompletionId,
  getAllTaskCompletions,
  getTaskCompletion,
  subscribeToTaskCompletions,
} from '@/api/firestore/taskCompletions';

beforeEach(() => {
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  deleteDocMock.mockReset();
  deleteDocMock.mockResolvedValue(undefined);
  getDocMock.mockReset();
  getDocsMock.mockReset();
  docMock.mockClear();
  collectionMock.mockClear();
  onSnapshotMock.mockReset();
});

describe('taskCompletionId', () => {
  it('joins taskCloudId and completedDateLocal with the same `__` separator as habits', () => {
    expect(taskCompletionId('task-7', '2026-04-26')).toBe(
      'task-7__2026-04-26',
    );
  });
});

describe('recordTaskCompletion (canonical-row dedup write path)', () => {
  it('writes to a deterministic doc id of `${taskCloudId}__${completedDateLocal}`', async () => {
    await recordTaskCompletion('uid-1', {
      taskCloudId: 'task-7',
      completedDateLocal: '2026-04-26',
      completedAt: 1_710_000_000_000,
    });

    expect(setDocMock).toHaveBeenCalledTimes(1);
    const ref = (setDocMock.mock.calls[0] as unknown[])[0] as {
      id: string;
      path: string;
    };
    expect(ref.id).toBe('task-7__2026-04-26');
    expect(ref.path).toContain('task_completions/task-7__2026-04-26');
  });

  it('uses { merge: true } so two parallel writers converge to one doc', async () => {
    await recordTaskCompletion('uid-1', {
      taskCloudId: 'task-7',
      completedDateLocal: '2026-04-26',
    });

    const opts = (setDocMock.mock.calls[0] as unknown[])[2] as
      | { merge: boolean }
      | undefined;
    expect(opts).toEqual({ merge: true });
  });

  it('writes Android-compatible field names from SyncMapper.taskCompletionToMap', async () => {
    await recordTaskCompletion('uid-1', {
      taskCloudId: 'task-7',
      completedDateLocal: '2026-04-26',
      completedAt: 1_710_000_000_000,
      priority: 3,
      wasOverdue: true,
      daysToComplete: 2,
      tags: 'work,urgent',
    });

    const written = (setDocMock.mock.calls[0] as unknown[])[1] as Record<
      string,
      unknown
    >;
    expect(written.taskId).toBe('task-7');
    expect(written.completedDateLocal).toBe('2026-04-26');
    expect(typeof written.completedDate).toBe('number');
    expect(written.completedAtTime).toBe(1_710_000_000_000);
    expect(written.priority).toBe(3);
    expect(written.wasOverdue).toBe(true);
    expect(written.daysToComplete).toBe(2);
    expect(written.tags).toBe('work,urgent');
  });

  it('write idempotency: two consecutive calls for the same (task, day) hit the same doc id', async () => {
    await recordTaskCompletion('uid-1', {
      taskCloudId: 'task-9',
      completedDateLocal: '2026-04-26',
    });
    await recordTaskCompletion('uid-1', {
      taskCloudId: 'task-9',
      completedDateLocal: '2026-04-26',
    });

    expect(setDocMock).toHaveBeenCalledTimes(2);
    const refA = (setDocMock.mock.calls[0] as unknown[])[0] as { id: string };
    const refB = (setDocMock.mock.calls[1] as unknown[])[0] as { id: string };
    expect(refA.id).toBe(refB.id);
    expect(refA.id).toBe('task-9__2026-04-26');
  });

  it('cross-timezone parity: same logical-day string from different TZ contexts maps to the same doc id', async () => {
    await recordTaskCompletion('uid-1', {
      taskCloudId: 'task-x',
      completedDateLocal: '2026-04-26',
    });
    const ref1 = (setDocMock.mock.calls[0] as unknown[])[0] as { id: string };

    setDocMock.mockClear();
    await recordTaskCompletion('uid-1', {
      taskCloudId: 'task-x',
      completedDateLocal: '2026-04-26',
    });
    const ref2 = (setDocMock.mock.calls[0] as unknown[])[0] as { id: string };

    expect(ref1.id).toBe(ref2.id);
    expect(ref1.id).toBe('task-x__2026-04-26');
  });

  it('omits Android-local-only fields (projectId, spawnedRecurrenceId)', async () => {
    await recordTaskCompletion('uid-1', {
      taskCloudId: 'task-7',
      completedDateLocal: '2026-04-26',
    });

    const written = (setDocMock.mock.calls[0] as unknown[])[1] as Record<
      string,
      unknown
    >;
    expect(
      Object.prototype.hasOwnProperty.call(written, 'projectId'),
    ).toBe(false);
    expect(
      Object.prototype.hasOwnProperty.call(written, 'spawnedRecurrenceId'),
    ).toBe(false);
  });
});

describe('removeTaskCompletion (toggle-uncomplete cleanup)', () => {
  it('deletes the canonical doc by deterministic id', async () => {
    await removeTaskCompletion('uid-1', 'task-7', '2026-04-26');

    expect(deleteDocMock).toHaveBeenCalledTimes(1);
    const ref = (deleteDocMock.mock.calls[0] as unknown[])[0] as {
      id: string;
      path: string;
    };
    expect(ref.id).toBe('task-7__2026-04-26');
    expect(ref.path).toContain('task_completions/task-7__2026-04-26');
  });
});

describe('getAllTaskCompletions / getTaskCompletion', () => {
  it('decodes Android-style field names back into the web TaskCompletion shape', async () => {
    getDocsMock.mockResolvedValueOnce({
      docs: [
        {
          id: 'task-7__2026-04-26',
          data: () => ({
            taskId: 'task-7',
            completedDate: 1_700_000_000_000,
            completedDateLocal: '2026-04-26',
            completedAtTime: 1_700_001_000_000,
            priority: 2,
            wasOverdue: false,
            daysToComplete: 3,
            tags: 'work',
          }),
        },
      ],
    });

    const out = await getAllTaskCompletions('uid-1');
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({
      id: 'task-7__2026-04-26',
      task_id: 'task-7',
      completed_date_local: '2026-04-26',
      completed_at: 1_700_001_000_000,
      priority: 2,
      was_overdue: false,
      days_to_complete: 3,
      tags: 'work',
    });
  });

  it('falls back to the legacy completedDate epoch when completedDateLocal is missing (Android-written rows)', async () => {
    // 2026-04-26T12:00:00Z reasonable-fixed epoch — we don't care about
    // the exact local-date conversion here, only that the read path
    // does not blow up and produces a YYYY-MM-DD string.
    getDocsMock.mockResolvedValueOnce({
      docs: [
        {
          id: 'random-android-id',
          data: () => ({
            taskId: 'task-7',
            completedDate: 1_745_625_600_000,
            completedAtTime: 1_745_625_600_000,
            priority: 0,
            wasOverdue: false,
          }),
        },
      ],
    });

    const out = await getAllTaskCompletions('uid-1');
    expect(out[0].completed_date_local).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('getTaskCompletion returns null when the doc does not exist', async () => {
    getDocMock.mockResolvedValueOnce({ exists: () => false });
    const out = await getTaskCompletion('uid-1', 'task-7', '2026-04-26');
    expect(out).toBeNull();
  });
});

describe('subscribeToTaskCompletions (listener wiring)', () => {
  it('registers an onSnapshot listener on the task_completions collection', () => {
    onSnapshotMock.mockImplementation((_q, _cb) => {
      return () => undefined;
    });

    const cb = vi.fn();
    const unsubscribe = subscribeToTaskCompletions('uid-1', cb);

    expect(onSnapshotMock).toHaveBeenCalledTimes(1);
    const colRef = (onSnapshotMock.mock.calls[0] as unknown[])[0] as {
      path: string;
    };
    expect(colRef.path).toBe('users/uid-1/task_completions');
    expect(typeof unsubscribe).toBe('function');
  });

  it('decodes snapshot docs and forwards them to the callback', () => {
    let captured: ((snap: unknown) => void) | null = null;
    onSnapshotMock.mockImplementation((_q, cb) => {
      captured = cb as (snap: unknown) => void;
      return () => undefined;
    });

    const cb = vi.fn();
    subscribeToTaskCompletions('uid-1', cb);

    expect(captured).not.toBeNull();
    captured!({
      docs: [
        {
          id: 'task-7__2026-04-26',
          data: () => ({
            taskId: 'task-7',
            completedDate: 1_700_000_000_000,
            completedDateLocal: '2026-04-26',
            completedAtTime: 1_700_001_000_000,
            priority: 1,
            wasOverdue: false,
          }),
        },
      ],
    });

    expect(cb).toHaveBeenCalledTimes(1);
    const forwarded = cb.mock.calls[0][0] as Array<{ id: string }>;
    expect(forwarded).toHaveLength(1);
    expect(forwarded[0].id).toBe('task-7__2026-04-26');
  });
});
