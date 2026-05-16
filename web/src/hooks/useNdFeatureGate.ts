import { useMemo } from 'react';
import { useNdPreferencesStore } from '@/stores/ndPreferencesStore';
import {
  isAnyNdModeActive,
  type NdPreferences,
} from '@/api/firestore/ndPreferences';

/**
 * Feature keys recognised by `useNdFeatureGate`. Mirrors Android's
 * `NdFeatureGate` enum surface — adding a new key here pairs with a
 * `resolveFeature` branch below.
 */
export type NdFeatureKey =
  | 'ship_it_celebration'
  | 'focus_release'
  | 'forgiveness_streak'
  | 'energy_aware_suggestions'
  | 'good_enough_timers'
  | 'anti_rework'
  | 'completion_animation'
  | 'streak_celebration'
  | 'reduce_animations'
  | 'muted_palette'
  | 'soft_contrast'
  | 'quiet_mode'
  | 'reduce_haptics';

/**
 * React port of Android's `domain/usecase/NdFeatureGate.kt`.
 *
 * Brain Mode is the master switch: when **no** ND mode (ADHD / Calm /
 * Focus & Release) is active, every gated feature resolves to `false`
 * regardless of the per-feature sub-setting. When Brain Mode is active,
 * the feature's individual pref decides the answer.
 *
 * The single-string call form is intentional — it lets consumers add new
 * features without changing the hook signature, and matches Android's
 * `NdFeatureGate.isEnabled(feature)` API.
 *
 * @example
 *   const showShipIt = useNdFeatureGate('ship_it_celebration');
 *   if (showShipIt) fireShipItAnimation();
 */
export function useNdFeatureGate(feature: NdFeatureKey): boolean {
  const prefs = useNdPreferencesStore((s) => s.prefs);
  return useMemo(() => resolveFeature(prefs, feature), [prefs, feature]);
}

/**
 * Standalone variant for cases where the caller already has the prefs in
 * scope (e.g. inside an action / non-React module). Identical resolution
 * to the hook so behaviour stays in lockstep.
 */
export function resolveNdFeature(
  prefs: NdPreferences,
  feature: NdFeatureKey,
): boolean {
  return resolveFeature(prefs, feature);
}

function resolveFeature(prefs: NdPreferences, feature: NdFeatureKey): boolean {
  // Master switch: any ND mode being active gates everything else.
  if (!isAnyNdModeActive(prefs)) return false;

  switch (feature) {
    case 'ship_it_celebration':
      // Both Focus & Release Mode and the per-feature toggle must be on
      // (matches Android's `shouldFireShipItCelebration`).
      return prefs.focusReleaseModeEnabled && prefs.shipItCelebrationsEnabled;
    case 'focus_release':
      return prefs.focusReleaseModeEnabled;
    case 'forgiveness_streak':
      return prefs.forgivenessStreaks;
    case 'energy_aware_suggestions':
      return prefs.energyAwareSuggestionsEnabled;
    case 'good_enough_timers':
      return prefs.focusReleaseModeEnabled && prefs.goodEnoughTimersEnabled;
    case 'anti_rework':
      return prefs.focusReleaseModeEnabled && prefs.antiReworkEnabled;
    case 'completion_animation':
      return prefs.completionAnimations;
    case 'streak_celebration':
      return prefs.streakCelebrations;
    case 'reduce_animations':
      return prefs.reduceAnimations;
    case 'muted_palette':
      return prefs.mutedColorPalette;
    case 'soft_contrast':
      return prefs.softContrast;
    case 'quiet_mode':
      return prefs.quietMode;
    case 'reduce_haptics':
      return prefs.reduceHaptics;
  }
}
