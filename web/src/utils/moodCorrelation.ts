/**
 * Pearson correlation between a mood (or energy) series and supporting
 * factors over a rolling window. Mirrors Android's
 * `MoodCorrelationEngine.kt` (parity audit C.3).
 *
 * Pure function: takes pre-aggregated `DailyObservation`s and returns
 * `CorrelationResult`s. The caller is responsible for stitching mood
 * logs together with task/habit/medication stats into observations.
 */

import type { MoodEnergyLog } from '@/api/firestore/moodEnergyLogs';

/** Daily observation bundle fed into the correlation routines. */
export interface DailyObservation {
  /** Epoch ms or any sortable number — used for ordering only. */
  date: number;
  /** 1..5 average mood for the day. */
  mood: number;
  /** 1..5 average energy for the day. */
  energy: number;
  tasksCompleted?: number;
  workTasksCompleted?: number;
  selfCareTasksCompleted?: number;
  habitCompletionRate?: number;
  medicationAdherence?: number;
  burnoutScore?: number;
}

export type CorrelationFactor =
  | 'TASKS_COMPLETED'
  | 'WORK_TASKS_COMPLETED'
  | 'SELF_CARE_TASKS_COMPLETED'
  | 'HABIT_COMPLETION_RATE'
  | 'MEDICATION_ADHERENCE'
  | 'BURNOUT_SCORE';

export type CorrelationStrength = 'WEAK' | 'MODERATE' | 'STRONG';

export interface CorrelationResult {
  factor: CorrelationFactor;
  targetLabel: 'mood' | 'energy';
  /** -1..1 Pearson coefficient. 0 when variance is zero. */
  coefficient: number;
  strength: CorrelationStrength;
}

export interface MoodCorrelationConfig {
  minObservations: number;
  moderateThreshold: number;
  strongThreshold: number;
}

export const DEFAULT_CORRELATION_CONFIG: MoodCorrelationConfig = {
  minObservations: 7,
  moderateThreshold: 0.3,
  strongThreshold: 0.6,
};

const ALL_FACTORS: CorrelationFactor[] = [
  'TASKS_COMPLETED',
  'WORK_TASKS_COMPLETED',
  'SELF_CARE_TASKS_COMPLETED',
  'HABIT_COMPLETION_RATE',
  'MEDICATION_ADHERENCE',
  'BURNOUT_SCORE',
];

/**
 * Correlate mood against every supported factor. Returns a list sorted
 * by absolute coefficient (strongest first). Returns an empty list when
 * fewer than `minObservations` days are supplied.
 */
export function correlateMood(
  observations: DailyObservation[],
  config: MoodCorrelationConfig = DEFAULT_CORRELATION_CONFIG,
): CorrelationResult[] {
  if (observations.length < config.minObservations) return [];
  const moods = observations.map((o) => o.mood);
  return ALL_FACTORS.map((factor) => {
    const values = observations.map((o) => extract(o, factor));
    const coef = pearson(moods, values);
    return {
      factor,
      targetLabel: 'mood' as const,
      coefficient: coef,
      strength: bucket(coef, config),
    };
  }).sort((a, b) => Math.abs(b.coefficient) - Math.abs(a.coefficient));
}

/** Same as `correlateMood` but for energy. */
export function correlateEnergy(
  observations: DailyObservation[],
  config: MoodCorrelationConfig = DEFAULT_CORRELATION_CONFIG,
): CorrelationResult[] {
  if (observations.length < config.minObservations) return [];
  const energies = observations.map((o) => o.energy);
  return ALL_FACTORS.map((factor) => {
    const values = observations.map((o) => extract(o, factor));
    const coef = pearson(energies, values);
    return {
      factor,
      targetLabel: 'energy' as const,
      coefficient: coef,
      strength: bucket(coef, config),
    };
  }).sort((a, b) => Math.abs(b.coefficient) - Math.abs(a.coefficient));
}

