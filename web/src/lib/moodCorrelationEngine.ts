/**
 * Habit ↔ mood / energy correlation engine.
 *
 * Mirrors Android `MoodCorrelationEngine.kt` (Pearson over daily
 * observations). The shared low-level helpers (`pearson`,
 * `averageByDay`, `correlateMood`, `correlateEnergy`) live in
 * `utils/moodCorrelation.ts`. This module wraps them with a habit-
 * focused entry point used by `HabitAnalyticsScreen`:
 *
 *   correlate(habitCompletions, moodLogs, lifeCategoryFilter?)
 *     → { r, n, p } | null
 *
 * Returns `null` when fewer than five paired daily observations exist
 * (parity unit 16/23 spec — looser than Android's
 * `MIN_OBSERVATIONS = 7` because the web entry point analyses a single
 * habit instead of all-tasks vs. mood, so the sample is smaller).
 *
 * `lifeCategoryFilter` is accepted for API parity with the Android
 * engine but is a no-op for habit ↔ mood pairing (habits don't carry
 * a life category). Reserved for future task-correlation hooks.
 */

import type { HabitCompletion } from '@/types/habit';
import type { MoodEnergyLog } from '@/api/firestore/moodEnergyLogs';
import { averageByDay, pearson } from '@/utils/moodCorrelation';

export type LifeCategory =
  | 'WORK'
  | 'PERSONAL'
  | 'SELF_CARE'
  | 'HEALTH'
  | 'UNCATEGORIZED';

export interface HabitMoodCorrelation {
  /** Pearson coefficient in [-1, 1]. */
  r: number;
  /** Number of paired daily observations. */
  n: number;
  /**
   * Two-tailed p-value derived from the Student-t approximation
   * `t = r * sqrt((n-2) / (1 - r²))`. Approximation only — accuracy
   * is adequate for the "weak / moderate / strong" UI bucketing.
   */
  p: number;
}

/** Minimum paired observations before a correlation is reported. */
export const MIN_PAIRED_OBSERVATIONS = 5;

/**
 * Verbal strength bucket used by the analytics card. Mirrors the
 * three-bucket scheme on Android (`CorrelationStrength`) plus a
 * neutral bucket for near-zero r.
 */
export type CorrelationStrengthLabel =
  | 'strong positive'
  | 'moderate positive'
  | 'weak positive'
  | 'no relationship'
  | 'weak negative'
  | 'moderate negative'
  | 'strong negative';

export function interpretCorrelation(r: number): CorrelationStrengthLabel {
  const abs = Math.abs(r);
  if (abs < 0.1) return 'no relationship';
  const sign = r >= 0 ? 'positive' : 'negative';
  if (abs >= 0.6) return `strong ${sign}` as CorrelationStrengthLabel;
  if (abs >= 0.3) return `moderate ${sign}` as CorrelationStrengthLabel;
  return `weak ${sign}` as CorrelationStrengthLabel;
}

/**
 * Build pairs of (habit completion count, mood/energy value) keyed by
 * ISO date. Days without a mood log are dropped (no extrapolation).
 * Days without a habit completion contribute a zero count — mirroring
 * Android's `DailyObservation` defaulting to `0` for absent factors.
 */
function buildPairs(
  habitCompletions: HabitCompletion[],
  moodLogs: MoodEnergyLog[],
  signal: 'mood' | 'energy',
): { habitCounts: number[]; signalValues: number[] } {
  const moodByDay = averageByDay(moodLogs);
  if (moodByDay.size === 0) return { habitCounts: [], signalValues: [] };

  const habitByDay = new Map<string, number>();
  for (const c of habitCompletions) {
    habitByDay.set(c.date, (habitByDay.get(c.date) ?? 0) + c.count);
  }

  const habitCounts: number[] = [];
  const signalValues: number[] = [];
  for (const [date, { avgMood, avgEnergy }] of moodByDay) {
    habitCounts.push(habitByDay.get(date) ?? 0);
    signalValues.push(signal === 'mood' ? avgMood : avgEnergy);
  }
  return { habitCounts, signalValues };
}

/**
 * Approximate two-tailed p-value for Pearson r using the t-statistic
 * `t = r * sqrt((n - 2) / (1 - r²))` and a standard-normal tail
 * approximation (Abramowitz & Stegun 26.2.17). Accuracy is adequate
 * for the UI bucket; we don't try to be a stats library.
 */
function pValueForR(r: number, n: number): number {
  if (n < 3) return 1;
  if (r >= 1 || r <= -1) return 0;
  const denom = 1 - r * r;
  if (denom <= 0) return 0;
  const t = Math.abs(r) * Math.sqrt((n - 2) / denom);
  // Standard-normal CDF approximation (good enough for df ≥ 3 here).
  const p = 2 * (1 - normalCdf(t));
  return Math.max(0, Math.min(1, p));
}

function normalCdf(x: number): number {
  // Abramowitz & Stegun 26.2.17 — error < 7.5e-8.
  const b1 = 0.319381530;
  const b2 = -0.356563782;
  const b3 = 1.781477937;
  const b4 = -1.821255978;
  const b5 = 1.330274429;
  const p = 0.2316419;
  const c = 0.39894228;
  const ax = Math.abs(x);
  const k = 1 / (1 + p * ax);
  const phi = c * Math.exp((-ax * ax) / 2);
  const cdf =
    1 -
    phi *
      k *
      (b1 + k * (b2 + k * (b3 + k * (b4 + k * b5))));
  return x >= 0 ? cdf : 1 - cdf;
}

/**
 * Correlate a habit's daily completion counts against either mood or
 * energy daily averages. Returns `null` when fewer than
 * `MIN_PAIRED_OBSERVATIONS` paired days exist — the UI should treat
 * null as "not enough data yet" rather than "no correlation".
 *
 * `lifeCategoryFilter` is reserved for future task-level hooks; it is
 * accepted for API symmetry with Android's task-vs-mood engine and is
 * currently a no-op for habit pairing.
 */
export function correlate(
  habitCompletions: HabitCompletion[],
  moodLogs: MoodEnergyLog[],
  _lifeCategoryFilter?: LifeCategory,
  signal: 'mood' | 'energy' = 'mood',
): HabitMoodCorrelation | null {
  const { habitCounts, signalValues } = buildPairs(
    habitCompletions,
    moodLogs,
    signal,
  );
  const n = habitCounts.length;
  if (n < MIN_PAIRED_OBSERVATIONS) return null;
  const r = pearson(habitCounts, signalValues);
  const p = pValueForR(r, n);
  return { r, n, p };
}

/** Convenience: mood pairing only. */
export function correlateMoodWithHabit(
  habitCompletions: HabitCompletion[],
  moodLogs: MoodEnergyLog[],
): HabitMoodCorrelation | null {
  return correlate(habitCompletions, moodLogs, undefined, 'mood');
}

/** Convenience: energy pairing only. */
export function correlateEnergyWithHabit(
  habitCompletions: HabitCompletion[],
  moodLogs: MoodEnergyLog[],
): HabitMoodCorrelation | null {
  return correlate(habitCompletions, moodLogs, undefined, 'energy');
}
