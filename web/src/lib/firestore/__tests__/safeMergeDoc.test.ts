import { describe, it, expect, vi, beforeEach } from 'vitest';

const {
  txGetMock,
  runTransactionMock,
  setDocMock,
  txUpdateMock,
  txSetMock,
  serverTimestampMock,
} = vi.hoisted(() => ({
  txGetMock: vi.fn(),
  runTransactionMock: vi.fn(),
  setDocMock: vi.fn(),
  txUpdateMock: vi.fn(),
  txSetMock: vi.fn(),
  serverTimestampMock: vi.fn(() => '__SERVER_TIMESTAMP__'),
}));

vi.mock('@/lib/firebase', () => ({ firestore: {} }));
vi.mock('firebase/firestore', () => ({
  runTransaction: runTransactionMock,
  setDoc: setDocMock,
  serverTimestamp: serverTimestampMock,
}));

import { safeMergeDoc } from '@/lib/firestore/safeMergeDoc';

interface Task {
  title: string;
  updatedAt?: number;
}

beforeEach(() => {
  txGetMock.mockReset();
  runTransactionMock.mockReset();
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  txUpdateMock.mockReset();
  txSetMock.mockReset();
  serverTimestampMock.mockClear();

  runTransactionMock.mockImplementation(
    async (_db: unknown, fn: (tx: unknown) => Promise<unknown>) => {
      const tx = {
        get: (ref: unknown) => txGetMock(ref),
        update: (ref: unknown, patch: unknown) => txUpdateMock(ref, patch),
        set: (ref: unknown, patch: unknown, opts?: unknown) =>
          txSetMock(ref, patch, opts),
      };
      return fn(tx);
    },
  );
});

describe('safeMergeDoc — create path (expectedUpdatedAt = null)', () => {
  it('writes through setDoc with merge=true and a serverTimestamp', async () => {
    const ref = { path: 'users/u/tasks/t1' } as never;
    const result = await safeMergeDoc<Task>(
      ref,
      { title: 'New' },
      null,
    );
    expect(result).toEqual({ ok: true });
    expect(setDocMock).toHaveBeenCalledTimes(1);
    const [calledRef, payload, opts] = setDocMock.mock.calls[0];
    expect(calledRef).toBe(ref);
    expect(payload).toMatchObject({
      title: 'New',
      updatedAt: '__SERVER_TIMESTAMP__',
    });
    expect(opts).toEqual({ merge: true });
    expect(runTransactionMock).not.toHaveBeenCalled();
  });

  it('stamps __pref_updated_at as Date.now() for the prefs envelope', async () => {
    const ref = { path: 'users/u/prefs/a11y_prefs' } as never;
    const before = Date.now();
    await safeMergeDoc<{ reduce_motion: boolean }>(
      ref,
      { reduce_motion: true },
      null,
      { timestampField: '__pref_updated_at' },
    );
    const after = Date.now();
    const payload = setDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.reduce_motion).toBe(true);
    expect(typeof payload.__pref_updated_at).toBe('number');
    expect(payload.__pref_updated_at as number).toBeGreaterThanOrEqual(before);
    expect(payload.__pref_updated_at as number).toBeLessThanOrEqual(after);
    expect(serverTimestampMock).not.toHaveBeenCalled();
  });
});

describe('safeMergeDoc — guarded update (happy path)', () => {
  it('commits the patch when remote.updatedAt == expectedUpdatedAt', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ title: 'Old', updatedAt: 1_000 }),
    });
    const ref = { path: 'users/u/tasks/t1' } as never;
    const result = await safeMergeDoc<Task>(
      ref,
      { title: 'New' },
      1_000,
    );
    expect(result).toEqual({ ok: true });
    expect(txUpdateMock).toHaveBeenCalledTimes(1);
    const payload = txUpdateMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.title).toBe('New');
    expect(payload.updatedAt).toBe('__SERVER_TIMESTAMP__');
    expect(txSetMock).not.toHaveBeenCalled();
  });

  it('commits the patch when remote.updatedAt < expectedUpdatedAt', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: 500 }),
    });
    const result = await safeMergeDoc<Task>(
      { path: 'p' } as never,
      { title: 'New' },
      1_000,
    );
    expect(result).toEqual({ ok: true });
    expect(txUpdateMock).toHaveBeenCalledTimes(1);
  });

  it('promotes to set+merge when the doc is missing (create-on-update)', async () => {
    txGetMock.mockResolvedValue({ exists: () => false });
    const result = await safeMergeDoc<Task>(
      { path: 'p' } as never,
      { title: 'New' },
      1_000,
    );
    expect(result).toEqual({ ok: true });
    expect(txSetMock).toHaveBeenCalledTimes(1);
    expect(txUpdateMock).not.toHaveBeenCalled();
    const [, payload, opts] = txSetMock.mock.calls[0];
    expect(payload).toMatchObject({
      title: 'New',
      updatedAt: '__SERVER_TIMESTAMP__',
    });
    expect(opts).toEqual({ merge: true });
  });

  it('reads Firestore Timestamp objects via toMillis()', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: { toMillis: () => 800 } }),
    });
    const result = await safeMergeDoc<Task>(
      { path: 'p' } as never,
      { title: 'X' },
      1_000,
    );
    expect(result).toEqual({ ok: true });
    expect(txUpdateMock).toHaveBeenCalledTimes(1);
  });
});

describe('safeMergeDoc — stale rejection', () => {
  it('returns { ok: false, reason: stale, remote } when remote is newer', async () => {
    const remote = { title: 'AndroidWroteThis', updatedAt: 9_999 };
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => remote,
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const result = await safeMergeDoc<Task>(
      { path: 'users/u/tasks/t1' } as never,
      { title: 'WebStaleEdit' },
      1_000,
    );
    expect(result).toEqual({
      ok: false,
      reason: 'stale',
      remote,
    });
    expect(txUpdateMock).not.toHaveBeenCalled();
    expect(txSetMock).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('[safeMergeDoc] aborted stale write'),
    );
    warnSpy.mockRestore();
  });

  it('compares against the alternate timestamp field for prefs docs', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ __pref_updated_at: 5_000, reduce_motion: false }),
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const result = await safeMergeDoc<{ reduce_motion: boolean }>(
      { path: 'users/u/prefs/a11y_prefs' } as never,
      { reduce_motion: true },
      1_000,
      { timestampField: '__pref_updated_at' },
    );
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.remote).toEqual({
        __pref_updated_at: 5_000,
        reduce_motion: false,
      });
    }
    warnSpy.mockRestore();
  });

  it('does not retry — caller is responsible for re-applying', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: 9_999 }),
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await safeMergeDoc<Task>(
      { path: 'p' } as never,
      { title: 'X' },
      1_000,
    );
    // Only one transaction issued — no retry loop on stale.
    expect(runTransactionMock).toHaveBeenCalledTimes(1);
    warnSpy.mockRestore();
  });
});
