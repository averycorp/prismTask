import { describe, it, expect } from 'vitest';
import { scoreBurnout } from '../burnoutScorer';

describe('scoreBurnout', () => {
  it('returns calm for low inputs', () => {
    const result = scoreBurnout({
      breaches: [],
      recent_mood_logs: [{ mood: 4, energy: 4 } as any],
      active_tasks_today: 2,
      task_soft_cap: 5,
    });
    expect(result.bucket).toBe('calm');
    expect(result.score).toBe(0);
  });

  it('handles empty mood logs by defaulting to 3', () => {
    const result = scoreBurnout({
      breaches: [],
      recent_mood_logs: [],
      active_tasks_today: 2,
      task_soft_cap: 5,
    });
    // Mood default 3 -> 3-3 = 0, Energy default 3 -> 3-3 = 0
    expect(result.score).toBe(0);
  });

  it('calculates breaches correctly', () => {
    const result = scoreBurnout({
      breaches: [
        { severity: 'alert' } as any,
        { severity: 'warn' } as any,
        { severity: 'soft' } as any,
      ],
      recent_mood_logs: [],
      active_tasks_today: 0,
      task_soft_cap: 5,
    });
    // Alert (20) + Warn (10) + Soft (4) = 34
    expect(result.factors.find(f => f.label === 'Active breaches')?.value).toBe(34);
  });

  it('caps breach score at 40', () => {
    const result = scoreBurnout({
      breaches: [
        { severity: 'alert' } as any,
        { severity: 'alert' } as any,
        { severity: 'alert' } as any,
      ],
      recent_mood_logs: [],
      active_tasks_today: 0,
      task_soft_cap: 5,
    });
    expect(result.factors.find(f => f.label === 'Active breaches')?.value).toBe(40);
  });

  it('calculates mood correctly', () => {
    const result = scoreBurnout({
      breaches: [],
      recent_mood_logs: [
        { mood: 1, energy: 5 } as any, // Only mood is 1
      ],
      active_tasks_today: 0,
      task_soft_cap: 5,
    });
    // Avg mood 1 -> (3 - 1) * 12.5 = 25
    expect(result.factors.find(f => f.label === 'Recent mood')?.value).toBe(25);
  });

  it('caps mood score at 25 and minimum 0', () => {
    const highMoodResult = scoreBurnout({
      breaches: [],
      recent_mood_logs: [{ mood: 5, energy: 5 } as any],
      active_tasks_today: 0,
      task_soft_cap: 5,
    });
    expect(highMoodResult.factors.find(f => f.label === 'Recent mood')?.value).toBe(0);

    const zeroMoodResult = scoreBurnout({
      breaches: [],
      recent_mood_logs: [{ mood: 0, energy: 5 } as any], // Extreme low mood
      active_tasks_today: 0,
      task_soft_cap: 5,
    });
    expect(zeroMoodResult.factors.find(f => f.label === 'Recent mood')?.value).toBe(25);
  });

  it('calculates energy correctly', () => {
    const result = scoreBurnout({
      breaches: [],
      recent_mood_logs: [
        { mood: 5, energy: 1 } as any, // Only energy is 1
      ],
      active_tasks_today: 0,
      task_soft_cap: 5,
    });
    // Avg energy 1 -> (3 - 1) * 10 = 20
    expect(result.factors.find(f => f.label === 'Recent energy')?.value).toBe(20);
  });

  it('caps energy score at 20 and minimum 0', () => {
    const highEnergyResult = scoreBurnout({
      breaches: [],
      recent_mood_logs: [{ mood: 5, energy: 5 } as any],
      active_tasks_today: 0,
      task_soft_cap: 5,
    });
    expect(highEnergyResult.factors.find(f => f.label === 'Recent energy')?.value).toBe(0);

    const zeroEnergyResult = scoreBurnout({
      breaches: [],
      recent_mood_logs: [{ mood: 5, energy: 0 } as any], // Extreme low energy
      active_tasks_today: 0,
      task_soft_cap: 5,
    });
    expect(zeroEnergyResult.factors.find(f => f.label === 'Recent energy')?.value).toBe(20);
  });

  it('calculates task overload correctly', () => {
    const result = scoreBurnout({
      breaches: [],
      recent_mood_logs: [],
      active_tasks_today: 10,
      task_soft_cap: 5,
    });
    // 5 over cap * 2 = 10
    expect(result.factors.find(f => f.label === 'Task overload')?.value).toBe(10);
  });

  it('caps task overload score at 15 and minimum 0', () => {
    const underCapResult = scoreBurnout({
      breaches: [],
      recent_mood_logs: [],
      active_tasks_today: 3,
      task_soft_cap: 5,
    });
    expect(underCapResult.factors.find(f => f.label === 'Task overload')?.value).toBe(0);

    const extremeOverloadResult = scoreBurnout({
      breaches: [],
      recent_mood_logs: [],
      active_tasks_today: 20,
      task_soft_cap: 5,
    });
    expect(extremeOverloadResult.factors.find(f => f.label === 'Task overload')?.value).toBe(15);
  });

  it('categorizes buckets correctly', () => {
    expect(scoreBurnout({ breaches: [], recent_mood_logs: [], active_tasks_today: 0, task_soft_cap: 5 }).bucket).toBe('calm'); // 0

    expect(scoreBurnout({
      breaches: [{ severity: 'alert' } as any, { severity: 'warn' } as any], // 30
      recent_mood_logs: [], active_tasks_today: 0, task_soft_cap: 5
    }).bucket).toBe('moderate');

    expect(scoreBurnout({
      breaches: [{ severity: 'alert' } as any, { severity: 'alert' } as any], // 40
      recent_mood_logs: [{ mood: 1, energy: 3 } as any], // 25
      active_tasks_today: 0, task_soft_cap: 5
    }).bucket).toBe('risky'); // 65

    expect(scoreBurnout({
      breaches: [{ severity: 'alert' } as any, { severity: 'alert' } as any], // 40
      recent_mood_logs: [{ mood: 1, energy: 1 } as any], // 25 + 20 = 45
      active_tasks_today: 10, task_soft_cap: 5 // 10
    }).bucket).toBe('burning'); // 95
  });
});
