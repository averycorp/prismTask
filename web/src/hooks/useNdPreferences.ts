import { useMemo } from 'react';
import { useNdPreferencesStore } from '@/stores/ndPreferencesStore';
import {
  effectiveCelebrationIntensity,
  isAnyNdModeActive,
  shouldFireShipItCelebration,
  type CelebrationIntensity,
  type NdPreferences,
} from '@/api/firestore/ndPreferences';

/**
 * React hook that exposes ND preferences plus the derived feature-gate
 * helpers. Mirrors Android's `NdFeatureGate` access pattern.
 *
 * Returned helpers re-compute only when the underlying prefs change.
 */
export function useNdPreferences(): {
  prefs: NdPreferences;
  loaded: boolean;
  anyNdModeActive: boolean;
  celebrationIntensity: CelebrationIntensity;
  shouldFireShipItCelebration: boolean;
  shouldShowRewardAnimation: boolean;
} {
  const prefs = useNdPreferencesStore((s) => s.prefs);
  const loaded = useNdPreferencesStore((s) => s.loaded);

  return useMemo(
    () => ({
      prefs,
      loaded,
      anyNdModeActive: isAnyNdModeActive(prefs),
      celebrationIntensity: effectiveCelebrationIntensity(prefs),
      shouldFireShipItCelebration: shouldFireShipItCelebration(prefs),
      shouldShowRewardAnimation: prefs.completionAnimations,
    }),
    [prefs, loaded],
  );
}
