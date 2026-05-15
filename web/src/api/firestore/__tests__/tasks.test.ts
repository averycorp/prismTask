import { describe, it, expect, vi, beforeEach } from 'vitest';

const {
  addDocMock,
  updateDocMock,
  setDocMock,
  getDocMock,
  txGetMock,
  runTransactionMock,
  docMock,
  collectionMock,
} = vi.hoisted(() => ({
  addDocMock: vi.fn(),
  updateDocMock: vi.fn(),
  setDocMock: vi.fn(),
  getDocMock: vi.fn(),
  txGetMock: vi.fn(),
  runTransactionMock: vi.fn(),
  docMock: vi.fn(),
  collectionMock: vi.fn(),
}));

vi.mock('firebase/firestore', () => ({
  addDoc: addDocMock,
  updateDoc: updateDocMock,
  setDoc: setDocMock,
  getDoc: getDocMock,
  runTransaction: runTransactionMock,
  doc: docMock,
  collection: collectionMock,
  // Unused-by-these-tests primitives — stubbed so the module loads.
  getDocs: vi.fn(),
  deleteDoc: vi.fn(),
  query: vi.fn(),
  where: vi.fn(),
  orderBy: vi.fn(),
  onSnapshot: vi.fn(),
}));
vi.mock('@/lib/firebase', () => ({ firestore: {} }));

import { createTask, updateTask } from '@/api/firestore/tasks';

beforeEach(() => {
  addDocMock.mockReset();
  updateDocMock.mockReset();
  setDocMock.mockReset();
  getDocMock.mockReset();
  txGetMock.mockReset();
  runTransactionMock.mockReset();
  docMock.mockReset();
  collectionMock.mockReset();
  docMock.mockReturnValue({ path: 'users/uid-1/tasks/task-1' });
  collectionMock.mockReturnValue({});
  // addDoc returns a fake ref with an id
  addDocMock.mockResolvedValue({ id: 'new-task-id' });
  // Default runTransaction: invokes the callback with a tx that delegates
  // `get` to txGetMock (returning a doc-exists snapshot whose updatedAt is
  // older than `Date.now()` so the LWW guard always applies) and
  // `update`/`set` to the corresponding mocks. Individual tests override
  // `txGetMock` to inject newer remote timestamps (stale-write cases).
  txGetMock.mockResolvedValue({
    exists: () => true,
    data: () => ({ updatedAt: 0 }),
  });
  runTransactionMock.mockImplementation(async (_db: unknown, fn: (tx: unknown) => Promise<unknown>) => {
    const tx = {
      get: (ref: unknown) => txGetMock(ref),
      update: (ref: unknown, patch: unknown) => updateDocMock(ref, patch),
      set: (ref: unknown, patch: unknown, opts?: unknown) => setDocMock(ref, patch, opts),
    };
    return fn(tx);
  });
});

describe('createTask payload shape', () => {
  it('does not write `dueTime: null` when no due_time was provided', async () => {
    await createTask('uid-1', { title: 'No time task' });
    expect(addDocMock).toHaveBeenCalledTimes(1);
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect('dueTime' in payload).toBe(false);
  });

  it('writes dueTime when due_time + due_date are both provided', async () => {
    await createTask('uid-1', {
      title: 'Lunch',
      due_date: '2026-05-01',
      due_time: '12:00',
    });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.dueTime).toEqual(expect.any(Number));
    // 2026-05-01T12:00 local-time millis
    const expected = new Date('2026-05-01T12:00:00').getTime();
    expect(payload.dueTime).toBe(expected);
  });

  it('does not write `isFlagged` when the user did not toggle it', async () => {
    await createTask('uid-1', { title: 'Just a title' });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect('isFlagged' in payload).toBe(false);
  });

  it('writes isFlagged: true only when explicitly set', async () => {
    await createTask('uid-1', {
      title: 'Flagged',
      isFlagged: true,
    } as Parameters<typeof createTask>[1] & { isFlagged?: boolean });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.isFlagged).toBe(true);
  });

  it('does not write `lifeCategory` when not provided', async () => {
    await createTask('uid-1', { title: 'No category' });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect('lifeCategory' in payload).toBe(false);
  });

  it('writes lifeCategory when explicitly set', async () => {
    await createTask('uid-1', {
      title: 'Self-care task',
      lifeCategory: 'SELF_CARE',
    } as Parameters<typeof createTask>[1] & { lifeCategory?: string });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.lifeCategory).toBe('SELF_CARE');
  });

  it('writes taskMode when explicitly set', async () => {
    await createTask('uid-1', {
      title: 'Recharge walk',
      taskMode: 'RELAX',
    } as Parameters<typeof createTask>[1] & { taskMode?: string });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.taskMode).toBe('RELAX');
  });

  it('writes cognitiveLoad when explicitly set', async () => {
    await createTask('uid-1', {
      title: 'Tax filing',
      cognitiveLoad: 'HARD',
    } as Parameters<typeof createTask>[1] & { cognitiveLoad?: string });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.cognitiveLoad).toBe('HARD');
  });

  it('does not write taskMode or cognitiveLoad when not provided', async () => {
    await createTask('uid-1', { title: 'No dimensions' });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect('taskMode' in payload).toBe(false);
    expect('cognitiveLoad' in payload).toBe(false);
  });

  it('does not write Android-only fields (archivedAt, eisenhowerReason, focus-release) on create', async () => {
    await createTask('uid-1', { title: 'Plain task' });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect('archivedAt' in payload).toBe(false);
    expect('eisenhowerReason' in payload).toBe(false);
    expect('userOverrodeQuadrant' in payload).toBe(false);
    expect('goodEnoughMinutesOverride' in payload).toBe(false);
    expect('maxRevisionsOverride' in payload).toBe(false);
    expect('revisionCount' in payload).toBe(false);
    expect('revisionLocked' in payload).toBe(false);
    expect('cumulativeEditMinutes' in payload).toBe(false);
    expect('sourceHabitId' in payload).toBe(false);
    expect('scheduledStartTime' in payload).toBe(false);
    expect('reminderOffset' in payload).toBe(false);
  });
});

