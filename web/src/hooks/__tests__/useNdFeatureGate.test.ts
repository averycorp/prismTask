import { describe, it, expect } from 'vitest';
import { resolveNdFeature } from '@/hooks/useNdFeatureGate';
import {
  DEFAULT_ND_PREFERENCES,
  type NdPreferences,
} from '@/api/firestore/ndPreferences';

/**
 * `useNdFeatureGate` is a thin Zustand-selector wrapper around
 * `resolveNdFeature`. We test the pure resolver so we can hit every
 * branch without mounting the React tree.
 */

function prefs(overrides: Partial<NdPreferences> = {}): NdPreferences {
  return { ...DEFAULT_ND_PREFERENCES, ...overrides };
}

describe('resolveNdFeature — master switch (Brain Mode off)', () => {
  const allOff = prefs({
    adhdModeEnabled: false,
    calmModeEnabled: false,
    focusReleaseModeEnabled: false,
    // Force every sub-setting on so we can prove the master switch wins.
    shipItCelebrationsEnabled: true,
    forgivenessStreaks: true,
    energyAwareSuggestionsEnabled: true,
    goodEnoughTimersEnabled: true,
    antiReworkEnabled: true,
    completionAnimations: true,
    streakCelebrations: true,
    reduceAnimations: true,
    mutedColorPalette: true,
    softContrast: true,
    quietMode: true,
    reduceHaptics: true,
  });

  const cases: (Parameters<typeof resolveNdFeature>[1])[] = [
    'ship_it_celebration',
    'focus_release',
    'forgiveness_streak',
    'energy_aware_suggestions',
    'good_enough_timers',
    'anti_rework',
    'completion_animation',
    'streak_celebration',
    'reduce_animations',
    'muted_palette',
    'soft_contrast',
    'quiet_mode',
    'reduce_haptics',
  ];

  it.each(cases)(
    'forces %s to false when no ND mode is active',
    (feature) => {
      expect(resolveNdFeature(allOff, feature)).toBe(false);
    },
  );
});

describe('resolveNdFeature — per-feature resolution (Brain Mode on)', () => {
  const adhdOnly = prefs({
    adhdModeEnabled: true,
    calmModeEnabled: false,
    focusReleaseModeEnabled: false,
  });

  it('returns the sub-setting value when ADHD Mode is active', () => {
    expect(
      resolveNdFeature(
        { ...adhdOnly, forgivenessStreaks: true },
        'forgiveness_streak',
      ),
    ).toBe(true);
    expect(
      resolveNdFeature(
        { ...adhdOnly, forgivenessStreaks: false },
        'forgiveness_streak',
      ),
    ).toBe(false);
  });

  it('ship_it_celebration requires BOTH Focus & Release Mode and ship-it pref', () => {
    const focusOnShipOn = prefs({
      adhdModeEnabled: false,
      calmModeEnabled: false,
      focusReleaseModeEnabled: true,
      shipItCelebrationsEnabled: true,
    });
    expect(resolveNdFeature(focusOnShipOn, 'ship_it_celebration')).toBe(true);

    const focusOffShipOn = prefs({
      adhdModeEnabled: true, // Brain Mode active via ADHD, not F&R
      calmModeEnabled: false,
      focusReleaseModeEnabled: false,
      shipItCelebrationsEnabled: true,
    });
    expect(resolveNdFeature(focusOffShipOn, 'ship_it_celebration')).toBe(false);

    const focusOnShipOff = prefs({
      ...focusOnShipOn,
      shipItCelebrationsEnabled: false,
    });
    expect(resolveNdFeature(focusOnShipOff, 'ship_it_celebration')).toBe(false);
  });

  it('good_enough_timers requires Focus & Release Mode', () => {
    const adhdOnlyTimers = prefs({
      adhdModeEnabled: true,
      focusReleaseModeEnabled: false,
      goodEnoughTimersEnabled: true,
    });
    expect(resolveNdFeature(adhdOnlyTimers, 'good_enough_timers')).toBe(false);

    const focusAndTimers = prefs({
      focusReleaseModeEnabled: true,
      goodEnoughTimersEnabled: true,
    });
    expect(resolveNdFeature(focusAndTimers, 'good_enough_timers')).toBe(true);
  });

  it('energy_aware_suggestions reads the dedicated pref once Brain Mode is on', () => {
    const on = prefs({
      adhdModeEnabled: true,
      energyAwareSuggestionsEnabled: true,
    });
    const off = prefs({
      adhdModeEnabled: true,
      energyAwareSuggestionsEnabled: false,
    });
    expect(resolveNdFeature(on, 'energy_aware_suggestions')).toBe(true);
    expect(resolveNdFeature(off, 'energy_aware_suggestions')).toBe(false);
  });

  it('Calm-Mode visual sub-settings resolve directly when Brain Mode is on', () => {
    const calm = prefs({
      calmModeEnabled: true,
      reduceAnimations: true,
      mutedColorPalette: false,
      softContrast: true,
      quietMode: false,
      reduceHaptics: true,
    });
    expect(resolveNdFeature(calm, 'reduce_animations')).toBe(true);
    expect(resolveNdFeature(calm, 'muted_palette')).toBe(false);
    expect(resolveNdFeature(calm, 'soft_contrast')).toBe(true);
    expect(resolveNdFeature(calm, 'quiet_mode')).toBe(false);
    expect(resolveNdFeature(calm, 'reduce_haptics')).toBe(true);
  });
});
