import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import type { Habit, HabitCompletion } from '@/types/habit';
import { resetIdbForTesting } from '@/lib/idb/database';
import {
  RECONCILER_FLAG_V1,
  clearFlagsForUser,
  isFlagSet,
} from '@/lib/builtInSyncPreferences';
import {
  planBuiltInDedup,
  reconcileBuiltInHabits,
} from '@/lib/builtInHabitReconciler';

const deleteHabit = vi.fn();
const reassignCompletions = vi.fn();

vi.mock('@/api/firestore/habits', () => ({
  deleteHabit: (...args: unknown[]) => deleteHabit(...args),
  reassignCompletions: (...args: unknown[]) => reassignCompletions(...args),
}));

function mkHabit(overrides: Partial<Habit>): Habit {
  return {
    id: 'h_default',
    user_id: 'user-a',
    name: 'Default',
    description: null,
    icon: null,
    color: null,
    category: null,
    frequency: 'daily',
    target_count: 1,
    active_days_json: null,
    is_active: true,
    created_at: '2026-05-01T00:00:00Z',
    updated_at: '2026-05-01T00:00:00Z',
    is_built_in: true,
    template_key: 'meditation',
    source_version: 1,
    is_user_modified: false,
    is_detached_from_template: false,
    ...overrides,
  };
}

function mkCompletion(habitId: string, date: string): HabitCompletion {
  return {
    id: `${habitId}__${date}`,
    habit_id: habitId,
    date,
    count: 1,
    created_at: `${date}T00:00:00Z`,
  };
}

describe('planBuiltInDedup — pure merge plan', () => {
  it('returns empty when there are no built-in habits', () => {
    const habits = [mkHabit({ id: 'h1', is_built_in: false })];
    expect(planBuiltInDedup(habits, {})).toEqual([]);
  });

  it('no-ops when each templateKey appears once', () => {
    const habits = [
      mkHabit({ id: 'h1', template_key: 'meditation' }),
      mkHabit({ id: 'h2', template_key: 'water' }),
    ];
    expect(planBuiltInDedup(habits, {})).toEqual([]);
  });

  it('picks the habit with the most completions as the keeper', () => {
    const habits = [
      mkHabit({ id: 'h_old', template_key: 'meditation' }),
      mkHabit({ id: 'h_new', template_key: 'meditation' }),
    ];
    const completions = {
      h_old: [mkCompletion('h_old', '2026-05-10')],
      h_new: [
        mkCompletion('h_new', '2026-05-10'),
        mkCompletion('h_new', '2026-05-11'),
      ],
    };
    const plan = planBuiltInDedup(habits, completions);
    expect(plan).toHaveLength(1);
    expect(plan[0].keeperId).toBe('h_new');
    expect(plan[0].loserIds).toEqual(['h_old']);
  });

  it('breaks ties on completion count by earliest created_at', () => {
    const habits = [
      mkHabit({
        id: 'h_older',
        template_key: 'meditation',
        created_at: '2026-04-01T00:00:00Z',
      }),
      mkHabit({
        id: 'h_newer',
        template_key: 'meditation',
        created_at: '2026-05-01T00:00:00Z',
      }),
    ];
    const plan = planBuiltInDedup(habits, {});
    expect(plan).toHaveLength(1);
    expect(plan[0].keeperId).toBe('h_older');
    expect(plan[0].loserIds).toEqual(['h_newer']);
  });

  it('falls back to name when templateKey is missing on legacy rows', () => {
    const habits = [
      mkHabit({ id: 'h1', name: 'Drink Water', template_key: null }),
      mkHabit({ id: 'h2', name: 'Drink Water', template_key: null }),
    ];
    const plan = planBuiltInDedup(habits, {});
    expect(plan).toHaveLength(1);
    expect(plan[0].groupKey).toBe('Drink Water');
  });

  it('handles multiple groups independently', () => {
    const habits = [
      mkHabit({ id: 'm1', template_key: 'meditation' }),
      mkHabit({ id: 'm2', template_key: 'meditation' }),
      mkHabit({ id: 'w1', template_key: 'water' }),
      mkHabit({ id: 'w2', template_key: 'water' }),
    ];
    const plan = planBuiltInDedup(habits, {});
    expect(plan).toHaveLength(2);
  });

  it('ignores non-built-in habits even when sharing a template_key', () => {
    const habits = [
      mkHabit({ id: 'h1', template_key: 'meditation', is_built_in: true }),
      mkHabit({ id: 'h2', template_key: 'meditation', is_built_in: false }),
    ];
    expect(planBuiltInDedup(habits, {})).toEqual([]);
  });
});

