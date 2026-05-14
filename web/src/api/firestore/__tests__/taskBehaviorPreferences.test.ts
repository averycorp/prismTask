import { describe, it, expect, vi, beforeEach } from 'vitest';

const {
  getDocMock,
  setDocMock,
  docMock,
  onSnapshotMock,
} = vi.hoisted(() => ({
  getDocMock: vi.fn(),
  setDocMock: vi.fn(),
  docMock: vi.fn(),
  onSnapshotMock: vi.fn(),
}));

vi.mock('firebase/firestore', () => ({
  doc: docMock,
  getDoc: getDocMock,
  setDoc: setDocMock,
  onSnapshot: onSnapshotMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: {} }));

import {
  DEFAULT_DAY_START_HOUR,
  getDayStartHour,
  setDayStartHour,
  subscribeToDayStartHour,
} from '@/api/firestore/taskBehaviorPreferences';

beforeEach(() => {
  getDocMock.mockReset();
  setDocMock.mockReset();
  docMock.mockReset();
  onSnapshotMock.mockReset();
  docMock.mockReturnValue({});
});

describe('getDayStartHour', () => {
  it('returns the default (0 = midnight) when the doc does not exist', async () => {
    getDocMock.mockResolvedValueOnce({ exists: () => false, data: () => undefined });
    const hour = await getDayStartHour('uid-1');
    expect(hour).toBe(DEFAULT_DAY_START_HOUR);
    expect(hour).toBe(0);
  });

  it('reads the persisted number', async () => {
    getDocMock.mockResolvedValueOnce({
      exists: () => true,
      data: () => ({ day_start_hour: 5 }),
    });
    expect(await getDayStartHour('uid-1')).toBe(5);
  });

  it('clamps out-of-range remote values back to default (defensive)', async () => {
    getDocMock.mockResolvedValueOnce({
      exists: () => true,
      data: () => ({ day_start_hour: 99 }),
    });
    expect(await getDayStartHour('uid-1')).toBe(DEFAULT_DAY_START_HOUR);
  });

  it('targets users/{uid}/prefs/task_behavior_prefs (matches Android sync path)', async () => {
    getDocMock.mockResolvedValueOnce({ exists: () => false, data: () => undefined });
    await getDayStartHour('uid-42');
    expect(docMock).toHaveBeenCalledWith(
      {},
      'users',
      'uid-42',
      'prefs',
      'task_behavior_prefs',
    );
  });
});

describe('setDayStartHour', () => {
  it('writes day_start_hour with merge=true and the "int" type tag', async () => {
    setDocMock.mockResolvedValueOnce(undefined);
    await setDayStartHour('uid-1', 6);
    const [, payload, options] = setDocMock.mock.calls[0];
    expect(payload.day_start_hour).toBe(6);
    expect(payload.__pref_types).toEqual({ day_start_hour: 'int' });
    expect(options).toEqual({ merge: true });
  });

  it('stamps __pref_updated_at so Android last-write-wins picks the newer value', async () => {
    setDocMock.mockResolvedValueOnce(undefined);
    const before = Date.now();
    await setDayStartHour('uid-1', 3);
    const after = Date.now();
    const [, payload] = setDocMock.mock.calls[0];
    expect(typeof payload.__pref_updated_at).toBe('number');
    expect(payload.__pref_updated_at).toBeGreaterThanOrEqual(before);
    expect(payload.__pref_updated_at).toBeLessThanOrEqual(after);
  });

  it('clamps out-of-range inputs into [0, 23] before write (matches Android)', async () => {
    setDocMock.mockResolvedValue(undefined);
    await setDayStartHour('uid-1', -2);
    expect(setDocMock.mock.calls[0]?.[1].day_start_hour).toBe(0);

    await setDayStartHour('uid-1', 42);
    expect(setDocMock.mock.calls[1]?.[1].day_start_hour).toBe(23);
  });

  it('round-trips: write then read returns the written value', async () => {
    setDocMock.mockResolvedValueOnce(undefined);
    await setDayStartHour('uid-1', 4);
    const [, payload] = setDocMock.mock.calls[0];

    getDocMock.mockResolvedValueOnce({
      exists: () => true,
      data: () => payload,
    });
    expect(await getDayStartHour('uid-1')).toBe(4);
  });
});

describe('subscribeToDayStartHour', () => {
  it('invokes the callback with the parsed remote hour', () => {
    const cb = vi.fn();
    let snapshotHandler: ((snap: unknown) => void) | undefined;
    onSnapshotMock.mockImplementation((_ref, handler) => {
      snapshotHandler = handler as (snap: unknown) => void;
      return () => {};
    });

    subscribeToDayStartHour('uid-1', cb);

    snapshotHandler?.({
      exists: () => true,
      data: () => ({ day_start_hour: 7 }),
    });
    expect(cb).toHaveBeenCalledWith(7);
  });

  it('falls back to the default when the snapshot has no doc', () => {
    const cb = vi.fn();
    let snapshotHandler: ((snap: unknown) => void) | undefined;
    onSnapshotMock.mockImplementation((_ref, handler) => {
      snapshotHandler = handler as (snap: unknown) => void;
      return () => {};
    });

    subscribeToDayStartHour('uid-1', cb);

    snapshotHandler?.({ exists: () => false, data: () => undefined });
    expect(cb).toHaveBeenCalledWith(DEFAULT_DAY_START_HOUR);
  });
});
