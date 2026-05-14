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
  getA11yPreferences,
  setHighContrast,
  setReduceMotion,
  subscribeToA11yPreferences,
} from '@/api/firestore/a11yPreferences';

beforeEach(() => {
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  getDocMock.mockReset();
  onSnapshotMock.mockReset();
  docMock.mockReset();
  docMock.mockReturnValue({ path: 'users/uid-1/prefs/a11y_prefs' });
});

describe('a11yPreferences Firestore mirror (parity A.5b)', () => {
  it('setReduceMotion writes reduce_motion + __pref_types tag + __pref_updated_at', async () => {
    await setReduceMotion('uid-1', true);
    const [, payload, opts] = setDocMock.mock.calls[0];
    expect(payload).toMatchObject({
      reduce_motion: true,
      __pref_types: { reduce_motion: 'bool' },
      __pref_updated_at: expect.any(Number),
    });
    expect(opts).toEqual({ merge: true });
  });

  it('setHighContrast writes high_contrast + __pref_types tag + __pref_updated_at', async () => {
    await setHighContrast('uid-1', true);
    const [, payload, opts] = setDocMock.mock.calls[0];
    expect(payload).toMatchObject({
      high_contrast: true,
      __pref_types: { high_contrast: 'bool' },
      __pref_updated_at: expect.any(Number),
    });
    expect(opts).toEqual({ merge: true });
  });

  it('targets users/{uid}/prefs/a11y_prefs (generic PreferenceSync path)', async () => {
    await setReduceMotion('uid-1', false);
    expect(docMock).toHaveBeenCalledWith(
      {},
      'users',
      'uid-1',
      'prefs',
      'a11y_prefs',
    );
  });

  it('getA11yPreferences returns defaults when doc missing', async () => {
    getDocMock.mockResolvedValue({ exists: () => false });
    const got = await getA11yPreferences('uid-1');
    expect(got).toEqual({ reduceMotion: false, highContrast: false });
  });

  it('getA11yPreferences returns Firestore values when present', async () => {
    getDocMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ reduce_motion: true, high_contrast: false }),
    });
    const got = await getA11yPreferences('uid-1');
    expect(got).toEqual({ reduceMotion: true, highContrast: false });
  });

  it('getA11yPreferences ignores non-boolean values', async () => {
    getDocMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ reduce_motion: 'yes', high_contrast: 1 }),
    });
    const got = await getA11yPreferences('uid-1');
    expect(got).toEqual({ reduceMotion: false, highContrast: false });
  });

  it('subscribeToA11yPreferences decodes snapshot to typed shape', () => {
    const cb = vi.fn();
    onSnapshotMock.mockImplementation((_ref, listener) => {
      listener({
        exists: () => true,
        data: () => ({ reduce_motion: true, high_contrast: true }),
      });
      return () => {};
    });
    subscribeToA11yPreferences('uid-1', cb);
    expect(cb).toHaveBeenCalledWith({ reduceMotion: true, highContrast: true });
  });
});