/**
 * Stitches raw mood/energy logs into per-day averages keyed by ISO date.
 * Days with multiple entries (morning + evening) are averaged.
 */
export function averageByDay(
  logs: MoodEnergyLog[],
): Map<string, { avgMood: number; avgEnergy: number }> {
  if (logs.length === 0) return new Map();
  const grouped = new Map<string, MoodEnergyLog[]>();
  for (const l of logs) {
    const key = l.date_iso;
    const existing = grouped.get(key);
    if (existing) existing.push(l);
    else grouped.set(key, [l]);
  }
  const out = new Map<string, { avgMood: number; avgEnergy: number }>();
  for (const [date, entries] of grouped) {
    const avgMood =
      entries.reduce((a, e) => a + e.mood, 0) / entries.length;
    const avgEnergy =
      entries.reduce((a, e) => a + e.energy, 0) / entries.length;
    out.set(date, { avgMood, avgEnergy });
  }
  return out;
}

function extract(obs: DailyObservation, factor: CorrelationFactor): number {
  switch (factor) {
    case 'TASKS_COMPLETED':
      return obs.tasksCompleted ?? 0;
    case 'WORK_TASKS_COMPLETED':
      return obs.workTasksCompleted ?? 0;
    case 'SELF_CARE_TASKS_COMPLETED':
      return obs.selfCareTasksCompleted ?? 0;
    case 'HABIT_COMPLETION_RATE':
      return obs.habitCompletionRate ?? 0;
    case 'MEDICATION_ADHERENCE':
      return obs.medicationAdherence ?? 0;
    case 'BURNOUT_SCORE':
      return obs.burnoutScore ?? 0;
  }
}

/**
 * Classic Pearson correlation coefficient. Returns 0 when variance is
 * zero (e.g., all mood values identical) so we don't report a misleading
 * "perfect correlation" on a flat series.
 */
export function pearson(xs: number[], ys: number[]): number {
  if (xs.length !== ys.length || xs.length === 0) return 0;
  const n = xs.length;
  const meanX = xs.reduce((a, b) => a + b, 0) / n;
  const meanY = ys.reduce((a, b) => a + b, 0) / n;
  let num = 0;
  let denomX = 0;
  let denomY = 0;
  for (let i = 0; i < n; i++) {
    const dx = xs[i] - meanX;
    const dy = ys[i] - meanY;
    num += dx * dy;
    denomX += dx * dx;
    denomY += dy * dy;
  }
  if (denomX === 0 || denomY === 0) return 0;
  const denom = Math.sqrt(denomX * denomY);
  const r = num / denom;
  return Math.max(-1, Math.min(1, r));
}

function bucket(coef: number, config: MoodCorrelationConfig): CorrelationStrength {
  const abs = Math.abs(coef);
  if (abs >= config.strongThreshold) return 'STRONG';
  if (abs >= config.moderateThreshold) return 'MODERATE';
  return 'WEAK';
}

/**
 * Plain-English explanation for a correlation result, suitable for use
 * as an analytics tooltip / list item.
 */
export function explainCorrelation(result: CorrelationResult): string {
  const direction = result.coefficient >= 0 ? 'higher' : 'lower';
  const factorLabel = (() => {
    switch (result.factor) {
      case 'TASKS_COMPLETED':
        return 'total task completions';
      case 'WORK_TASKS_COMPLETED':
        return 'work tasks';
      case 'SELF_CARE_TASKS_COMPLETED':
        return 'self-care tasks';
      case 'HABIT_COMPLETION_RATE':
        return 'habit completion rate';
      case 'MEDICATION_ADHERENCE':
        return 'medication adherence';
      case 'BURNOUT_SCORE':
        return 'burnout score';
    }
  })();
  return `Your ${result.targetLabel} tends to be ${direction} on days with more ${factorLabel} (${result.coefficient.toFixed(2)}).`;
}
