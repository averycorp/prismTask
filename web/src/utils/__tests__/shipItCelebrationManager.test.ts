import { describe, it, expect } from 'vitest';
import {
  DEFAULT_ND_PREFERENCES,
  type NdPreferences,
} from '@/api/firestore/ndPreferences';
import {
  createShipItCelebration,
  shouldFireInsteadOfAdhd,
  type CelebrationTrigger,
} from '@/utils/shipItCelebrationManager';

function prefs(overrides: Partial<NdPreferences> = {}): NdPreferences {
  return { ...DEFAULT_ND_PREFERENCES, ...overrides };
}

const ENABLED = prefs({
  focusReleaseModeEnabled: true,
  shipItCelebrationsEnabled: true,
});

describe('createShipItCelebration', () => {
  it('returns null when F&R mode is off', () => {
    const c = createShipItCelebration(
      'NORMAL_COMPLETION',
      prefs({ focusReleaseModeEnabled: false }),
    );
    expect(c).toBeNull();
  });

  it('returns null when shipItCelebrationsEnabled is off', () => {
    const c = createShipItCelebration(
      'NORMAL_COMPLETION',
      prefs({ focusReleaseModeEnabled: true, shipItCelebrationsEnabled: false }),
    );
    expect(c).toBeNull();
  });

  it('returns a celebration with effective intensity when enabled', () => {
    const c = createShipItCelebration('NORMAL_COMPLETION', ENABLED);
    expect(c).not.toBeNull();
    expect(c!.trigger).toBe('NORMAL_COMPLETION');
    expect(c!.intensity).toBe('MEDIUM');
    expect(c!.message.length).toBeGreaterThan(0);
    expect(c!.isStreakMilestone).toBe(false);
  });

  it('forces LOW intensity when Calm Mode is active', () => {
    const c = createShipItCelebration(
      'NORMAL_COMPLETION',
      prefs({
        focusReleaseModeEnabled: true,
        shipItCelebrationsEnabled: true,
        calmModeEnabled: true,
        celebrationIntensity: 'HIGH',
      }),
    );
    expect(c).not.toBeNull();
    expect(c!.intensity).toBe('LOW');
  });

  it('flags streak milestones at 3 / 7 / 14 / 30 days', () => {
    for (const days of [3, 7, 14, 30]) {
      const c = createShipItCelebration('NORMAL_COMPLETION', ENABLED, days);
      expect(c!.isStreakMilestone).toBe(true);
      expect(c!.streakDays).toBe(days);
    }
  });

  it('does NOT flag non-milestone streaks', () => {
    for (const days of [0, 1, 2, 4, 8, 31]) {
      const c = createShipItCelebration('NORMAL_COMPLETION', ENABLED, days);
      expect(c!.isStreakMilestone).toBe(false);
    }
  });

  it('emits a message that matches the trigger flavor', () => {
    const triggers: CelebrationTrigger[] = [
      'NORMAL_COMPLETION',
      'GOOD_ENOUGH_SHIP',
      'RESISTED_REWORK',
      'LOCKED_AT_MAX_REVISIONS',
    ];
    for (const t of triggers) {
      const c = createShipItCelebration(t, ENABLED);
      expect(c).not.toBeNull();
      expect(typeof c!.message).toBe('string');
      expect(c!.message.length).toBeGreaterThan(0);
    }
  });
});

describe('shouldFireInsteadOfAdhd', () => {
  it('is true exactly when Ship-It celebrations would fire', () => {
    expect(shouldFireInsteadOfAdhd(ENABLED)).toBe(true);
    expect(shouldFireInsteadOfAdhd(prefs())).toBe(false);
    expect(
      shouldFireInsteadOfAdhd(
        prefs({ focusReleaseModeEnabled: true, shipItCelebrationsEnabled: false }),
      ),
    ).toBe(false);
  });
});
