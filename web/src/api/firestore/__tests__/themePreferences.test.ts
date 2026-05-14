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
  getThemePreferences,
  setThemeKey,
  setFontScale,
  subscribeToThemePreferences,
} from '@/api/firestore/themePreferences';

beforeEach(() => {
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  getDocMock.mockReset();
  onSnapshotMock.mockReset();
  docMock.mockReset();
  docMock.mockReturnValue({ path: 'users/uid-1/settings/theme_preferences' });
});

describe('themePreferences Firestore mirror (parity A.5b)', () => {
  describe('setThemeKey', () => {
    it('writes prism_theme + updated_at with merge=true', async () => {
      await setThemeKey('uid-1', 'CYBERPUNK');
      expect(setDocMock).toHaveBeenCalledTimes(1);
      const [, payload, opts] = setDocMock.mock.calls[0];
      expect(payload).toMatchObject({
        prism_theme: 'CYBERPUNK',
        updated_at: expect.any(Number),
      });
      expect(opts).toEqual({ merge: true });
    });

    it('targets users/{uid}/settings/theme_preferences (bespoke path, NOT users/{uid}/prefs/...)', async () => {
      await setThemeKey('uid-1', 'MATRIX');
      // The doc helper is called with the full path
      expect(docMock).toHaveBeenCalledWith(
        {},
        'users',
        'uid-1',
        'settings',
        'theme_preferences',
      );
    });

    it('ignores invalid theme keys (no Firestore write)', async () => {
      // Cast through unknown to defeat the type guard for the test
      await setThemeKey('uid-1', 'NOT_A_THEME' as unknown as 'CYBERPUNK');
      expect(setDocMock).not.toHaveBeenCalled();
    });
  });

  describe('setFontScale', () => {
    it('writes font_scale + updated_at with merge=true', async () => {
      await setFontScale('uid-1', 1.25);
      const [, payload, opts] = setDocMock.mock.calls[0];
      expect(payload).toMatchObject({
        font_scale: 1.25,
        updated_at: expect.any(Number),
      });
      expect(opts).toEqual({ merge: true });
    });

    it('ignores non-finite values', async () => {
      await setFontScale('uid-1', NaN);
      await setFontScale('uid-1', Infinity);
      expect(setDocMock).not.toHaveBeenCalled();
    });
  });

  describe('getThemePreferences', () => {
    it('returns the Firestore prism_theme + font_scale when present', async () => {
      getDocMock.mockResolvedValue({
        exists: () => true,
        data: () => ({ prism_theme: 'SYNTHWAVE', font_scale: 1.1 }),
      });
      const got = await getThemePreferences('uid-1');
      expect(got).toEqual({ themeKey: 'SYNTHWAVE', fontScale: 1.1 });
    });

    it('falls back to DEFAULT_THEME_KEY + 1.0 when doc missing', async () => {
      getDocMock.mockResolvedValue({ exists: () => false });
      const got = await getThemePreferences('uid-1');
      expect(got.themeKey).toBe('VOID');
      expect(got.fontScale).toBe(1.0);
    });

    it('falls back to default when prism_theme is unrecognised', async () => {
      getDocMock.mockResolvedValue({
        exists: () => true,
        data: () => ({ prism_theme: 'LEGACY_GARBAGE' }),
      });
      const got = await getThemePreferences('uid-1');
      expect(got.themeKey).toBe('VOID');
    });
  });

  describe('subscribeToThemePreferences', () => {
    it('registers an onSnapshot listener at the right doc path', () => {
      const cb = vi.fn();
      onSnapshotMock.mockReturnValue(() => {});
      subscribeToThemePreferences('uid-1', cb);
      expect(onSnapshotMock).toHaveBeenCalledTimes(1);
      expect(docMock).toHaveBeenCalledWith(
        {},
        'users',
        'uid-1',
        'settings',
        'theme_preferences',
      );
    });

    it('decodes snapshot data into the typed shape', () => {
      const cb = vi.fn();
      onSnapshotMock.mockImplementation((_ref, listener) => {
        listener({
          exists: () => true,
          data: () => ({ prism_theme: 'MATRIX', font_scale: 0.9 }),
        });
        return () => {};
      });
      subscribeToThemePreferences('uid-1', cb);
      expect(cb).toHaveBeenCalledWith({ themeKey: 'MATRIX', fontScale: 0.9 });
    });
  });
});
