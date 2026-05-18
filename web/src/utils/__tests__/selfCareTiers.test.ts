import { describe, it, expect } from 'vitest';
import {
  getTierOrder,
  resolveSelectedTier,
  tierIncludes,
} from '@/utils/selfCareTiers';

/**
 * Mirrors Android `SelfCareRoutines.tierIncludes` + `DailyEssentialsUseCase
 * .resolveSelectedTier`. Keeps tier orders in lockstep with
 * `domain/model/SelfCareRoutine.kt`.
 */
describe('selfCareTiers', () => {
  describe('getTierOrder', () => {
    it('exposes the canonical order per routine', () => {
      expect(getTierOrder('morning')).toEqual(['survival', 'solid', 'full']);
      expect(getTierOrder('bedtime')).toEqual([
        'survival',
        'basic',
        'solid',
        'full',
      ]);
      expect(getTierOrder('housework')).toEqual(['quick', 'regular', 'deep']);
      expect(getTierOrder('medication')).toEqual([
        'essential',
        'prescription',
        'complete',
      ]);
    });
  });

  describe('tierIncludes', () => {
    it('includes lower-or-equal tiers', () => {
      const order = getTierOrder('morning');
      expect(tierIncludes(order, 'solid', 'survival')).toBe(true);
      expect(tierIncludes(order, 'solid', 'solid')).toBe(true);
    });

    it('excludes higher tiers', () => {
      const order = getTierOrder('morning');
      expect(tierIncludes(order, 'solid', 'full')).toBe(false);
    });

    it('excludes unknown tiers (e.g. medication.skipped)', () => {
      const order = getTierOrder('medication');
      expect(tierIncludes(order, 'complete', 'skipped')).toBe(false);
    });
  });

  describe('resolveSelectedTier', () => {
    const morningOrder = getTierOrder('morning');

    it('prefers the log tier when valid', () => {
      expect(resolveSelectedTier('full', morningOrder, 'solid')).toBe('full');
    });

    it('falls back to the user default when log tier is missing', () => {
      expect(resolveSelectedTier(null, morningOrder, 'survival')).toBe('survival');
    });

    it('falls back to the penultimate tier when neither log nor default applies', () => {
      expect(resolveSelectedTier(null, morningOrder, null)).toBe('solid');
    });

    it('ignores invalid log + default tiers', () => {
      expect(resolveSelectedTier('bogus', morningOrder, 'also-bogus')).toBe(
        'solid',
      );
    });

    it('returns null when the tier order is empty', () => {
      expect(resolveSelectedTier('any', [], null)).toBeNull();
    });
  });
});
