import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  calculateStreaks,
  resolveHabitForgiveness,
  DEFAULT_FORGIVENESS,
  type ForgivenessConfig,
  type HabitForgivenessOverrides,
} from '@/utils/streaks';

/**
 * Per-habit streak-forgiveness override wiring (spec
 * `docs/superpowers/specs/2026-05-18-per-habit-forgiveness-design.md`).
 *
 * When a habit carries any of the four override fields
 * (`streak_max_missed_days`, `forgiveness_enabled`,
 * `forgiveness_allowed_misses`, `forgiveness_grace_period_days`), they
 * must take precedence over the global `ForgivenessConfig` for that
 * habit only — the global must not win when an override is set.
 *
 * Mirrors Android's `HabitForgivenessResolverTest`.
 */
describe('calculateStreaks — per-habit forgiveness overrides', () => {
  const fixedNow = new Date('2026-04-12T12:00:00Z');

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(fixedNow);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('per-habit allowedMisses=2 wins over global allowedMisses=1', () => {
    // Dataset: today + 4 met days with 2 misses sprinkled in. With the
    // default global (allowedMisses=1) the resilient walk halts after
    // the second miss. With the per-habit override (allowedMisses=2),
    // the walk absorbs both misses.
    const completions = [
      { date: '2026-04-12', count: 1 },
      { date: '2026-04-11', count: 1 },
      // 2026-04-10 missed
      { date: '2026-04-09', count: 1 },
      // 2026-04-08 missed
      { date: '2026-04-07', count: 1 },
      { date: '2026-04-06', count: 1 },
    ];

    // Sanity: with global (allowedMisses=1) and no override the streak
    // halts at 04-08 after absorbing 04-10.
    const baseline = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
    );
    expect(baseline.currentStreak).toBe(4);

    // Now pass per-habit override allowedMisses=2 — the walk should
    // reach all the way back to 04-06 (7 total: 5 met + 2 forgiven).
    const overrides: HabitForgivenessOverrides = {
      forgivenessAllowedMisses: 2,
      forgivenessGracePeriodDays: 14,
    };
    const overridden = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
      new Set<string>(),
      undefined,
      overrides,
    );
    expect(overridden.currentStreak).toBe(7);

    // The override must produce a strictly different result from the
    // global — that's how we know the override is doing something.
    expect(overridden.currentStreak).not.toBe(baseline.currentStreak);
  });

  it('per-habit forgiveness_enabled=0 forces forgiveness OFF even when global says ON', () => {
    // Dataset: today + one miss + two more met days. Global ON absorbs
    // the miss; per-habit OFF must break on the first miss.
    const completions = [
      { date: '2026-04-12', count: 1 },
      // 2026-04-11 missed
      { date: '2026-04-10', count: 1 },
      { date: '2026-04-09', count: 1 },
    ];

    const globalOn: ForgivenessConfig = {
      enabled: true,
      gracePeriodDays: 7,
      allowedMisses: 1,
    };

    // Sanity: with global ON, miss is absorbed → streak = 4.
    const onResult = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      globalOn,
    );
    expect(onResult.currentStreak).toBe(4);

    // Per-habit OFF (forgivenessEnabled = 0). Strict prefix walk now
    // breaks on 04-11 → just today.
    const override: HabitForgivenessOverrides = { forgivenessEnabled: 0 };
    const offResult = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      globalOn,
      new Set<string>(),
      undefined,
      override,
    );
    expect(offResult.currentStreak).toBe(1);
  });

  it('per-habit forgiveness_enabled=1 forces forgiveness ON even when global says OFF', () => {
    const completions = [
      { date: '2026-04-12', count: 1 },
      // 2026-04-11 missed
      { date: '2026-04-10', count: 1 },
      { date: '2026-04-09', count: 1 },
    ];

    const globalOff: ForgivenessConfig = {
      enabled: false,
      gracePeriodDays: 7,
      allowedMisses: 1,
    };

    // Sanity: global OFF + first miss → strict prefix walk → just today.
    const baseline = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      globalOff,
    );
    expect(baseline.currentStreak).toBe(1);

    const override: HabitForgivenessOverrides = { forgivenessEnabled: 1 };
    const overridden = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      globalOff,
      new Set<string>(),
      undefined,
      override,
    );
    // With forgiveness re-enabled, the miss is absorbed.
    expect(overridden.currentStreak).toBe(4);
  });

  it('undefined override fields inherit the global (no override = no change)', () => {
    const completions = [
      { date: '2026-04-12', count: 1 },
      { date: '2026-04-11', count: 1 },
      { date: '2026-04-10', count: 1 },
    ];

    const withoutOverride = calculateStreaks(
      completions,
      'daily',
      null,
      1,
    );
    const withEmptyOverride = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
      new Set<string>(),
      undefined,
      {},
    );
    expect(withEmptyOverride.currentStreak).toBe(withoutOverride.currentStreak);
  });

  it('partial override: gracePeriodDays only — allowedMisses still inherits', () => {
    // Override only the grace window; the allowed-misses cap still
    // comes from the global (DEFAULT_FORGIVENESS.allowedMisses=1).
    const completions = [
      { date: '2026-04-12', count: 1 },
      // 2026-04-11 missed
      // 2026-04-10 missed
      { date: '2026-04-09', count: 1 },
    ];

    const override: HabitForgivenessOverrides = {
      forgivenessGracePeriodDays: 30,
      // forgivenessAllowedMisses intentionally omitted
    };
    const result = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
      new Set<string>(),
      undefined,
      override,
    );
    // Allowed misses still = 1 (inherited). 04-11 absorbed, 04-10 hits
    // the cap → walk halts. Today + forgiven 04-11 = 2.
    expect(result.currentStreak).toBe(2);
  });
});