describe('updateTask merge-write payload shape', () => {
  beforeEach(() => {
    // updateTask re-reads the doc after writing; return a usable snapshot
    getDocMock.mockResolvedValue({
      id: 'task-1',
      exists: () => true,
      data: () => ({
        title: 'Existing title',
        priority: 2,
        isCompleted: false,
        // Android-only fields the web reader doesn't surface — round-tripped untouched
        isFlagged: true,
        lifeCategory: 'WORK',
        eisenhowerReason: 'Auto: keyword=urgent',
        archivedAt: null,
        userOverrodeQuadrant: true,
        goodEnoughMinutesOverride: 25,
      }),
    });
    updateDocMock.mockResolvedValue(undefined);
  });

  it('writes only the fields the caller passed (title-only edit)', async () => {
    await updateTask('uid-1', 'task-1', { title: 'New title' });
    expect(updateDocMock).toHaveBeenCalledTimes(1);
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.title).toBe('New title');
    // Field set must not include any Android-only keys we'd be clobbering.
    const keys = Object.keys(payload).sort();
    expect(keys).toEqual(['title', 'updatedAt'].sort());
  });

  it('does not include `dueTime`, `isFlagged`, `lifeCategory` in payload when they were not passed', async () => {
    await updateTask('uid-1', 'task-1', { description: 'A description' });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect('dueTime' in payload).toBe(false);
    expect('isFlagged' in payload).toBe(false);
    expect('lifeCategory' in payload).toBe(false);
    expect('eisenhowerReason' in payload).toBe(false);
    expect('archivedAt' in payload).toBe(false);
    expect('userOverrodeQuadrant' in payload).toBe(false);
  });

  it('includes lifeCategory when explicitly set on update', async () => {
    await updateTask('uid-1', 'task-1', { lifeCategory: 'PERSONAL' });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.lifeCategory).toBe('PERSONAL');
  });

  it('includes taskMode when explicitly set on update', async () => {
    await updateTask('uid-1', 'task-1', { taskMode: 'PLAY' });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.taskMode).toBe('PLAY');
  });

  it('includes cognitiveLoad when explicitly set on update', async () => {
    await updateTask('uid-1', 'task-1', { cognitiveLoad: 'EASY' });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.cognitiveLoad).toBe('EASY');
  });

  it('clears taskMode (explicit null) when set to null', async () => {
    await updateTask('uid-1', 'task-1', { taskMode: null });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.taskMode).toBeNull();
  });

  // Parity audit § B.8 — web TaskEditor now exposes Focus-Release
  // per-task overrides. They must round-trip through the conditional-
  // include write path so a user without the section open never
  // clobbers Android-side values.
  it('writes Focus-Release overrides when explicitly set on update', async () => {
    await updateTask('uid-1', 'task-1', {
      good_enough_minutes_override: 25,
      max_revisions_override: 3,
    });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.goodEnoughMinutesOverride).toBe(25);
    expect(payload.maxRevisionsOverride).toBe(3);
  });

  it('clears Focus-Release overrides (explicit null) so the global default takes over', async () => {
    await updateTask('uid-1', 'task-1', {
      good_enough_minutes_override: null,
      max_revisions_override: null,
    });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.goodEnoughMinutesOverride).toBeNull();
    expect(payload.maxRevisionsOverride).toBeNull();
  });

  it('writes userOverrodeQuadrant: true alongside an explicit eisenhower_quadrant move', async () => {
    await updateTask('uid-1', 'task-1', {
      eisenhower_quadrant: 'Q1',
      userOverrodeQuadrant: true,
    });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.eisenhowerQuadrant).toBe('Q1');
    expect(payload.userOverrodeQuadrant).toBe(true);
  });

  it('writes dueTime when due_time is provided alongside due_date', async () => {
    await updateTask('uid-1', 'task-1', {
      due_date: '2026-06-01',
      due_time: '09:30',
    });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    const expected = new Date('2026-06-01T09:30:00').getTime();
    expect(payload.dueTime).toBe(expected);
  });

  it('clears dueTime (explicit null) when due_time is set to null', async () => {
    await updateTask('uid-1', 'task-1', { due_time: null });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.dueTime).toBeNull();
  });

  it('round-trips Android-only fields on the returned Task (read passes them through unchanged)', async () => {
    // The point of this test: even though we wrote a partial update, the
    // re-read snapshot still carries the Android-only fields, and updateTask
    // doesn't strip them.
    const result = await updateTask('uid-1', 'task-1', { title: 'Touched' });
    expect(result.title).toBe('Existing title'); // value from re-read snapshot
    // Verify we didn't touch isFlagged / lifeCategory / etc on the write
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    for (const key of [
      'isFlagged',
      'lifeCategory',
      'eisenhowerReason',
      'archivedAt',
      'userOverrodeQuadrant',
      'goodEnoughMinutesOverride',
      'maxRevisionsOverride',
      'revisionCount',
      'revisionLocked',
      'cumulativeEditMinutes',
      'scheduledStartTime',
      'sourceHabitId',
      'reminderOffset',
    ]) {
      expect(key in payload).toBe(false);
    }
  });
});

