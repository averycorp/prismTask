import { describe, it, expect, vi, beforeEach } from 'vitest';

const {
  txGetMock,
  runTransactionMock,
  updateDocMock,
  setDocMock,
  docMock,
} = vi.hoisted(() => ({
  txGetMock: vi.fn(),
  runTransactionMock: vi.fn(),
  updateDocMock: vi.fn(),
  setDocMock: vi.fn(),
  docMock: vi.fn(),
}));

vi.mock('@/lib/firebase', () => ({ firestore: {} }));
vi.mock('firebase/firestore', () => ({
  addDoc: vi.fn(),
  collection: vi.fn(),
  deleteDoc: vi.fn(),
  doc: docMock,
  getDoc: vi.fn(),
  getDocs: vi.fn(),
  onSnapshot: vi.fn(),
  orderBy: vi.fn(),
  query: vi.fn(),
  setDoc: setDocMock,
  updateDoc: updateDocMock,
  where: vi.fn(),
  writeBatch: vi.fn(),
  runTransaction: runTransactionMock,
}));

import { updateSlotDef } from '@/api/firestore/medicationSlots';

beforeEach(() => {
  txGetMock.mockReset();
  runTransactionMock.mockReset();
  updateDocMock.mockReset();
  setDocMock.mockReset();
  docMock.mockReset();
  docMock.mockReturnValue({
    path: 'users/uid-1/medication_slots/slot-1',
  });
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

describe('updateSlotDef LWW timestamp guard (parity A.2)', () => {
  it('writes through `tx.update` when remote is older', async () => {
    await updateSlotDef('uid-1', 'slot-1', { display_name: 'Morning' });
    expect(updateDocMock).toHaveBeenCalledTimes(1);
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.displayName).toBe('Morning');
    expect(typeof payload.updatedAt).toBe('number');
  });

  it('aborts the write when remote updatedAt is strictly newer', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: Date.now() + 1_000_000 }),
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await updateSlotDef('uid-1', 'slot-1', { display_name: 'Stale' });
    expect(updateDocMock).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('[lww] aborted stale write'),
    );
    warnSpy.mockRestore();
  });

  it('first-create wins when the remote slot doc does not exist', async () => {
    txGetMock.mockResolvedValue({
      exists: () => false,
      data: () => undefined,
    });
    await updateSlotDef('uid-1', 'slot-1', { display_name: 'New' });
    expect(setDocMock).toHaveBeenCalledTimes(1);
    expect(updateDocMock).not.toHaveBeenCalled();
  });

  it('only writes the fields the caller passed', async () => {
    await updateSlotDef('uid-1', 'slot-1', { reminder_mode: 'INTERVAL' });
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.reminderMode).toBe('INTERVAL');
    expect('slotKey' in payload).toBe(false);
    expect('displayName' in payload).toBe(false);
    expect('sortOrder' in payload).toBe(false);
  });
});