describe('resolveHabitForgiveness', () => {
  const globalConfig: ForgivenessConfig = {
    enabled: true,
    gracePeriodDays: 7,
    allowedMisses: 1,
  };

  it('returns global config unchanged when overrides are undefined/null', () => {
    expect(resolveHabitForgiveness(globalConfig, undefined)).toEqual(
      globalConfig,
    );
    expect(resolveHabitForgiveness(globalConfig, null)).toEqual(globalConfig);
  });

  it('returns global config unchanged when all override fields are undefined', () => {
    const resolved = resolveHabitForgiveness(globalConfig, {});
    expect(resolved.enabled).toBe(globalConfig.enabled);
    expect(resolved.gracePeriodDays).toBe(globalConfig.gracePeriodDays);
    expect(resolved.allowedMisses).toBe(globalConfig.allowedMisses);
  });

  it('forgivenessEnabled=0 forces OFF', () => {
    const resolved = resolveHabitForgiveness(globalConfig, {
      forgivenessEnabled: 0,
    });
    expect(resolved.enabled).toBe(false);
  });

  it('forgivenessEnabled=1 forces ON even when global is OFF', () => {
    const resolved = resolveHabitForgiveness(
      { ...globalConfig, enabled: false },
      { forgivenessEnabled: 1 },
    );
    expect(resolved.enabled).toBe(true);
  });

  it('forgivenessEnabled=-1 (Android-style inherit sentinel) falls back to global', () => {
    const resolved = resolveHabitForgiveness(globalConfig, {
      forgivenessEnabled: -1,
    });
    expect(resolved.enabled).toBe(globalConfig.enabled);
  });

  it('allowedMisses >= 0 wins; < 0 inherits', () => {
    expect(
      resolveHabitForgiveness(globalConfig, { forgivenessAllowedMisses: 0 })
        .allowedMisses,
    ).toBe(0);
    expect(
      resolveHabitForgiveness(globalConfig, { forgivenessAllowedMisses: 4 })
        .allowedMisses,
    ).toBe(4);
    // Android-style -1 sentinel from a legacy doc must inherit.
    expect(
      resolveHabitForgiveness(globalConfig, { forgivenessAllowedMisses: -1 })
        .allowedMisses,
    ).toBe(globalConfig.allowedMisses);
  });

  it('gracePeriodDays >= 1 wins; < 1 inherits', () => {
    expect(
      resolveHabitForgiveness(globalConfig, { forgivenessGracePeriodDays: 14 })
        .gracePeriodDays,
    ).toBe(14);
    expect(
      resolveHabitForgiveness(globalConfig, { forgivenessGracePeriodDays: 0 })
        .gracePeriodDays,
    ).toBe(globalConfig.gracePeriodDays);
    expect(
      resolveHabitForgiveness(globalConfig, { forgivenessGracePeriodDays: -1 })
        .gracePeriodDays,
    ).toBe(globalConfig.gracePeriodDays);
  });
});
