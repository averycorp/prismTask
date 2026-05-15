import { describe, expect, it } from 'vitest';
import {
  DEFAULT_MODE_BALANCE_CONFIG,
  EMPTY_MODE_BALANCE_STATE,
  computeModeBalanceState,
  isValidModeBalanceConfig,
} from '@/utils/modeBalanceTracker';
import type { Task } from '@/types/task';

/**
 * Mirrors Android's `ModeBalanceTrackerTest.kt` — the canonical model for
 * mode-ratio computation. Same SoD-aware semantics, same exclude-
 * Uncategorized rule, same dominant-mode resolution.
 */

function task(over: Partial<Task>): Task {
  const now = new Date().toISOString();
  return {
    id: 'id',
    title: 'task',
    description: null,
    priority: 0,
    due_date: null,
    completed: false,
    completed_at: null,
    created_at: now,
    updated_at: now,
    archived_at: null,
    subtask_progress: null,
    ...over,
  } as Task;
}

describe('isValidModeBalanceConfig', () => {
  it('returns true when targets sum to ~1.0', () => {
    expect(isValidModeBalanceConfig(DEFAULT_MODE_BALANCE_CONFIG)).toBe(true);
    expect(
      isValidModeBalanceConfig({ workTarget: 0.5, playTarget: 0.3, relaxTarget: 0.2 }),
    ).toBe(true);
  });
  it('returns false when targets do not sum to 1.0', () => {
    expect(
      isValidModeBalanceConfig({ workTarget: 0.5, playTarget: 0.5, relaxTarget: 0.5 }),
    ).toBe(false);
  });
});

