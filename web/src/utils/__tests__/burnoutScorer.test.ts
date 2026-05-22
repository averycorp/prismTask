import { describe, it, expect } from 'vitest';
import { scoreBurnout, type BurnoutInputs } from '../burnoutScorer';
import type { Breach } from '@/utils/boundaryEnforcer';
import type { MoodEnergyLog } from '@/api/firestore/moodEnergyLogs';

describe('burnoutScorer', () => {
  it('should return a calm score when all signals are good', () => {
    const inputs: BurnoutInputs = {
      breaches: [],
      recent_mood_logs: [
        { id: '1', mood: 5, energy: 5, timestamp: '2023-01-01T00:00:00Z', user_id: 'user', date: '2023-01-01', notes: '' } as unknown as MoodEnergyLog,
      ],
      active_tasks_today: 0,
      task_soft_cap: 10,
    };

    const result = scoreBurnout(inputs);
    expect(result.score).toBe(0);
    expect(result.bucket).toBe('calm');
    expect(result.factors).toEqual([
      { label: 'Active breaches', value: 0 },
      { label: 'Recent mood', value: 0 },
      { label: 'Recent energy', value: 0 },
      { label: 'Task overload', value: 0 },
    ]);
  });

  it('should calculate breach component correctly', () => {
    const inputs: BurnoutInputs = {
      breaches: [
        { severity: 'alert' } as Breach,
        { severity: 'warn' } as Breach,
        { severity: 'info' } as Breach,
      ],
      recent_mood_logs: [],
      active_tasks_today: 0,
      task_soft_cap: 10,
    };

    const result = scoreBurnout(inputs);
    expect(result.factors[0].value).toBe(34); // 20 + 10 + 4
  });

  it('should cap breach component at 40', () => {
    const inputs: BurnoutInputs = {
      breaches: [
        { severity: 'alert' } as Breach,
        { severity: 'alert' } as Breach,
        { severity: 'alert' } as Breach,
      ],
      recent_mood_logs: [],
      active_tasks_today: 0,
      task_soft_cap: 10,
    };

    const result = scoreBurnout(inputs);
    expect(result.factors[0].value).toBe(40);
  });

  it('should calculate mood component correctly', () => {
    const inputs: BurnoutInputs = {
      breaches: [],
      recent_mood_logs: [
        { mood: 1, energy: 5 } as MoodEnergyLog,
        { mood: 1, energy: 5 } as MoodEnergyLog,
      ],
      active_tasks_today: 0,
      task_soft_cap: 10,
    };

    const result = scoreBurnout(inputs);
    // avg mood = 1. (3 - 1) * 12.5 = 25
    expect(result.factors[1].value).toBe(25);
  });

  it('should calculate energy component correctly', () => {
    const inputs: BurnoutInputs = {
      breaches: [],
      recent_mood_logs: [
        { mood: 5, energy: 1 } as MoodEnergyLog,
        { mood: 5, energy: 1 } as MoodEnergyLog,
      ],
      active_tasks_today: 0,
      task_soft_cap: 10,
    };

    const result = scoreBurnout(inputs);
    // avg energy = 1. (3 - 1) * 10 = 20
    expect(result.factors[2].value).toBe(20);
  });

  it('should calculate task overload correctly', () => {
    const inputs: BurnoutInputs = {
      breaches: [],
      recent_mood_logs: [],
      active_tasks_today: 15,
      task_soft_cap: 10,
    };

    const result = scoreBurnout(inputs);
    // over = 5. 5 * 2 = 10
    expect(result.factors[3].value).toBe(10);
  });

  it('should cap task overload at 15', () => {
    const inputs: BurnoutInputs = {
      breaches: [],
      recent_mood_logs: [],
      active_tasks_today: 20,
      task_soft_cap: 10,
    };

    const result = scoreBurnout(inputs);
    // over = 10. 10 * 2 = 20. capped at 15
    expect(result.factors[3].value).toBe(15);
  });

  it('should classify buckets correctly', () => {
    const testCases: Array<{ score: number; bucket: string; inputs: BurnoutInputs }> = [
      { score: 24, bucket: 'calm', inputs: { breaches: [{severity: 'alert'} as Breach, {severity: 'info'} as Breach], recent_mood_logs: [{mood: 3, energy: 3} as MoodEnergyLog], active_tasks_today: 10, task_soft_cap: 10 } }, // 20 + 4
      { score: 30, bucket: 'moderate', inputs: { breaches: [{severity: 'alert'} as Breach, {severity: 'warn'} as Breach], recent_mood_logs: [{mood: 3, energy: 3} as MoodEnergyLog], active_tasks_today: 10, task_soft_cap: 10 } }, // 20 + 10
      { score: 49, bucket: 'moderate', inputs: { breaches: [{severity: 'alert'} as Breach, {severity: 'alert'} as Breach], recent_mood_logs: [{mood: 2.28, energy: 3} as MoodEnergyLog], active_tasks_today: 10, task_soft_cap: 10 } }, // 40 + (3-2.28)*12.5 = 40 + 9
      { score: 50, bucket: 'risky', inputs: { breaches: [{severity: 'alert'} as Breach, {severity: 'alert'} as Breach], recent_mood_logs: [{mood: 2.2, energy: 3} as MoodEnergyLog], active_tasks_today: 10, task_soft_cap: 10 } }, // 40 + (3-2.2)*12.5 = 40 + 10
      { score: 74, bucket: 'risky', inputs: { breaches: [{severity: 'alert'} as Breach, {severity: 'alert'} as Breach], recent_mood_logs: [{mood: 1, energy: 2.1} as MoodEnergyLog], active_tasks_today: 10, task_soft_cap: 10 } }, // 40 + 25 + (3-2.1)*10 = 65 + 9
      { score: 75, bucket: 'burning', inputs: { breaches: [{severity: 'alert'} as Breach, {severity: 'alert'} as Breach], recent_mood_logs: [{mood: 1, energy: 2} as MoodEnergyLog], active_tasks_today: 10, task_soft_cap: 10 } }, // 40 + 25 + 10 = 75
    ];

    testCases.forEach((tc) => {
      const result = scoreBurnout(tc.inputs);
      expect(result.score).toBe(tc.score);
      expect(result.bucket).toBe(tc.bucket);
    });
  });

  it('should handle missing recent mood logs gracefully', () => {
    const inputs: BurnoutInputs = {
      breaches: [],
      recent_mood_logs: [],
      active_tasks_today: 0,
      task_soft_cap: 10,
    };

    const result = scoreBurnout(inputs);
    expect(result.factors[1].value).toBe(0); // defaults to avg 3 -> (3-3)=0
    expect(result.factors[2].value).toBe(0);
  });
});
