import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  calculateStreaks,
  resolveForgivenessForMode,
  DEFAULT_FORGIVENESS,
  DEFAULT_PLAY_RELAX_FORGIVENESS,
  type ForgivenessConfig,
} from '@/utils/streaks';

/**
 * Pillars-audit Phase 2 item #5: mode-aware streak strictness. Per
 * `docs/WORK_PLAY_RELAX.md` § *Streak strictness*:
 *
 *   - Work tasks use the standard forgiveness window (7d / 1 miss).
 *   - Play and Relax default to a wider window (14d / 2 misses).
 *   - The user can override per-mode via Settings → Advanced Tuning.
 *   - Uncategorized + missing mode fall back to the base config.
 *
 * These tests pin the wiring: `calculateStreaks` reads
 * `forgivenessConfig.byMode[taskMode]` via `resolveForgivenessForMode`
 * before calling the daily-walk core, and the rest-day fold (#1507)
 * still composes correctly.
 */
describe('calculateStreaks — mode-aware streak strictness', () => {
  const fixedNow = new Date('2026-04-12T12:00:00Z');

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(fixedNow);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /**
   * Build a dataset where the streak diverges between the standard
   * (7d / 1 miss) window and the wider (14d / 2 miss) window. Same data
   * threaded with different modes must produce different streak counts.
   */
  const lenientChainCompletions = [
    { date: '2026-04-12', count: 1 }, // today met
    { date: '2026-04-11', count: 1 },
    // 2026-04-10 missed
    { date: '2026-04-09', count: 1 },
    // 2026-04-08 missed
    { date: '2026-04-07', count: 1 },
    { date: '2026-04-06', count: 1 },
  ];

  it('Work mode uses the standard (7d / 1 miss) window by default', () => {
    // No explicit byMode override → resolves to base config (7d / 1 miss).
    // Walk: today (1) + 04-11 (2) + forgiven 04-10 (3) + 04-09 (4)
    // → break at 04-08 (second miss inside grace). Streak = 4.
    const result = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
      new Set<string>(),
      'WORK',
    );
    expect(result.currentStreak).toBe(4);
  });

  it('Play mode uses the wider (14d / 2 miss) default window', () => {
    // byMode.play in DEFAULT_FORGIVENESS = {grace: 14, misses: 2}. With
    // 2 allowed misses the walk absorbs both 04-10 and 04-08, then
    // reaches 04-07 and 04-06 before halting at earliest activity.
    // Streak = 7.
    const result = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
      new Set<string>(),
      'PLAY',
    );
    expect(result.currentStreak).toBe(7);
    // The point of the test: wider knobs visibly change the count.
    // (Whatever the strict streak is must be less than the wider read.)
    expect(result.currentStreak).toBeGreaterThan(4);
  });

  it('Relax mode mirrors Play — same wider (14d / 2 miss) defaults', () => {
    // Relax and Play are symmetric in the doc — both self-paced.
    const result = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
      new Set<string>(),
      'RELAX',
    );
    expect(result.currentStreak).toBe(7);
  });

  it('UNCATEGORIZED falls back to base config — never auto-upgrades', () => {
    // Even though byMode.play / relax carry wider knobs, an unknown /
    // uncategorized mode must read the BASE config so the system never
    // auto-upgrades the user's grace silently. Mirrors the
    // `WORK_PLAY_RELAX.md` § *Inference rules* lean: never inflate Work,
    // never inflate Play either — keep Uncategorized at the standard
    // window so the user sees what they configured.
    const result = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
      new Set<string>(),
      'UNCATEGORIZED',
    );
    expect(result.currentStreak).toBe(4);
  });

  it('missing mode falls back to base config (parity with UNCATEGORIZED)', () => {
    // Habit callers pass no `taskMode` (habits don't carry a mode).
    // Result must match the no-mode default and the UNCATEGORIZED read.
    const noMode = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
    );
    const uncategorized = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
      new Set<string>(),
      'UNCATEGORIZED',
    );
    expect(noMode.currentStreak).toBe(uncategorized.currentStreak);
    expect(noMode.currentStreak).toBe(4); // base config behaviour
  });

  it('user override per mode is honored — wider override widens, tighter override tightens', () => {
    // User configures Play with a strict (7d / 0 miss) override and
    // Relax with an even wider (28d / 3 miss) window. Run the same data
    // three ways:
    //
    //   - Play (strict override) → first miss at 04-10 terminates.
    //     Walk: today (1) + 04-11 (2) → break at 04-10. Streak = 2.
    //   - Relax (wider override) → absorbs both 04-10 and 04-08 like
    //     the default Play config does. Streak = 7.
    //   - Work (no override → base 7/1) → matches the default Work
    //     reading: streak = 4.
    const config: ForgivenessConfig = {
      enabled: true,
      gracePeriodDays: 7,
      allowedMisses: 1,
      byMode: {
        work: { gracePeriodDays: 7, allowedMisses: 1 },
        play: { gracePeriodDays: 7, allowedMisses: 0 },
        relax: { gracePeriodDays: 28, allowedMisses: 3 },
      },
    };

    const playStrict = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      config,
      new Set<string>(),
      'PLAY',
    );
    const relaxWider = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      config,
      new Set<string>(),
      'RELAX',
    );
    const workDefault = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      config,
      new Set<string>(),
      'WORK',
    );

    expect(playStrict.currentStreak).toBe(2);
    expect(relaxWider.currentStreak).toBe(7);
    expect(workDefault.currentStreak).toBe(4);
  });

  it('per-mode override does NOT leak into other modes', () => {
    // Overriding only Play must leave Work + Relax on their defaults.
    // Same data, three modes, three different streak counts.
    const config: ForgivenessConfig = {
      enabled: true,
      gracePeriodDays: 7,
      allowedMisses: 1,
      byMode: {
        // Only Play is overridden — to strict.
        play: { gracePeriodDays: 7, allowedMisses: 0 },
        // No work / relax entries → they fall back to base.
      },
    };

    const work = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      config,
      new Set<string>(),
      'WORK',
    );
    const play = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      config,
      new Set<string>(),
      'PLAY',
    );
    const relax = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      config,
      new Set<string>(),
      'RELAX',
    );

    // Work → base 7/1 → streak = 4.
    expect(work.currentStreak).toBe(4);
    // Play → strict override 7/0 → streak = 2.
    expect(play.currentStreak).toBe(2);
    // Relax → falls back to base 7/1 → streak = 4.
    expect(relax.currentStreak).toBe(4);
  });

  it('top-level `enabled: false` disables forgiveness for every mode', () => {
    // The master switch is global — a per-mode override can NOT turn
    // forgiveness on for one mode while it's globally off.
    const config: ForgivenessConfig = {
      ...DEFAULT_FORGIVENESS,
      enabled: false,
    };
    const work = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      config,
      new Set<string>(),
      'WORK',
    );
    const play = calculateStreaks(
      lenientChainCompletions,
      'daily',
      null,
      1,
      config,
      new Set<string>(),
      'PLAY',
    );
    // With forgiveness off the strict walk runs — today + yesterday
    // are met, then 04-10 is missed. Strict walk = 2 for every mode.
    expect(work.currentStreak).toBe(2);
    expect(play.currentStreak).toBe(2);
  });

  // -----------------------------------------------------------------
  // Mode + rest-day compose (PR #1507). A rest day on a Play activity
  // must still be kept-by-definition, and a miss inside the wider Play
  // window must still be absorbed. These are the two folds composing
  // — neither feature steps on the other.
  // -----------------------------------------------------------------

  it('rest day on a Play activity is kept-by-definition', () => {
    // April 11 is a rest day for the user's Play activity. Without
    // forgiveness OR rest days the resilient walk would still hit two
    // misses (04-10 + 04-08) inside the rolling window, but the wider
    // Play window absorbs both — strictly, the test here is the rest
    // day itself doesn't consume any of those 2 allowed misses.
    const completions = [
      { date: '2026-04-12', count: 1 },
      // 2026-04-11 missed → rest day
      // 2026-04-10 missed (genuine)
      { date: '2026-04-09', count: 1 },
      // 2026-04-08 missed (genuine)
      { date: '2026-04-07', count: 1 },
      { date: '2026-04-06', count: 1 },
    ];
    const restDays = new Set(['2026-04-11']);
    const result = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
      restDays,
      'PLAY',
    );
    // Walk: today (1) + rest April 11 (2) + forgiven 04-10 (3) + 04-09 (4)
    // + forgiven 04-08 (5) + 04-07 (6) + 04-06 (7). Both genuine misses
    // fit inside Play's wider 14d/2miss window; rest day didn't eat the
    // budget. Streak = 7.
    expect(result.currentStreak).toBe(7);
  });

  it('miss inside the wider Play window is still absorbed when composing with rest days', () => {
    // Three rest days in the middle of the chain, plus one genuine
    // miss. The rest days keep the chain alive (free), and Play's 2
    // allowed misses are spent on the genuine ones. Verifies the
    // composition fires in both directions.
    const completions = [
      { date: '2026-04-12', count: 1 },
      // 2026-04-11 rest day
      // 2026-04-10 rest day
      { date: '2026-04-09', count: 1 },
      // 2026-04-08 missed (genuine)
      { date: '2026-04-07', count: 1 },
      // 2026-04-06 missed (genuine)
      { date: '2026-04-05', count: 1 },
    ];
    const restDays = new Set(['2026-04-11', '2026-04-10']);

    // Play (wider window — both genuine misses absorbed).
    const play = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
      restDays,
      'PLAY',
    );
    // today + rest 04-11 + rest 04-10 + 04-09 + forgiven 04-08 + 04-07
    // + forgiven 04-06 + 04-05 = 8.
    expect(play.currentStreak).toBe(8);

    // Same data threaded as Work (standard window — only 1 miss absorbed).
    const work = calculateStreaks(
      completions,
      'daily',
      null,
      1,
      DEFAULT_FORGIVENESS,
      restDays,
      'WORK',
    );
    // today + rest 04-11 + rest 04-10 + 04-09 + forgiven 04-08 + 04-07
    // → break at 04-06 (second miss). Streak = 6.
    expect(work.currentStreak).toBe(6);
    expect(play.currentStreak).toBeGreaterThan(work.currentStreak);
  });
});

