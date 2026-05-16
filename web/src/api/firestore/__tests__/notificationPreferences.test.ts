import { describe, it, expect, vi, beforeEach } from 'vitest';

const { setDocMock, getDocMock, onSnapshotMock, docMock } = vi.hoisted(() => ({
  setDocMock: vi.fn(),
  getDocMock: vi.fn(),
  onSnapshotMock: vi.fn(),
  docMock: vi.fn(),
}));

vi.mock('@/lib/firebase', () => ({ firestore: {} }));
vi.mock('firebase/firestore', () => ({
  doc: docMock,
  getDoc: getDocMock,
  onSnapshot: onSnapshotMock,
  setDoc: setDocMock,
}));

import {
  DEFAULT_ACTIVE_PROFILE_ID,
  getActiveProfileId,
  setActiveProfileId,
  subscribeToActiveProfileId,
} from '@/api/firestore/notificationPreferences';

beforeEach(() => {
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  getDocMock.mockReset();
  onSnapshotMock.mockReset();
  docMock.mockReset();
  docMock.mockReturnValue({ path: 'users/uid-1/prefs/notification_prefs' });
});

describe('notificationPreferences active-profile Firestore mirror (parity unit 20)', () => {
  it('targets users/{uid}/prefs/notification_prefs (matches Android GenericPreferenceSync path)', async () => {
    await setActiveProfileId('uid-1', 42);
    expect(docMock).toHaveBeenCalledWith(
      {},
      'users',
      'uid-1',
      'prefs',
      'notification_prefs',
    );
  });

  it('setActiveProfileId writes the typed long with __pref_types tag', async () => {
    await setActiveProfileId('uid-1', 42);
    const [, payload, opts] = setDocMock.mock.calls[0];
    expect(payload).toMatchObject({
      active_notification_profile_id: 42,
      __pref_types: { active_notification_profile_id: 'long' },
      __pref_updated_at: expect.any(Number),
    });
    expect(opts).toEqual({ merge: true });
  });

  it('setActiveProfileId truncates floats to int (Android key is Long-typed)', async () => {
    await setActiveProfileId('uid-1', 3.9);
    const [, payload] = setDocMock.mock.calls[0];
    expect(payload.active_notification_profile_id).toBe(3);
  });

  it('getActiveProfileId returns DEFAULT_ACTIVE_PROFILE_ID when doc is missing', async () => {
    getDocMock.mockResolvedValue({ exists: () => false });
    const id = await getActiveProfileId('uid-1');
    expect(id).toBe(DEFAULT_ACTIVE_PROFILE_ID);
  });

  it('getActiveProfileId reads the stored int when present', async () => {
    getDocMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ active_notification_profile_id: 7 }),
    });
    const id = await getActiveProfileId('uid-1');
    expect(id).toBe(7);
  });

  it('getActiveProfileId falls back when the stored value is non-finite', async () => {
    getDocMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ active_notification_profile_id: 'banana' }),
    });
    const id = await getActiveProfileId('uid-1');
    expect(id).toBe(DEFAULT_ACTIVE_PROFILE_ID);
  });

  it('subscribeToActiveProfileId reports the stored value via onSnapshot', () => {
    const cb = vi.fn();
    let snapHandler: ((snap: {
      exists: () => boolean;
      data: () => Record<string, unknown>;
    }) => void) | null = null;
    onSnapshotMock.mockImplementation((_doc, handler) => {
      snapHandler = handler;
      return () => {};
    });
    subscribeToActiveProfileId('uid-1', cb);
    snapHandler?.({ exists: () => true, data: () => ({ active_notification_profile_id: 5 }) });
    expect(cb).toHaveBeenCalledWith(5);
  });
});
