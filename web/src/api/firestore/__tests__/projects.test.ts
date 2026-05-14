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
  getDocs: vi.fn(),
  deleteDoc: vi.fn(),
  query: vi.fn(),
  orderBy: vi.fn(),
  onSnapshot: vi.fn(),
}));
vi.mock('@/lib/firebase', () => ({ firestore: {} }));

import { updateProject } from '@/api/firestore/projects';

beforeEach(() => {
  addDocMock.mockReset();
  updateDocMock.mockReset();
  setDocMock.mockReset();
  getDocMock.mockReset();
  txGetMock.mockReset();
  runTransactionMock.mockReset();
  docMock.mockReset();
  collectionMock.mockReset();
  docMock.mockReturnValue({ path: 'users/uid-1/projects/proj-1' });
  collectionMock.mockReturnValue({});
  getDocMock.mockResolvedValue({
    id: 'proj-1',
    exists: () => true,
    data: () => ({
      name: 'Existing',
      status: 'active',
      createdAt: 1_700_000_000_000,
      updatedAt: 1_700_000_000_000,
    }),
  });
  // Default-allow: remote updatedAt is older than the patch, so the
  // guard always lets the write through.
  txGetMock.mockResolvedValue({
    exists: () => true,
    data: () => ({ updatedAt: 0 }),
  });
  runTransactionMock.mockImplementation(
    async (_db: unknown, fn: (tx: unknown) => Promise<unknown>) => {
      const tx = {
        get: (ref: unknown) => txGetMock(ref),
        update: (ref: unknown, patch: unknown) => updateDocMock(ref, patch),
        set: (ref: unknown, patch: unknown, opts?: unknown) =>
          setDocMock(ref, patch, opts),
      };
      return fn(tx);
    },
  );
});

describe('updateProject LWW timestamp guard (parity A.2)', () => {
  it('writes through `tx.update` when remote is older', async () => {
    await updateProject('uid-1', 'proj-1', { title: 'Renamed' });
    expect(updateDocMock).toHaveBeenCalledTimes(1);
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.name).toBe('Renamed');
    expect(typeof payload.updatedAt).toBe('number');
  });

  it('aborts the write when remote updatedAt is strictly newer', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: Date.now() + 1_000_000 }),
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    await updateProject('uid-1', 'proj-1', { title: 'Stale' });

    expect(updateDocMock).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('[lww] aborted stale write'),
    );
    warnSpy.mockRestore();
  });

  it('first-create wins when the remote project doc does not exist', async () => {
    txGetMock.mockResolvedValue({
      exists: () => false,
      data: () => undefined,
    });
    await updateProject('uid-1', 'proj-1', { title: 'New' });
    expect(setDocMock).toHaveBeenCalledTimes(1);
    expect(updateDocMock).not.toHaveBeenCalled();
  });

  it('only writes the fields the caller passed (no Android-only clobber)', async () => {
    await updateProject('uid-1', 'proj-1', { status: 'completed' });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.status).toBe('completed');
    // Android-only fields are not written
    expect('endDate' in payload).toBe(false);
    expect('startDate' in payload).toBe(false);
    expect('themeColorKey' in payload).toBe(false);
    expect('archivedAt' in payload).toBe(false);
    expect('completedAt' in payload).toBe(false);
  });
});
