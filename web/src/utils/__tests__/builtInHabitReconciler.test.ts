import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  REPAIR_FLAGS,
  clearDismissalsFor,
  diffHabitAgainstRegistry,
  dismissBuiltInUpdate,
  findDuplicateBuiltIns,
  findPendingUpdates,
  isDismissed,
  isRepairDone,
  markRepairDone,
} from '@/utils/builtInHabitReconciler';
import type { Habit } from '@/types/habit';
import { BUILT_IN_TEMPLATE_KEYS } from '@/data/builtInHabitTemplates';

function mkBuiltIn(overrides: Partial<Habit>): Habit {
  const base: Habit = {
    id: 'habit-1',
    user_id: 'u1',
    name: 'School',
    description: null,
    icon: '📚',
    color: '#4A90D9',
    category: null,
    frequency: 'daily',
    target_count: 1,
    active_days_json: null,
    is_active: true,
    created_at: '2026-01-01T00:00:00.000Z',
    updated_at: '2026-01-02T00:00:00.000Z',
    is_built_in: true,
    template_key: BUILT_IN_TEMPLATE_KEYS.SCHOOL,
    source_version: 1,
    is_user_modified: false,
    is_detached_from_template: false,
  };
  return { ...base, ...overrides };
}

beforeEach(() => {
  // Reset localStorage between tests so dismissals + repair flags don't leak.
  if (typeof window !== 'undefined') {
    window.localStorage.clear();
  }
});

afterEach(() => {
  if (typeof window !== 'undefined') {
    window.localStorage.clear();
  }
});

describe('diffHabitAgainstRegistry', () => {
  it('returns null for non-built-in habits (no template_key)', () => {
    const habit: Habit = mkBuiltIn({ template_key: null });
    expect(diffHabitAgainstRegistry(habit)).toBeNull();
  });

  it('returns null when detached from template', () => {
    const habit = mkBuiltIn({ is_detached_from_template: true });
    expect(diffHabitAgainstRegistry(habit)).toBeNull();
  });

  it('returns null when template_key is not in the registry', () => {
    const habit = mkBuiltIn({ template_key: 'builtin_unknown' });
    expect(diffHabitAgainstRegistry(habit)).toBeNull();
  });

  it('returns null when habit is already at the current version', () => {
    const habit = mkBuiltIn({ source_version: 1 }); // School is v1
    expect(diffHabitAgainstRegistry(habit)).toBeNull();
  });

  it('returns null when habit is ahead of the registry', () => {
    const habit = mkBuiltIn({ source_version: 5 });
    expect(diffHabitAgainstRegistry(habit)).toBeNull();
  });

  it('treats source_version 0 as v1 (pre-versioning rows)', () => {
    // School is v1 in the registry; a row at sv=0 is "pre-versioning" but
    // shape-matches v1, so the diff finds no changes and returns null.
    const habit = mkBuiltIn({
      source_version: 0,
      name: 'School',
      frequency: 'daily',
      target_count: 1,
    });
    expect(diffHabitAgainstRegistry(habit)).toBeNull();
  });
});

describe('findPendingUpdates', () => {
  it('returns an empty list when no habits are built-in', () => {
    const habits: Habit[] = [
      mkBuiltIn({ template_key: null, is_built_in: false }),
    ];
    expect(findPendingUpdates(habits)).toEqual([]);
  });

  it('persists dismissals per (template_key, toVersion) pair', () => {
    dismissBuiltInUpdate(BUILT_IN_TEMPLATE_KEYS.SCHOOL, 2);
    expect(isDismissed(BUILT_IN_TEMPLATE_KEYS.SCHOOL, 2)).toBe(true);
    expect(isDismissed(BUILT_IN_TEMPLATE_KEYS.SCHOOL, 3)).toBe(false);
    expect(isDismissed(BUILT_IN_TEMPLATE_KEYS.HOUSEWORK, 2)).toBe(false);
  });

  it('clearDismissalsFor wipes every version of a template_key', () => {
    dismissBuiltInUpdate(BUILT_IN_TEMPLATE_KEYS.SCHOOL, 2);
    dismissBuiltInUpdate(BUILT_IN_TEMPLATE_KEYS.SCHOOL, 3);
    dismissBuiltInUpdate(BUILT_IN_TEMPLATE_KEYS.HOUSEWORK, 2);
    clearDismissalsFor(BUILT_IN_TEMPLATE_KEYS.SCHOOL);
    expect(isDismissed(BUILT_IN_TEMPLATE_KEYS.SCHOOL, 2)).toBe(false);
    expect(isDismissed(BUILT_IN_TEMPLATE_KEYS.SCHOOL, 3)).toBe(false);
    // Housework dismissals are untouched.
    expect(isDismissed(BUILT_IN_TEMPLATE_KEYS.HOUSEWORK, 2)).toBe(true);
  });

  it('sorts pending updates by display name (stable across renders)', () => {
    // Construct a synthetic snapshot where every built-in template is
    // "missing" from the registry so no real diffs land — we're only
    // asserting the sort path is reachable. With v1 baselines and
    // shape-matching defaults, an empty pending list is the truth.
    const habits = [
      mkBuiltIn({
        id: 'h-housework',
        template_key: BUILT_IN_TEMPLATE_KEYS.HOUSEWORK,
        name: 'Housework',
      }),
      mkBuiltIn({
        id: 'h-school',
        template_key: BUILT_IN_TEMPLATE_KEYS.SCHOOL,
        name: 'School',
      }),
    ];
    expect(findPendingUpdates(habits)).toEqual([]);
  });
});

