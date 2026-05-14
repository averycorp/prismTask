import { describe, expect, it } from 'vitest';
import {
  DEFAULT_BALANCE_CONFIG,
  EMPTY_BALANCE_STATE,
  computeBalanceState,
  isValidBalanceConfig,
} from '@/utils/balanceTracker';
import type { Task } from '@/types/task';

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

describe('isValidBalanceConfig', () => {
  it('returns true when targets sum to ~1.0', () => {
    expect(isValidBalanceConfig(DEFAULT_BALANCE_CONFIG)).toBe(true);
  });
  it('returns false when targets do not sum to 1.0', () => {
    expect(
      isValidBalanceConfig({
        ...DEFAULT_BALANCE_CONFIG,
        workTarget: 0.6,
      }),
    ).toBe(false);
  });
});

describe('computeBalanceState', () => {
  const NOW = new Date('2026-05-13T15:00:00Z').getTime();

  it('returns zero state when no tasks have a life_category', () => {
    const tasks = [task({}), task({ life_category: 'UNCATEGORIZED' })];
    const state = computeBalanceState(tasks, DEFAULT_BALANCE_CONFIG, { nowMs: NOW });
    expect(state.totalTracked).toBe(0);
    expect(state.dominantCategory).toBe('UNCATEGORIZED');
    expect(state.isOverloaded).toBe(false);
    expect(state.currentRatios).toEqual(EMPTY_BALANCE_STATE.currentRatios);
  });

  it('counts tasks within the 7-day window and computes ratios', () => {
    const within = new Date('2026-05-13T10:00:00Z').toISOString();
    const tasks = [
      task({ id: '1', life_category: 'WORK', created_at: within }),
      task({ id: '2', life_category: 'WORK', created_at: within }),
      task({ id: '3', life_category: 'SELF_CARE', created_at: within }),
      task({ id: '4', life_category: 'HEALTH', created_at: within }),
    ];
    const state = computeBalanceState(tasks, DEFAULT_BALANCE_CONFIG, { nowMs: NOW });
    expect(state.totalTracked).toBe(4);
    expect(state.currentRatios.WORK).toBeCloseTo(0.5);
    expect(state.currentRatios.SELF_CARE).toBeCloseTo(0.25);
    expect(state.currentRatios.HEALTH).toBeCloseTo(0.25);
    expect(state.dominantCategory).toBe('WORK');
  });

  it('flags overload when work exceeds target + threshold', () => {
    const within = new Date('2026-05-13T10:00:00Z').toISOString();
    const tasks = [
      task({ id: '1', life_category: 'WORK', created_at: within }),
      task({ id: '2', life_category: 'WORK', created_at: within }),
      task({ id: '3', life_category: 'WORK', created_at: within }),
      task({ id: '4', life_category: 'SELF_CARE', created_at: within }),
    ];
    const state = computeBalanceState(tasks, DEFAULT_BALANCE_CONFIG, { nowMs: NOW });
    // workTarget=0.40, threshold=0.10 → overload above 0.50. 3/4 = 0.75.
    expect(state.isOverloaded).toBe(true);
  });

  it('excludes tasks outside the 7-day window', () => {
    const long_ago = new Date('2026-04-01T10:00:00Z').toISOString();
    const tasks = [task({ life_category: 'WORK', created_at: long_ago })];
    const state = computeBalanceState(tasks, DEFAULT_BALANCE_CONFIG, { nowMs: NOW });
    expect(state.totalTracked).toBe(0);
  });

  it('uses completed_at when present', () => {
    const long_ago = new Date('2026-04-01T10:00:00Z').toISOString();
    const recent = new Date('2026-05-12T10:00:00Z').toISOString();
    const tasks = [
      task({ life_category: 'WORK', created_at: long_ago, completed_at: recent }),
    ];
    const state = computeBalanceState(tasks, DEFAULT_BALANCE_CONFIG, { nowMs: NOW });
    expect(state.totalTracked).toBe(1);
  });
});