describe('reconcileBuiltInHabits — side-effecting wrapper', () => {
  beforeEach(() => {
    deleteHabit.mockReset();
    reassignCompletions.mockReset();
  });

  afterEach(async () => {
    await clearFlagsForUser('user-a');
    resetIdbForTesting();
  });

  it('no-ops without firing the flag when uid is empty', async () => {
    await reconcileBuiltInHabits('', [], {});
    expect(deleteHabit).not.toHaveBeenCalled();
    expect(reassignCompletions).not.toHaveBeenCalled();
  });

  it('reassigns completions then deletes the loser habit', async () => {
    const habits = [
      mkHabit({ id: 'h_old', template_key: 'meditation' }),
      mkHabit({
        id: 'h_new',
        template_key: 'meditation',
        created_at: '2026-04-01T00:00:00Z',
      }),
    ];
    const completions = {
      h_old: [mkCompletion('h_old', '2026-05-10')],
      h_new: [],
    };
    await reconcileBuiltInHabits('user-a', habits, completions);

    // Keeper (more completions) is h_old → drop h_new.
    expect(reassignCompletions).toHaveBeenCalledTimes(1);
    expect(reassignCompletions).toHaveBeenCalledWith('user-a', 'h_new', 'h_old');
    expect(deleteHabit).toHaveBeenCalledWith('user-a', 'h_new');
  });

  it('no-ops on the second call (one-shot flag set)', async () => {
    const habits = [
      mkHabit({ id: 'h1', template_key: 'meditation' }),
      mkHabit({ id: 'h2', template_key: 'meditation' }),
    ];
    await reconcileBuiltInHabits('user-a', habits, {});
    deleteHabit.mockClear();
    reassignCompletions.mockClear();

    await reconcileBuiltInHabits('user-a', habits, {});
    expect(deleteHabit).not.toHaveBeenCalled();
    expect(reassignCompletions).not.toHaveBeenCalled();
  });

  it('persists the repair flag after a successful sweep', async () => {
    const habits = [
      mkHabit({ id: 'h1', template_key: 'meditation' }),
      mkHabit({ id: 'h2', template_key: 'meditation' }),
    ];
    await reconcileBuiltInHabits('user-a', habits, {});
    expect(await isFlagSet('user-a', RECONCILER_FLAG_V1)).toBe(true);
  });

  it('flips the flag even on a no-duplicate sweep', async () => {
    const habits = [mkHabit({ id: 'h1', template_key: 'meditation' })];
    await reconcileBuiltInHabits('user-a', habits, {});
    expect(deleteHabit).not.toHaveBeenCalled();
    expect(await isFlagSet('user-a', RECONCILER_FLAG_V1)).toBe(true);
  });

  it('force=true bypasses the flag', async () => {
    await reconcileBuiltInHabits(
      'user-a',
      [
        mkHabit({ id: 'h1', template_key: 'meditation' }),
        mkHabit({ id: 'h2', template_key: 'meditation' }),
      ],
      {},
    );
    deleteHabit.mockClear();
    reassignCompletions.mockClear();

    await reconcileBuiltInHabits(
      'user-a',
      [
        mkHabit({ id: 'h3', template_key: 'water' }),
        mkHabit({ id: 'h4', template_key: 'water' }),
      ],
      {},
      { force: true },
    );
    expect(deleteHabit).toHaveBeenCalledTimes(1);
  });
});