describe('resolveForgivenessForMode', () => {
  it('falls back to base config when mode is null / undefined / UNCATEGORIZED', () => {
    const base: ForgivenessConfig = {
      enabled: true,
      gracePeriodDays: 7,
      allowedMisses: 1,
      byMode: {
        work: { gracePeriodDays: 5, allowedMisses: 0 },
        play: { gracePeriodDays: 14, allowedMisses: 2 },
        relax: { gracePeriodDays: 14, allowedMisses: 2 },
      },
    };
    for (const mode of [null, undefined, 'UNCATEGORIZED' as const]) {
      const r = resolveForgivenessForMode(base, mode);
      expect(r.gracePeriodDays).toBe(7);
      expect(r.allowedMisses).toBe(1);
      expect(r.enabled).toBe(true);
      // byMode is intentionally dropped from the resolved config so
      // downstream walks don't re-resolve.
      expect(r.byMode).toBeUndefined();
    }
  });

  it('falls back to base config when byMode is omitted entirely', () => {
    const flat: ForgivenessConfig = {
      enabled: true,
      gracePeriodDays: 7,
      allowedMisses: 1,
      // No byMode.
    };
    const r = resolveForgivenessForMode(flat, 'PLAY');
    expect(r.gracePeriodDays).toBe(7);
    expect(r.allowedMisses).toBe(1);
  });

  it('falls back to base config when the specific mode is missing from byMode', () => {
    const partial: ForgivenessConfig = {
      enabled: true,
      gracePeriodDays: 7,
      allowedMisses: 1,
      byMode: {
        // Only play is overridden.
        play: { gracePeriodDays: 14, allowedMisses: 2 },
      },
    };
    const relax = resolveForgivenessForMode(partial, 'RELAX');
    expect(relax.gracePeriodDays).toBe(7);
    expect(relax.allowedMisses).toBe(1);

    const play = resolveForgivenessForMode(partial, 'PLAY');
    expect(play.gracePeriodDays).toBe(14);
    expect(play.allowedMisses).toBe(2);
  });

  it('honors the top-level enabled flag even when a mode is overridden', () => {
    const off: ForgivenessConfig = {
      enabled: false,
      gracePeriodDays: 7,
      allowedMisses: 1,
      byMode: {
        play: { gracePeriodDays: 14, allowedMisses: 5 },
      },
    };
    const r = resolveForgivenessForMode(off, 'PLAY');
    expect(r.enabled).toBe(false);
    // The window + misses still come from the override even though
    // enabled is false — the walk itself decides what to do with them.
    expect(r.gracePeriodDays).toBe(14);
    expect(r.allowedMisses).toBe(5);
  });

  it('exposes the Play/Relax wider defaults so callers can verify the canonical numbers', () => {
    // Pin the doc-side default to the exported constant. If
    // `DEFAULT_PLAY_RELAX_FORGIVENESS` ever drifts from the values
    // baked into `DEFAULT_FORGIVENESS.byMode`, the streak math diverges
    // from the user's Settings UI — this test catches the regression
    // before the surfaces drift.
    expect(DEFAULT_PLAY_RELAX_FORGIVENESS.gracePeriodDays).toBe(14);
    expect(DEFAULT_PLAY_RELAX_FORGIVENESS.allowedMisses).toBe(2);
    expect(DEFAULT_FORGIVENESS.byMode?.play).toEqual(
      DEFAULT_PLAY_RELAX_FORGIVENESS,
    );
    expect(DEFAULT_FORGIVENESS.byMode?.relax).toEqual(
      DEFAULT_PLAY_RELAX_FORGIVENESS,
    );
  });
});
