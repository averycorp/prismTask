import { describe, expect, it } from 'vitest';
import {
  correlate,
  correlateMoodWithHabit,
  correlateEnergyWithHabit,
  interpretCorrelation,
  MIN_PAIRED_OBSERVATIONS,
} from '@/lib/moodCorrelationEngine';
import type { HabitCompletion } from '@/types/habit';
import type { MoodEnergyLog } from '@/api/firestore/moodEnergyLogs';

function completion(date: string, count = 1): HabitCompletion {
  return {
    id: `${date}-${count}`,
    habit_id: 'habit-1',
    date,
    count,
    created_at: date,
  };
}

function moodLog(
  date_iso: string,
  mood: number,
  energy: number,
): MoodEnergyLog {
  return {
    id: `${date_iso}__morning`,
    date_iso,
    mood,
    energy,
    notes: '',
    time_of_day: 'morning',
    created_at: 0,
    updated_at: 0,
  };
}

describe('moodCorrelationEngine.correlate', () => {
  it('returns null when fewer than MIN_PAIRED_OBSERVATIONS paired days exist', () => {
    const completions = [
      completion('2026-05-01'),
      completion('2026-05-02'),
      completion('2026-05-03'),
      completion('2026-05-04'),
    ];
    const moods = [
      moodLog('2026-05-01', 3, 3),
      moodLog('2026-05-02', 4, 4),
      moodLog('2026-05-03', 5, 5),
      moodLog('2026-05-04', 3, 3),
    ];
    expect(correlate(completions, moods)).toBeNull();
    expect(MIN_PAIRED_OBSERVATIONS).toBe(5);
  });

  it('computes Pearson r and reports n / p when at least 5 paired days exist', () => {
    // Perfectly positive correlation between completion count and mood.
    const dates = [
      '2026-05-01',
      '2026-05-02',
      '2026-05-03',
      '2026-05-04',
      '2026-05-05',
    ];
    const completions = dates.map((d, i) => completion(d, i + 1));
    const moods = dates.map((d, i) => moodLog(d, i + 1, 3));
    const result = correlate(completions, moods, undefined, 'mood');
    expect(result).not.toBeNull();
    expect(result!.n).toBe(5);
    expect(result!.r).toBeCloseTo(1, 5);
    // n = 5 with r = 1 → p ≈ 0
    expect(result!.p).toBeLessThan(0.05);
  });

  it('handles perfect anti-correlation symmetrically', () => {
    const dates = [
      '2026-05-01',
      '2026-05-02',
      '2026-05-03',
      '2026-05-04',
      '2026-05-05',
    ];
    const completions = dates.map((d, i) => completion(d, 5 - i));
    const moods = dates.map((d, i) => moodLog(d, i + 1, 3));
    const result = correlate(completions, moods, undefined, 'mood');
    expect(result).not.toBeNull();
    expect(result!.r).toBeCloseTo(-1, 5);
  });

  it('returns r = 0 when the mood series has zero variance', () => {
    // Flat mood, varying completion counts — pearson should report 0
    // (no relationship), not NaN.
    const dates = [
      '2026-05-01',
      '2026-05-02',
      '2026-05-03',
      '2026-05-04',
      '2026-05-05',
    ];
    const completions = dates.map((d, i) => completion(d, i));
    const moods = dates.map((d) => moodLog(d, 4, 4));
    const result = correlate(completions, moods);
    expect(result).not.toBeNull();
    expect(result!.r).toBe(0);
  });

  it('drops mood log days without coverage and pads habit-less days with zero', () => {
    // Five mood days, only three habit completions — counts on the
    // missing days default to zero.
    const moods = [
      moodLog('2026-05-01', 1, 1),
      moodLog('2026-05-02', 2, 2),
      moodLog('2026-05-03', 3, 3),
      moodLog('2026-05-04', 4, 4),
      moodLog('2026-05-05', 5, 5),
    ];
    const completions = [
      completion('2026-05-03', 1),
      completion('2026-05-04', 2),
      completion('2026-05-05', 3),
    ];
    const result = correlate(completions, moods);
    expect(result).not.toBeNull();
    expect(result!.n).toBe(5);
    // Positive correlation (high mood days are also high completion days).
    expect(result!.r).toBeGreaterThan(0);
  });

  it('correlateEnergyWithHabit pairs against energy instead of mood', () => {
    const dates = [
      '2026-05-01',
      '2026-05-02',
      '2026-05-03',
      '2026-05-04',
      '2026-05-05',
    ];
    const completions = dates.map((d, i) => completion(d, i + 1));
    // Energy positively correlated with completions, mood flat.
    const moods = dates.map((d, i) => moodLog(d, 3, i + 1));
    expect(correlateMoodWithHabit(completions, moods)!.r).toBe(0);
    expect(correlateEnergyWithHabit(completions, moods)!.r).toBeCloseTo(1, 5);
  });

  it('handles empty inputs without throwing', () => {
    expect(correlate([], [])).toBeNull();
    expect(correlate([completion('2026-05-01')], [])).toBeNull();
  });
});

describe('interpretCorrelation', () => {
  it('buckets r into verbal labels', () => {
    expect(interpretCorrelation(0.0)).toBe('no relationship');
    expect(interpretCorrelation(0.05)).toBe('no relationship');
    expect(interpretCorrelation(0.2)).toBe('weak positive');
    expect(interpretCorrelation(-0.2)).toBe('weak negative');
    expect(interpretCorrelation(0.4)).toBe('moderate positive');
    expect(interpretCorrelation(-0.45)).toBe('moderate negative');
    expect(interpretCorrelation(0.7)).toBe('strong positive');
    expect(interpretCorrelation(-0.9)).toBe('strong negative');
  });
});
