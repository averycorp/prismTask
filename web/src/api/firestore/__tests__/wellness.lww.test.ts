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

import { setCheckIn } from '@/api/firestore/checkInLogs';
import { updateLog } from '@/api/firestore/moodEnergyLogs';
import { updateRule } from '@/api/firestore/boundaryRules';

beforeEach(() => {
  txGetMock.mockReset();
  runTransactionMock.mockReset();
  updateDocMock.mockReset();
  setDocMock.mockReset();
  docMock.mockReset();
  docMock.mockReturnValue({ path: 'users/uid-1/<coll>/<id>' });
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

describe('setCheckIn LWW guard (parity A.2)', () => {
  it('writes through tx.update when remote is older', async () => {
    await setCheckIn('uid-1', {
      date_iso: '2026-05-13',
      steps_completed_csv: 'hydrated,medicated',
    });
    expect(updateDocMock).toHaveBeenCalledTimes(1);
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.dateIso).toBe('2026-05-13');
    expect(payload.stepsCompletedCsv).toBe('hydrated,medicated');
    expect(typeof payload.updatedAt).toBe('number');
  });

  it('aborts when remote updatedAt is newer', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: Date.now() + 1_000_000 }),
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await setCheckIn('uid-1', { date_iso: '2026-05-13' });
    expect(updateDocMock).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('[lww] aborted stale write'),
    );
    warnSpy.mockRestore();
  });

  it('first-create wins on missing doc (no prior check-in for the day)', async () => {
    txGetMock.mockResolvedValue({
      exists: () => false,
      data: () => undefined,
    });
    await setCheckIn('uid-1', { date_iso: '2026-05-13' });
    expect(setDocMock).toHaveBeenCalledTimes(1);
    expect(updateDocMock).not.toHaveBeenCalled();
  });
});

describe('updateLog (mood) LWW guard (parity A.2)', () => {
  it('writes through tx.update when remote is older', async () => {
    await updateLog('uid-1', '2026-05-13__morning', { mood: 4, energy: 3 });
    expect(updateDocMock).toHaveBeenCalledTimes(1);
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.mood).toBe(4);
    expect(payload.energy).toBe(3);
  });

  it('aborts on stale remote', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: Date.now() + 1_000_000 }),
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await updateLog('uid-1', '2026-05-13__morning', { mood: 5 });
    expect(updateDocMock).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });
});

describe('updateRule (boundary) LWW guard (parity A.2)', () => {
  it('writes through tx.update when remote is older', async () => {
    await updateRule('uid-1', 'rule-1', { enabled: false });
    expect(updateDocMock).toHaveBeenCalledTimes(1);
    const payload = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.enabled).toBe(false);
  });

  it('aborts on stale remote so an Android-side toggle survives', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: Date.now() + 1_000_000 }),
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await updateRule('uid-1', 'rule-1', { label: 'Stale rename' });
    expect(updateDocMock).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });
});