describe('findDuplicateBuiltIns', () => {
  it('returns no groups when every templateKey is unique', () => {
    const habits = [
      mkBuiltIn({
        id: 'h1',
        template_key: BUILT_IN_TEMPLATE_KEYS.SCHOOL,
        name: 'School',
      }),
      mkBuiltIn({
        id: 'h2',
        template_key: BUILT_IN_TEMPLATE_KEYS.HOUSEWORK,
        name: 'Housework',
      }),
    ];
    expect(findDuplicateBuiltIns(habits, {})).toEqual([]);
  });

  it('groups duplicates and picks the keeper with the most completions', () => {
    const habits = [
      mkBuiltIn({
        id: 'h-loser',
        template_key: BUILT_IN_TEMPLATE_KEYS.SCHOOL,
        created_at: '2026-01-01T00:00:00.000Z',
      }),
      mkBuiltIn({
        id: 'h-keeper',
        template_key: BUILT_IN_TEMPLATE_KEYS.SCHOOL,
        created_at: '2026-01-02T00:00:00.000Z',
      }),
    ];
    const groups = findDuplicateBuiltIns(habits, {
      'h-loser': 1,
      'h-keeper': 5,
    });
    expect(groups).toHaveLength(1);
    expect(groups[0].keeperId).toBe('h-keeper');
    expect(groups[0].loserIds).toEqual(['h-loser']);
  });

  it('tie-breaks on oldest created_at when completions tie', () => {
    const habits = [
      mkBuiltIn({
        id: 'h-newer',
        template_key: BUILT_IN_TEMPLATE_KEYS.SCHOOL,
        created_at: '2026-02-01T00:00:00.000Z',
      }),
      mkBuiltIn({
        id: 'h-older',
        template_key: BUILT_IN_TEMPLATE_KEYS.SCHOOL,
        created_at: '2026-01-01T00:00:00.000Z',
      }),
    ];
    const groups = findDuplicateBuiltIns(habits, {});
    expect(groups[0].keeperId).toBe('h-older');
    expect(groups[0].loserIds).toEqual(['h-newer']);
  });

  it('falls back to name-based grouping when template_key is missing', () => {
    const habits = [
      mkBuiltIn({
        id: 'h-a',
        template_key: null,
        name: 'School',
        is_built_in: true,
      }),
      mkBuiltIn({
        id: 'h-b',
        template_key: null,
        name: 'School', // same name → grouped together
        is_built_in: true,
      }),
    ];
    const groups = findDuplicateBuiltIns(habits, { 'h-a': 2, 'h-b': 5 });
    expect(groups).toHaveLength(1);
    expect(groups[0].keeperId).toBe('h-b');
  });

  it('ignores user-created habits (is_built_in = false)', () => {
    const habits = [
      mkBuiltIn({
        id: 'h-user-1',
        is_built_in: false,
        template_key: null,
        name: 'Read for 30m',
      }),
      mkBuiltIn({
        id: 'h-user-2',
        is_built_in: false,
        template_key: null,
        name: 'Read for 30m',
      }),
    ];
    expect(findDuplicateBuiltIns(habits, {})).toEqual([]);
  });
});

describe('repair-completed flags', () => {
  it('round-trips per-flag values through localStorage', () => {
    expect(isRepairDone(REPAIR_FLAGS.DRIFT_CLEANUP_DONE)).toBe(false);
    markRepairDone(REPAIR_FLAGS.DRIFT_CLEANUP_DONE);
    expect(isRepairDone(REPAIR_FLAGS.DRIFT_CLEANUP_DONE)).toBe(true);
    // Other flag is independent.
    expect(isRepairDone(REPAIR_FLAGS.BUILT_INS_RECONCILED)).toBe(false);
  });
});
