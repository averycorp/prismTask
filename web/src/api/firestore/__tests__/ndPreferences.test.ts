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
  DEFAULT_ND_PREFERENCES,
  effectiveCelebrationIntensity,
  getNdPreferences,
  isAnyNdModeActive,
  patchNdPreferences,
  shouldFireShipItCelebration,
} from '@/api/firestore/ndPreferences';

beforeEach(() => {
  getDocMock.mockReset();
  setDocMock.mockReset();
  docMock.mockReset();
  onSnapshotMock.mockReset();
  docMock.mockReturnValue({});
});

describe('getNdPreferences', () => {
  it('returns defaults when the doc does not exist', async () => {
    getDocMock.mockResolvedValueOnce({ exists: () => false, data: () => undefined });
    expect(await getNdPreferences('uid-1')).toEqual(DEFAULT_ND_PREFERENCES);
  });

  it('reads persisted booleans + ints + enums', async () => {
    getDocMock.mockResolvedValueOnce({
      exists: () => true,
      data: () => ({
        nd_adhd_mode_enabled: true,
        nd_calm_mode_enabled: false,
        nd_check_in_interval_minutes: 15,
        nd_good_enough_escalation: 'STRICT',
        nd_celebration_intensity: 'HIGH',
      }),
    });
    const p = await getNdPreferences('uid-1');
    expect(p.adhdModeEnabled).toBe(true);
    expect(p.checkInIntervalMinutes).toBe(15);
    expect(p.goodEnoughEscalation).toBe('STRICT');
    expect(p.celebrationIntensity).toBe('HIGH');
  });

  it('falls back to defaults on invalid enum values', async () => {
    getDocMock.mockResolvedValueOnce({
      exists: () => true,
      data: () => ({ nd_celebration_intensity: 'BOGUS' }),
    });
    expect((await getNdPreferences('uid-1')).celebrationIntensity).toBe(
      DEFAULT_ND_PREFERENCES.celebrationIntensity,
    );
  });

  it('targets users/{uid}/prefs/nd_prefs to match Android sync path', async () => {
    getDocMock.mockResolvedValueOnce({ exists: () => false, data: () => undefined });
    await getNdPreferences('uid-42');
    expect(docMock).toHaveBeenCalledWith({}, 'users', 'uid-42', 'prefs', 'nd_prefs');
  });
});

describe('patchNdPreferences', () => {
  it('writes only the changed fields with the Android field names', async () => {
    setDocMock.mockResolvedValueOnce(undefined);
    await patchNdPreferences('uid-1', {
      adhdModeEnabled: true,
      checkInIntervalMinutes: 20,
    });
    const payload = setDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.nd_adhd_mode_enabled).toBe(true);
    expect(payload.nd_check_in_interval_minutes).toBe(20);
    expect(payload.__pref_types).toEqual({
      nd_adhd_mode_enabled: 'bool',
      nd_check_in_interval_minutes: 'int',
    });
  });

  it('is a no-op when the patch is empty', async () => {
    await patchNdPreferences('uid-1', {});
    expect(setDocMock).not.toHaveBeenCalled();
  });
});

describe('feature-gate helpers', () => {
  it('isAnyNdModeActive returns true when any of the three is on', () => {
    expect(isAnyNdModeActive(DEFAULT_ND_PREFERENCES)).toBe(false);
    expect(
      isAnyNdModeActive({ ...DEFAULT_ND_PREFERENCES, adhdModeEnabled: true }),
    ).toBe(true);
    expect(
      isAnyNdModeActive({ ...DEFAULT_ND_PREFERENCES, calmModeEnabled: true }),
    ).toBe(true);
  });

  it('effectiveCelebrationIntensity forces LOW when Calm Mode is on', () => {
    expect(
      effectiveCelebrationIntensity({
        ...DEFAULT_ND_PREFERENCES,
        calmModeEnabled: true,
        celebrationIntensity: 'HIGH',
      }),
    ).toBe('LOW');
    expect(
      effectiveCelebrationIntensity({
        ...DEFAULT_ND_PREFERENCES,
        celebrationIntensity: 'HIGH',
      }),
    ).toBe('HIGH');
  });

  it('shouldFireShipItCelebration requires both F&R mode and the toggle', () => {
    expect(
      shouldFireShipItCelebration({
        ...DEFAULT_ND_PREFERENCES,
        focusReleaseModeEnabled: false,
        shipItCelebrationsEnabled: true,
      }),
    ).toBe(false);
    expect(
      shouldFireShipItCelebration({
        ...DEFAULT_ND_PREFERENCES,
        focusReleaseModeEnabled: true,
        shipItCelebrationsEnabled: true,
      }),
    ).toBe(true);
  });
});