// ── LWW guard tests (parity audit A.2) ────────────────────────────────

describe('updateTask LWW timestamp guard', () => {
  beforeEach(() => {
    // The post-write re-read returns the same snapshot the guard saw
    // when it allowed the write through; aborts just round-trip the
    // stale remote.
    getDocMock.mockResolvedValue({
      id: 'task-1',
      exists: () => true,
      data: () => ({ title: 'Existing title' }),
    });
  });

  it('aborts the write when remote updatedAt is strictly newer', async () => {
    // Stamp the remote 100ms in the future relative to Date.now() at
    // call time. The LWW guard reads the snapshot inside the
    // transaction, sees remote > local, and skips `tx.update`.
    const farFuture = Date.now() + 1_000_000;
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: farFuture }),
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    await updateTask('uid-1', 'task-1', { title: 'Stale write' });

    expect(updateDocMock).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('[lww] aborted stale write'),
    );

    warnSpy.mockRestore();
  });

  it('lets the write through when remote updatedAt is older', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: 1000 }),
    });

    await updateTask('uid-1', 'task-1', { title: 'Newer write' });

    expect(updateDocMock).toHaveBeenCalledTimes(1);
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.title).toBe('Newer write');
    expect(payload.updatedAt).toEqual(expect.any(Number));
  });

  it('treats remote.updatedAt === local as not stale (equality wins)', async () => {
    // Pre-seed `Date.now()` to a known value via jest fake timers so
    // local == remote precisely.
    const now = 1_700_000_000_000;
    vi.useFakeTimers();
    vi.setSystemTime(now);
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: now }),
    });

    await updateTask('uid-1', 'task-1', { title: 'Tie' });

    expect(updateDocMock).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });

  it('first-create wins when the remote doc does not exist', async () => {
    txGetMock.mockResolvedValue({
      exists: () => false,
      data: () => undefined,
    });
    // When the doc is missing the LWW helper uses `tx.set(ref, patch,
    // {merge:true})` instead of `tx.update`.
    await updateTask('uid-1', 'task-1', { title: 'First create' });
    expect(setDocMock).toHaveBeenCalledTimes(1);
    expect(updateDocMock).not.toHaveBeenCalled();
  });

  it('stamps the same `updatedAt` on the patch as it compares against', async () => {
    // The guard must use ONE wall-clock millis for both the
    // comparison and the doc write — otherwise the same Firestore
    // write would land with a different timestamp than the value the
    // guard authorised.
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: 0 }),
    });

    await updateTask('uid-1', 'task-1', { title: 'Coherent stamp' });

    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    const stamp = payload.updatedAt as number;
    expect(typeof stamp).toBe('number');
    // We can't observe what the guard internally compared against, but
    // we *can* assert the stamp on the patch is consistent with
    // wall-clock-at-call-time (within a generous window).
    expect(stamp).toBeGreaterThan(Date.now() - 5_000);
    expect(stamp).toBeLessThanOrEqual(Date.now());
  });
});
