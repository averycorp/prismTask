import { describe, expect, it } from 'vitest';
import {
  averageByDay,
  correlateMood,
  pearson,
  explainCorrelation,
  type DailyObservation,
} from '@/utils/moodCorrelation';
import type { MoodEnergyLog } from '@/api/firestore/moodEnergyLogs';

function obs(d: number, mood: number, energy: number, over: Partial<DailyObservation> = {}): DailyObservation {
  return { date: d, mood, energy, ...over };
}

describe('pearson', () => {
  it('returns 1 for perfectly correlated series', () => {
    expect(pearson([1, 2, 3, 4], [2, 4, 6, 8])).toBeCloseTo(1, 5);
  });

  it('returns -1 for perfectly anti-correlated series', () => {
    expect(pearson([1, 2, 3, 4], [4, 3, 2, 1])).toBeCloseTo(-1, 5);
  });

  it('returns 0 for orthogonal series', () => {
    expect(pearson([1, 2, 3, 4, 5], [3, 3, 3, 3, 3])).toBe(0);
  });

  it('returns 0 for mismatched-length inputs', () => {
    expect(pearson([1, 2], [1, 2, 3])).toBe(0);
  });
});

describe('correlateMood', () => {
  it('returns empty when fewer than minObservations days are supplied', () => {
    const observations = Array.from({ length: 6 }, (_, i) => obs(i, 3, 3));
    expect(correlateMood(observations)).toEqual([]);
  });

  it('returns sorted results by absolute coefficient (strongest first)', () => {
    const observations: DailyObservation[] = [
      obs(1, 1, 3, { tasksCompleted: 1, burnoutScore: 90 }),
      obs(2, 2, 3, { tasksCompleted: 2, burnoutScore: 80 }),
      obs(3, 3, 3, { tasksCompleted: 3, burnoutScore: 70 }),
      obs(4, 4, 3, { tasksCompleted: 4, burnoutScore: 60 }),
      obs(5, 5, 3, { tasksCompleted: 5, burnoutScore: 50 }),
      obs(6, 4, 3, { tasksCompleted: 4, burnoutScore: 55 }),
      obs(7, 3, 3, { tasksCompleted: 3, burnoutScore: 65 }),
    ];
    const results = correlateMood(observations);
    // burnoutScore is perfectly anti-correlated with mood, tasksCompleted positively
    expect(results[0].strength).not.toBe('WEAK');
    for (let i = 1; i < results.length; i++) {
      expect(Math.abs(results[i - 1].coefficient)).toBeGreaterThanOrEqual(
        Math.abs(results[i].coefficient),
      );
    }
  });
});

describe('averageByDay', () => {
  it('averages multiple entries per day', () => {
    const log = (date: string, mood: number, energy: number): MoodEnergyLog =>
      ({
        id: `${date}-${mood}`,
        date_iso: date,
        mood,
        energy,
        timestamp: 0,
      }) as unknown as MoodEnergyLog;
    const out = averageByDay([
      log('2026-05-13', 2, 3),
      log('2026-05-13', 4, 5),
      log('2026-05-14', 3, 3),
    ]);
    expect(out.get('2026-05-13')).toEqual({ avgMood: 3, avgEnergy: 4 });
    expect(out.get('2026-05-14')).toEqual({ avgMood: 3, avgEnergy: 3 });
  });
});

describe('explainCorrelation', () => {
  it('produces a readable sentence', () => {
    expect(
      explainCorrelation({
        factor: 'BURNOUT_SCORE',
        targetLabel: 'mood',
        coefficient: -0.72,
        strength: 'STRONG',
      }),
    ).toBe(
      'Your mood tends to be lower on days with more burnout score (-0.72).',
    );
  });
});
