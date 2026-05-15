import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  calculateStreaks,
  DEFAULT_FORGIVENESS,
  type ForgivenessConfig,
} from '@/utils/streaks';

/**
 * Pillars-audit Phase 2 item #4: the streak path must pick up the
 * user-configured `ForgivenessConfig` instead of always reading the
 * hardcoded `DEFAULT_FORGIVENESS` constant.
 *
 * The Settings → Advanced Tuning UI writes to
 * `users/{uid}/prefs/advanced_tuning_prefs`; the value flows through
 * `advancedTuningStore.selectForgivenessConfig` and reaches this util
 * as the optional 5th parameter to `calculateStreaks`.
 */
describe('calculateStreaks — Advanced Tuning forgiveness wiring', () => {
  const fixedNow = new Date('2026-04-12T12:00:00Z');

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(fixedNow);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('forgives 2 misses inside a 14-day window when allowedMisses=2', () => {
    // Setup: today and 4 met days, with 2 misses sprinkled in. Default
    // forgiveness (allowedMisses=1) would terminate at the second miss
    // — verify the wider knob walks through both.
    const completions = [
      { date: '2026-04-12', count: 1 }, // today met
      { date: '2026-04-11', count: 1 },
      // 2026-04-10 missed
      { date: '2026-04-09', count: 1 },
      // 2026-04-08 missed
      { date: '2026-04-07', count: 1 },
      { date: '2026-04-06', count: 1 },
    ];

    const strict = calculateStreaks(completions, 'daily', null, 1, DEFAULT_FORGIVENESS);
    // Default config (gracePeriodDays=7, allowedMisses=1) absorbs the
    // first miss (04-10) and then halts at the second miss (04-08).
    // Walk:  today (1) + 04-11 (2) + forgiven 04-10 (3) + 04-09 (4)
    // → break at 04-08. Strict streak = 4.
    expect(strict.currentStreak).toBe(4);

    const wider: ForgivenessConfig = {
      enabled: true,
      gracePeriodDays: 14,
      allowedMisses: 2,
    };
    const lenient = calculateStreaks(completions, 'daily', null, 1, wider);
    // With allowedMisses=2 the walk now absorbs both 04-10 and 04-08,
    // so it reaches 04-07 and 04-06 before running off the earliest
    // known date. Walk = today + 04-11 + forgiven 04-10 + 04-09 +
    // forgiven 04-08 + 04-07 + 04-06 = 7.
    expect(lenient.currentStreak).toBe(7);
    // The new value must differ from the strict-default reading —
    // otherwise the param threading isn't doing anything.
    expect(lenient.currentStreak).not.toBe(strict.currentStreak);
  });

  it('breaks the chain on first miss when forgiveness is disabled', () => {
    const completions = [
      { date: '2026-04-12', count: 1 },
      // 2026-04-11 missed
      { date: '2026-04-10', count: 1 },
      { date: '2026-04-09', count: 1 },
    ];

    const off: ForgivenessConfig = {
      enabled: false,
      gracePeriodDays: 7,
      allowedMisses: 1,
    };
    // Mirrors Android: forgiveness off → strict prefix walk → just today.
    const result = calculateStreaks(completions, 'daily', null, 1, off);
    expect(result.currentStreak).toBe(1);

    // Sanity: with default forgiveness ON the same dataset absorbs the
    // 04-11 miss and counts all three met days → today + forgiven +
    // 04-10 + 04-09 = 4. The strict reading above (1) is strictly less
    // — the wiring change makes a difference.
    const defaulted = calculateStreaks(completions, 'daily', null, 1);
    expect(defaulted.currentStreak).toBe(4);
    expect(defaulted.currentStreak).toBeGreaterThan(result.currentStreak);
  });

  it('falls back to DEFAULT_FORGIVENESS when no config is passed', () => {
    // Don't pass a forgiveness param. Same dataset as the first test;
    // the result should equal the explicit-DEFAULT_FORGIVENESS read.
    const completions = [
      { date: '2026-04-12', count: 1 },
      { date: '2026-04-11', count: 1 },
      { date: '2026-04-09', count: 1 },
    ];

    const implicit = calculateStreaks(completions, 'daily', null, 1);
    const explicit = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
    );
    expect(implicit.currentStreak).toBe(explicit.currentStreak);
  });
});
