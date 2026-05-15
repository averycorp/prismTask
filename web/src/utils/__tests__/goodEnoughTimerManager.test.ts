import { describe, it, expect } from 'vitest';
import {
  DEFAULT_ND_PREFERENCES,
  type NdPreferences,
} from '@/api/firestore/ndPreferences';
import {
  computePomodoroTimerStatus,
  goodEnoughUnlockMessage,
  isGoodEnoughEnabled,
  POMODORO_GOOD_ENOUGH_THRESHOLD,
} from '@/utils/goodEnoughTimerManager';

function prefs(overrides: Partial<NdPreferences> = {}): NdPreferences {
  return { ...DEFAULT_ND_PREFERENCES, ...overrides };
}

describe('isGoodEnoughEnabled', () => {
  it('returns false when neither F&R nor ADHD forgiveness is on', () => {
    expect(isGoodEnoughEnabled(prefs())).toBe(false);
  });

  it('returns true when F&R mode is on with good-enough timers enabled', () => {
    expect(
      isGoodEnoughEnabled(
        prefs({
          focusReleaseModeEnabled: true,
          goodEnoughTimersEnabled: true,
        }),
      ),
    ).toBe(true);
  });

  it('returns false when F&R mode is on but good-enough timers are off', () => {
    expect(
      isGoodEnoughEnabled(
        prefs({
          focusReleaseModeEnabled: true,
          goodEnoughTimersEnabled: false,
        }),
      ),
    ).toBe(false);
  });

  it('returns true under ADHD mode with forgiveness streaks enabled', () => {
    expect(
      isGoodEnoughEnabled(
        prefs({
          adhdModeEnabled: true,
          forgivenessStreaks: true,
        }),
      ),
    ).toBe(true);
  });

  it('returns false under ADHD mode alone without forgiveness toggle', () => {
    expect(
      isGoodEnoughEnabled(
        prefs({
          adhdModeEnabled: true,
          forgivenessStreaks: false,
        }),
      ),
    ).toBe(false);
  });
});

describe('computePomodoroTimerStatus', () => {
  const enabledPrefs = prefs({
    focusReleaseModeEnabled: true,
    goodEnoughTimersEnabled: true,
  });

  it('locks early exit below the 70% threshold under ND gate', () => {
    const s = computePomodoroTimerStatus(
      { plannedSeconds: 100, elapsedSeconds: 50 },
      enabledPrefs,
    );
    expect(s.goodEnoughUnlocked).toBe(false);
    expect(s.progressRatio).toBeCloseTo(0.5);
    expect(s.fullyElapsed).toBe(false);
    expect(s.remainingSeconds).toBe(50);
  });

  it('unlocks at exactly 70% under ND gate', () => {
    const s = computePomodoroTimerStatus(
      { plannedSeconds: 100, elapsedSeconds: 70 },
      enabledPrefs,
    );
    expect(s.goodEnoughUnlocked).toBe(true);
    expect(s.fullyElapsed).toBe(false);
  });

  it('does NOT unlock at 70% without an ND gate (vanilla Pomodoro)', () => {
    const s = computePomodoroTimerStatus(
      { plannedSeconds: 100, elapsedSeconds: 70 },
      prefs(),
    );
    expect(s.goodEnoughUnlocked).toBe(false);
  });

  it('reports fully elapsed at 100% regardless of ND state', () => {
    const s = computePomodoroTimerStatus(
      { plannedSeconds: 60, elapsedSeconds: 120 },
      prefs(),
    );
    expect(s.fullyElapsed).toBe(true);
    expect(s.goodEnoughUnlocked).toBe(true);
    expect(s.remainingSeconds).toBe(0);
    expect(s.progressRatio).toBe(1);
  });

  it('handles zero planned duration as auto-unlocked', () => {
    const s = computePomodoroTimerStatus(
      { plannedSeconds: 0, elapsedSeconds: 0 },
      prefs(),
    );
    expect(s.fullyElapsed).toBe(true);
    expect(s.goodEnoughUnlocked).toBe(true);
  });

  it('clamps negative elapsed values', () => {
    const s = computePomodoroTimerStatus(
      { plannedSeconds: 60, elapsedSeconds: -10 },
      enabledPrefs,
    );
    expect(s.progressRatio).toBe(0);
    expect(s.remainingSeconds).toBe(60);
  });

  it('respects a custom threshold parameter', () => {
    const s = computePomodoroTimerStatus(
      { plannedSeconds: 100, elapsedSeconds: 55 },
      enabledPrefs,
      0.5,
    );
    expect(s.goodEnoughUnlocked).toBe(true);
  });

  it('exposes the threshold constant for callers', () => {
    expect(POMODORO_GOOD_ENOUGH_THRESHOLD).toBeCloseTo(0.7);
  });
});

describe('goodEnoughUnlockMessage', () => {
  it('pluralises minutes', () => {
    expect(goodEnoughUnlockMessage(1)).toMatch(/1 minute /);
    expect(goodEnoughUnlockMessage(15)).toMatch(/15 minutes /);
  });

  it('floors fractional minutes', () => {
    expect(goodEnoughUnlockMessage(15.9)).toContain('15');
  });

  it('clamps negatives to zero', () => {
    expect(goodEnoughUnlockMessage(-3)).toContain('0');
  });
});