describe('computeModeBalanceState', () => {
  const NOW = new Date('2026-05-13T15:00:00Z').getTime();

  it('returns zero state when no tasks have a task_mode', () => {
    const tasks = [task({}), task({ task_mode: 'UNCATEGORIZED' })];
    const state = computeModeBalanceState(tasks, DEFAULT_MODE_BALANCE_CONFIG, {
      nowMs: NOW,
    });
    expect(state.totalTracked).toBe(0);
    expect(state.dominantMode).toBe('UNCATEGORIZED');
    expect(state.currentRatios).toEqual(EMPTY_MODE_BALANCE_STATE.currentRatios);
  });

  it('excludes uncategorized tasks from counts', () => {
    const within = new Date('2026-05-13T10:00:00Z').toISOString();
    const tasks = [
      task({ id: '1', task_mode: null, created_at: within }),
      task({ id: '2', task_mode: 'UNCATEGORIZED', created_at: within }),
      task({ id: '3', task_mode: 'RELAX', created_at: within }),
    ];
    const state = computeModeBalanceState(tasks, DEFAULT_MODE_BALANCE_CONFIG, {
      nowMs: NOW,
    });
    expect(state.totalTracked).toBe(1);
    expect(state.dominantMode).toBe('RELAX');
  });

  it('counts tasks within the 7-day window and normalizes ratios', () => {
    const within = new Date('2026-05-13T10:00:00Z').toISOString();
    const tasks = [
      task({ id: '1', task_mode: 'WORK', created_at: within }),
      task({ id: '2', task_mode: 'WORK', created_at: within }),
      task({ id: '3', task_mode: 'PLAY', created_at: within }),
      task({ id: '4', task_mode: 'RELAX', created_at: within }),
    ];
    const state = computeModeBalanceState(tasks, DEFAULT_MODE_BALANCE_CONFIG, {
      nowMs: NOW,
    });
    expect(state.totalTracked).toBe(4);
    expect(state.currentRatios.WORK).toBeCloseTo(0.5);
    expect(state.currentRatios.PLAY).toBeCloseTo(0.25);
    expect(state.currentRatios.RELAX).toBeCloseTo(0.25);
    expect(state.dominantMode).toBe('WORK');
  });

  it('excludes tasks outside the 7-day window from current ratios', () => {
    const longAgo = new Date('2026-04-01T10:00:00Z').toISOString();
    const recent = new Date('2026-05-12T10:00:00Z').toISOString();
    const tasks = [
      task({ id: '1', task_mode: 'WORK', created_at: longAgo }),
      task({ id: '2', task_mode: 'PLAY', created_at: recent }),
    ];
    const state = computeModeBalanceState(tasks, DEFAULT_MODE_BALANCE_CONFIG, {
      nowMs: NOW,
    });
    expect(state.totalTracked).toBe(1);
    expect(state.currentRatios.PLAY).toBeCloseTo(1);
  });

  it('rolling 28-day window includes older tasks', () => {
    const twentyDaysAgo = new Date('2026-04-23T10:00:00Z').toISOString();
    const recent = new Date('2026-05-12T10:00:00Z').toISOString();
    const tasks = [
      task({ id: '1', task_mode: 'WORK', created_at: twentyDaysAgo }),
      task({ id: '2', task_mode: 'RELAX', created_at: recent }),
    ];
    const state = computeModeBalanceState(tasks, DEFAULT_MODE_BALANCE_CONFIG, {
      nowMs: NOW,
    });
    expect(state.rollingRatios.WORK).toBeCloseTo(0.5);
    expect(state.rollingRatios.RELAX).toBeCloseTo(0.5);
  });

  it('uses completed_at when present so historical-created tasks count when freshly completed', () => {
    const longAgo = new Date('2026-04-01T10:00:00Z').toISOString();
    const recent = new Date('2026-05-12T10:00:00Z').toISOString();
    const tasks = [
      task({ task_mode: 'WORK', created_at: longAgo, completed_at: recent }),
    ];
    const state = computeModeBalanceState(tasks, DEFAULT_MODE_BALANCE_CONFIG, {
      nowMs: NOW,
    });
    expect(state.totalTracked).toBe(1);
  });

  it('honors Start-of-Day when bucketing the 7-day window', () => {
    // now = 2026-05-13 02:30 local — *before* 4 AM SoD, so logical "today"
    // is 2026-05-12. The 7-day window covers 2026-05-06 04:00 onward.
    // Boundary task at 2026-05-06 05:00 should count under SoD=4;
    // boundary task at 2026-05-06 03:00 should NOT count.
    const NOW_0230_LOCAL = new Date(2026, 4, 13, 2, 30).getTime();
    const ok = new Date(2026, 4, 6, 5, 0).toISOString();
    const tooEarly = new Date(2026, 4, 6, 3, 0).toISOString();

    const tasks = [
      task({ id: 'ok', task_mode: 'PLAY', created_at: ok }),
      task({ id: 'early', task_mode: 'WORK', created_at: tooEarly }),
    ];

    const sod4 = computeModeBalanceState(tasks, DEFAULT_MODE_BALANCE_CONFIG, {
      nowMs: NOW_0230_LOCAL,
      dayStartHour: 4,
    });
    expect(sod4.totalTracked).toBe(1);
    expect(sod4.currentRatios.PLAY).toBeCloseTo(1);

    const midnight = computeModeBalanceState(tasks, DEFAULT_MODE_BALANCE_CONFIG, {
      nowMs: NOW_0230_LOCAL,
    });
    // At SoD=0, the 7-day cutoff is 2026-05-07 00:00, so neither task
    // falls in the window.
    expect(midnight.totalTracked).toBe(0);
  });

  it('dominant mode resolves to the largest current ratio', () => {
    const within = new Date('2026-05-13T10:00:00Z').toISOString();
    const tasks = [
      task({ id: '1', task_mode: 'RELAX', created_at: within }),
      task({ id: '2', task_mode: 'RELAX', created_at: within }),
      task({ id: '3', task_mode: 'RELAX', created_at: within }),
      task({ id: '4', task_mode: 'PLAY', created_at: within }),
    ];
    const state = computeModeBalanceState(tasks, DEFAULT_MODE_BALANCE_CONFIG, {
      nowMs: NOW,
    });
    expect(state.dominantMode).toBe('RELAX');
  });
});
