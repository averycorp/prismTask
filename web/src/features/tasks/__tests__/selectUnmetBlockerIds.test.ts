import { describe, it, expect } from 'vitest';
import {
  selectUnmetBlockerIds,
  buildUnmetBlockerCountMap,
} from '@/features/tasks/dependencyHelpers';
import type { Task } from '@/types/task';
import type { TaskDependency } from '@/types/taskDependency';

function task(id: string, status: Task['status'] = 'todo'): Task {
  return {
    id,
    project_id: 'p',
    user_id: 'u',
    parent_id: null,
    title: id,
    description: null,
    notes: null,
    status,
    priority: 3,
    due_date: null,
    due_time: null,
    planned_date: null,
    completed_at: null,
    urgency_score: 0,
    recurrence_json: null,
    eisenhower_quadrant: null,
    eisenhower_updated_at: null,
    estimated_duration: null,
    actual_duration: null,
    sort_order: 0,
    depth: 0,
    created_at: '',
    updated_at: '',
  };
}

function dep(id: string, blocker: string, blocked: string): TaskDependency {
  return {
    id,
    blocker_task_id: blocker,
    blocked_task_id: blocked,
    created_at: 0,
  };
}

describe('selectUnmetBlockerIds', () => {
  it('returns blocker ids when blocker is not done', () => {
    const tasks = [task('a'), task('b')];
    const deps = [dep('d1', 'a', 'b')];
    expect(selectUnmetBlockerIds(deps, tasks, 'b')).toEqual(['a']);
  });

  it('omits blockers that are done', () => {
    const tasks = [task('a', 'done'), task('b')];
    const deps = [dep('d1', 'a', 'b')];
    expect(selectUnmetBlockerIds(deps, tasks, 'b')).toEqual([]);
  });

  it('omits blockers that are cancelled', () => {
    const tasks = [task('a', 'cancelled'), task('b')];
    const deps = [dep('d1', 'a', 'b')];
    expect(selectUnmetBlockerIds(deps, tasks, 'b')).toEqual([]);
  });

  it('fails open (omits) when blocker task is missing from the task list', () => {
    // Unknown blocker (e.g. just-deleted task) — fail-open so a stale
    // dep doesn't permanently mute a card.
    const tasks = [task('b')];
    const deps = [dep('d1', 'missing', 'b')];
    expect(selectUnmetBlockerIds(deps, tasks, 'b')).toEqual([]);
  });

  it('counts multiple unmet blockers', () => {
    const tasks = [task('a'), task('b'), task('c'), task('d')];
    const deps = [dep('d1', 'a', 'd'), dep('d2', 'b', 'd'), dep('d3', 'c', 'd')];
    expect(selectUnmetBlockerIds(deps, tasks, 'd').sort()).toEqual([
      'a',
      'b',
      'c',
    ]);
  });

  it('returns empty array for a task with no blockers', () => {
    const tasks = [task('a'), task('b')];
    const deps: TaskDependency[] = [];
    expect(selectUnmetBlockerIds(deps, tasks, 'a')).toEqual([]);
  });

  it('ignores edges where the queried task is the blocker side', () => {
    // `a` blocks `b` — querying for `a` (the blocker) should return [].
    const tasks = [task('a'), task('b')];
    const deps = [dep('d1', 'a', 'b')];
    expect(selectUnmetBlockerIds(deps, tasks, 'a')).toEqual([]);
  });
});

describe('buildUnmetBlockerCountMap', () => {
  it('counts unmet blockers per blocked task', () => {
    const tasks = [
      task('a'),
      task('b', 'done'),
      task('c'),
      task('d'),
    ];
    const deps = [
      dep('d1', 'a', 'd'),
      dep('d2', 'b', 'd'), // b is done — should not count
      dep('d3', 'c', 'd'),
      dep('d4', 'a', 'c'),
    ];
    const counts = buildUnmetBlockerCountMap(deps, tasks);
    expect(counts.get('d')).toBe(2);
    expect(counts.get('c')).toBe(1);
    expect(counts.has('a')).toBe(false);
  });

  it('omits blocked tasks whose only blockers are met', () => {
    const tasks = [task('a', 'done'), task('b')];
    const deps = [dep('d1', 'a', 'b')];
    const counts = buildUnmetBlockerCountMap(deps, tasks);
    expect(counts.has('b')).toBe(false);
  });

  it('skips edges with unknown blockers (fail-open)', () => {
    const tasks = [task('b')];
    const deps = [dep('d1', 'missing', 'b')];
    const counts = buildUnmetBlockerCountMap(deps, tasks);
    expect(counts.has('b')).toBe(false);
  });
});
