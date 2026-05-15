import { describe, it, expect } from 'vitest';
import {
  DEFAULT_ENERGY_POMODORO_CONFIG,
  DEFAULT_POMODORO_CONFIG,
  planPomodoroFromEnergy,
  planPomodoroFromLogs,
} from '@/utils/energyAwarePomodoro';
import type { MoodEnergyLog } from '@/api/firestore/moodEnergyLogs';

function log(overrides: Partial<MoodEnergyLog> = {}): MoodEnergyLog {
  return {
    id: 'log-1',
    date_iso: '2026-05-15',
    mood: 3,
    energy: 3,
    notes: '',
    time_of_day: 'morning',
    created_at: 1_700_000_000_000,
    updated_at: 1_700_000_000_000,
    ...overrides,
  };
}

describe('planPomodoroFromEnergy', () => {
  it('returns classic defaults when no energy is logged', () => {
    const plan = planPomodoroFromEnergy(null);
    expect(plan.workMinutes).toBe(DEFAULT_POMODORO_CONFIG.workMinutes);
    expect(plan.breakMinutes).toBe(DEFAULT_POMODORO_CONFIG.breakMinutes);
    expect(plan.longBreakMinutes).toBe(
      DEFAULT_POMODORO_CONFIG.longBreakMinutes,
    );
    expect(plan.rationale).toMatch(/classic/i);
  });

  it('returns very-low band for energy 1', () => {
    const plan = planPomodoroFromEnergy(1);
    expect(plan.workMinutes).toBe(DEFAULT_ENERGY_POMODORO_CONFIG.veryLowWork);
    expect(plan.breakMinutes).toBe(DEFAULT_ENERGY_POMODORO_CONFIG.veryLowBreak);
    expect(plan.rationale).toMatch(/low-energy/i);
  });

  it('returns low band for energy 2', () => {
    const plan = planPomodoroFromEnergy(2);
    expect(plan.workMinutes).toBe(DEFAULT_ENERGY_POMODORO_CONFIG.lowWork);
    expect(plan.breakMinutes).toBe(DEFAULT_ENERGY_POMODORO_CONFIG.lowBreak);
  });

  it('returns classic medium band for energy 3', () => {
    const plan = planPomodoroFromEnergy(3);
    expect(plan.workMinutes).toBe(DEFAULT_ENERGY_POMODORO_CONFIG.mediumWork);
    expect(plan.breakMinutes).toBe(DEFAULT_ENERGY_POMODORO_CONFIG.mediumBreak);
    expect(plan.rationale).toMatch(/classic|groove/i);
  });

  it('returns high band for energy 4', () => {
    const plan = planPomodoroFromEnergy(4);
    expect(plan.workMinutes).toBe(DEFAULT_ENERGY_POMODORO_CONFIG.highWork);
    expect(plan.breakMinutes).toBe(DEFAULT_ENERGY_POMODORO_CONFIG.highBreak);
    expect(plan.rationale).toMatch(/high-energy|deep-work/i);
  });

  it('returns very-high band for energy 5', () => {
    const plan = planPomodoroFromEnergy(5);
    expect(plan.workMinutes).toBe(DEFAULT_ENERGY_POMODORO_CONFIG.veryHighWork);
    expect(plan.breakMinutes).toBe(
      DEFAULT_ENERGY_POMODORO_CONFIG.veryHighBreak,
    );
    expect(plan.rationale).toMatch(/peak|sprint/i);
  });

  it('clamps out-of-range energy values', () => {
    expect(planPomodoroFromEnergy(0).workMinutes).toBe(
      DEFAULT_ENERGY_POMODORO_CONFIG.veryLowWork,
    );
    expect(planPomodoroFromEnergy(99).workMinutes).toBe(
      DEFAULT_ENERGY_POMODORO_CONFIG.veryHighWork,
    );
  });

  it('treats NaN like null', () => {
    const plan = planPomodoroFromEnergy(Number.NaN);
    expect(plan.workMinutes).toBe(DEFAULT_POMODORO_CONFIG.workMinutes);
  });
});

describe('planPomodoroFromLogs', () => {
  it('returns defaults when no logs are passed', () => {
    expect(planPomodoroFromLogs([]).workMinutes).toBe(
      DEFAULT_POMODORO_CONFIG.workMinutes,
    );
    expect(planPomodoroFromLogs(null).workMinutes).toBe(
      DEFAULT_POMODORO_CONFIG.workMinutes,
    );
  });

  it('uses the most recent log by created_at', () => {
    const logs: MoodEnergyLog[] = [
      log({ id: 'a', energy: 5, created_at: 1_000 }),
      log({ id: 'b', energy: 1, created_at: 5_000 }),
      log({ id: 'c', energy: 3, created_at: 2_000 }),
    ];
    const plan = planPomodoroFromLogs(logs);
    expect(plan.workMinutes).toBe(DEFAULT_ENERGY_POMODORO_CONFIG.veryLowWork);
  });

  it('respects custom defaults when energy is null', () => {
    const plan = planPomodoroFromLogs([], {
      workMinutes: 50,
      breakMinutes: 10,
      longBreakMinutes: 30,
    });
    expect(plan.workMinutes).toBe(50);
    expect(plan.breakMinutes).toBe(10);
  });
});
