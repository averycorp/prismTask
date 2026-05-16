import { describe, it, expect } from 'vitest';
import {
  computeShipItIntensityLevel,
  computeUiComplexityLevel,
  shipItIntensityPatchFor,
  uiComplexityPatchFor,
} from '@/features/settings/BrainModeScreen';
import {
  DEFAULT_ND_PREFERENCES,
  type NdPreferences,
} from '@/api/firestore/ndPreferences';

function prefs(overrides: Partial<NdPreferences> = {}): NdPreferences {
  return { ...DEFAULT_ND_PREFERENCES, ...overrides };
}

describe('computeUiComplexityLevel', () => {
  it('returns 0 (Minimal) when all five Calm-Mode sub-settings are ON', () => {
    const p = prefs({
      reduceAnimations: true,
      mutedColorPalette: true,
      softContrast: true,
      quietMode: true,
      reduceHaptics: true,
    });
    expect(computeUiComplexityLevel(p)).toBe(0);
  });

  it('returns 1 (Standard) for the curated mid-tier combination', () => {
    const p = prefs({
      reduceAnimations: true,
      mutedColorPalette: true,
      reduceHaptics: true,
      softContrast: false,
      quietMode: false,
    });
    expect(computeUiComplexityLevel(p)).toBe(1);
  });

  it('falls back to 2 (Full) for unrecognised combinations', () => {
    const p = prefs({
      reduceAnimations: false,
      mutedColorPalette: false,
      softContrast: false,
      quietMode: false,
      reduceHaptics: false,
    });
    expect(computeUiComplexityLevel(p)).toBe(2);
  });
});

describe('uiComplexityPatchFor', () => {
  it('Minimal flips all five Calm sub-settings ON', () => {
    expect(uiComplexityPatchFor(0)).toEqual({
      reduceAnimations: true,
      mutedColorPalette: true,
      softContrast: true,
      quietMode: true,
      reduceHaptics: true,
    });
  });

  it('Standard picks the curated mid-tier subset', () => {
    expect(uiComplexityPatchFor(1)).toEqual({
      reduceAnimations: true,
      mutedColorPalette: true,
      reduceHaptics: true,
      softContrast: false,
      quietMode: false,
    });
  });

  it('Full flips all five Calm sub-settings OFF', () => {
    expect(uiComplexityPatchFor(2)).toEqual({
      reduceAnimations: false,
      mutedColorPalette: false,
      softContrast: false,
      quietMode: false,
      reduceHaptics: false,
    });
  });

  it('patches round-trip through the resolver', () => {
    for (const level of [0, 1, 2] as const) {
      const patched = prefs(uiComplexityPatchFor(level));
      expect(computeUiComplexityLevel(patched)).toBe(level);
    }
  });
});

describe('computeShipItIntensityLevel', () => {
  it('returns 0 when ship-it celebrations are disabled', () => {
    expect(
      computeShipItIntensityLevel(
        prefs({ shipItCelebrationsEnabled: false, celebrationIntensity: 'HIGH' }),
      ),
    ).toBe(0);
  });

  it('maps LOW / MEDIUM / HIGH to 1 / 2 / 3', () => {
    expect(
      computeShipItIntensityLevel(
        prefs({ shipItCelebrationsEnabled: true, celebrationIntensity: 'LOW' }),
      ),
    ).toBe(1);
    expect(
      computeShipItIntensityLevel(
        prefs({
          shipItCelebrationsEnabled: true,
          celebrationIntensity: 'MEDIUM',
        }),
      ),
    ).toBe(2);
    expect(
      computeShipItIntensityLevel(
        prefs({ shipItCelebrationsEnabled: true, celebrationIntensity: 'HIGH' }),
      ),
    ).toBe(3);
  });
});

describe('shipItIntensityPatchFor', () => {
  it('level 0 only disables the toggle (preserves stored intensity)', () => {
    expect(shipItIntensityPatchFor(0)).toEqual({
      shipItCelebrationsEnabled: false,
    });
  });

  it('levels 1..3 enable the toggle and pick the matching intensity', () => {
    expect(shipItIntensityPatchFor(1)).toEqual({
      shipItCelebrationsEnabled: true,
      celebrationIntensity: 'LOW',
    });
    expect(shipItIntensityPatchFor(2)).toEqual({
      shipItCelebrationsEnabled: true,
      celebrationIntensity: 'MEDIUM',
    });
    expect(shipItIntensityPatchFor(3)).toEqual({
      shipItCelebrationsEnabled: true,
      celebrationIntensity: 'HIGH',
    });
  });

  it('patches round-trip through the resolver', () => {
    for (const level of [0, 1, 2, 3] as const) {
      const patched = prefs(shipItIntensityPatchFor(level));
      // When intensity = 0, the persisted level depends on whatever
      // CelebrationIntensity was already in the doc — which is fine for
      // production but means we need to seed an explicit baseline here.
      const seed: NdPreferences =
        level === 0
          ? { ...patched, celebrationIntensity: 'MEDIUM' }
          : patched;
      expect(computeShipItIntensityLevel(seed)).toBe(level);
    }
  });
});
