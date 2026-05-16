import { describe, it, expect } from 'vitest';
import {
  groupResults,
  normalizeQuery,
  searchAll,
  type SearchTypeFilter,
} from '../searchFilters';
import type { Task } from '@/types/task';
import type { Project } from '@/types/project';
import type { Habit } from '@/types/habit';
import type { Tag } from '@/types/tag';

function makeTask(overrides: Partial<Task>): Task {
  return {
    id: overrides.id ?? 't1',
    project_id: 'p1',
    user_id: 'u1',
    parent_id: null,
    title: overrides.title ?? 'Test task',
    description: overrides.description ?? null,
    notes: overrides.notes ?? null,
    status: 'todo',
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
    created_at: '2026-05-15T00:00:00.000Z',
    updated_at: '2026-05-15T00:00:00.000Z',
    ...overrides,
  } as Task;
}

function makeProject(overrides: Partial<Project>): Project {
  return {
    id: overrides.id ?? 'p1',
    goal_id: 'g1',
    user_id: 'u1',
    title: overrides.title ?? 'Test project',
    description: overrides.description ?? null,
    status: 'active',
    due_date: null,
    color: '#4A90D9',
    icon: 'folder',
    sort_order: 0,
    created_at: '2026-05-15T00:00:00.000Z',
    updated_at: '2026-05-15T00:00:00.000Z',
    ...overrides,
  } as Project;
}

function makeHabit(overrides: Partial<Habit>): Habit {
  return {
    id: overrides.id ?? 'h1',
    user_id: 'u1',
    name: overrides.name ?? 'Test habit',
    description: overrides.description ?? null,
    icon: null,
    color: null,
    category: null,
    frequency: 'daily',
    target_count: 1,
    active_days_json: null,
    is_active: true,
    created_at: '2026-05-15T00:00:00.000Z',
    updated_at: '2026-05-15T00:00:00.000Z',
    ...overrides,
  } as Habit;
}

function makeTag(overrides: Partial<Tag>): Tag {
  return {
    id: overrides.id ?? 'tag1',
    user_id: 'u1',
    name: overrides.name ?? 'urgent',
    color: '#E86F3C',
    sort_order: 0,
    archived: false,
    created_at: '2026-05-15T00:00:00.000Z',
    ...overrides,
  } as Tag;
}

describe('normalizeQuery', () => {
  it('trims, lowercases, and collapses whitespace', () => {
    expect(normalizeQuery('  Hello   World  ')).toBe('hello world');
  });

  it('returns empty string for whitespace-only input', () => {
    expect(normalizeQuery('   ')).toBe('');
  });
});

describe('searchAll', () => {
  const fixtures = {
    tasks: [
      makeTask({ id: 't1', title: 'Write Report', description: 'Quarterly summary' }),
      makeTask({ id: 't2', title: 'Call dentist', notes: 'About the report findings' }),
      makeTask({ id: 't3', title: 'Buy groceries' }),
    ],
    projects: [
      makeProject({ id: 'p1', title: 'Annual Report', description: 'finance' }),
      makeProject({ id: 'p2', title: 'Garden Cleanup' }),
    ],
    habits: [
      makeHabit({ id: 'h1', name: 'Meditate', description: 'Morning ritual' }),
      makeHabit({ id: 'h2', name: 'Read 30m' }),
    ],
    tags: [
      makeTag({ id: 'tag1', name: 'urgent' }),
      makeTag({ id: 'tag2', name: 'report-related' }),
      makeTag({ id: 'tag3', name: 'archived-tag', archived: true }),
    ],
  };

  it('returns no results for empty query', () => {
    expect(searchAll('', fixtures)).toEqual([]);
    expect(searchAll('   ', fixtures)).toEqual([]);
  });

  it('matches across tasks/projects/habits/notes/tags by default', () => {
    const results = searchAll('report', fixtures);
    const types = new Set(results.map((r) => r.type));
    expect(types).toContain('task');
    expect(types).toContain('project');
    expect(types).toContain('note');
    expect(types).toContain('tag');
  });

  it('respects the type filter', () => {
    const tasks = searchAll('report', fixtures, 'task');
    expect(tasks.every((r) => r.type === 'task')).toBe(true);
    expect(tasks.length).toBeGreaterThan(0);

    const projects = searchAll('report', fixtures, 'project');
    expect(projects.every((r) => r.type === 'project')).toBe(true);
  });

  it('hides archived tags from results', () => {
    const tagResults = searchAll('archived', fixtures, 'tag');
    expect(tagResults).toHaveLength(0);
  });

  it('is case-insensitive and whitespace-tolerant', () => {
    const a = searchAll('  REPORT ', fixtures, 'task');
    const b = searchAll('report', fixtures, 'task');
    expect(a.map((r) => r.id)).toEqual(b.map((r) => r.id));
  });

  it('matches task description for tasks', () => {
    const results = searchAll('quarterly', fixtures, 'task');
    expect(results.map((r) => r.id)).toContain('t1');
  });

  it('surfaces task notes as `note` results, not `task` results', () => {
    const all = searchAll('findings', fixtures);
    const noteHit = all.find((r) => r.type === 'note');
    expect(noteHit).toBeDefined();
    expect(noteHit?.title).toBe('Call dentist');
  });

  it('does not match across unrelated text', () => {
    const results = searchAll('xyzzy', fixtures);
    expect(results).toHaveLength(0);
  });

  it('honours each individual filter', () => {
    const filters: SearchTypeFilter[] = ['task', 'project', 'habit', 'note', 'tag'];
    for (const f of filters) {
      const out = searchAll('report', fixtures, f);
      for (const r of out) expect(r.type).toBe(f);
    }
  });

  it('points tag results at /tags', () => {
    const out = searchAll('urgent', fixtures, 'tag');
    expect(out[0]?.href).toBe('/tags');
  });

  it('builds task hrefs from task ids', () => {
    const out = searchAll('groceries', fixtures, 'task');
    expect(out[0]?.href).toBe('/tasks/t3');
  });
});

describe('groupResults', () => {
  it('returns sections in canonical type order', () => {
    const fakeResults = [
      { type: 'tag' as const, id: '1', title: 't', href: '/tags' },
      { type: 'task' as const, id: '2', title: 'x', href: '/tasks/2' },
      { type: 'project' as const, id: '3', title: 'p', href: '/projects/3' },
    ];
    const grouped = groupResults(fakeResults);
    expect(grouped.map((g) => g.type)).toEqual(['task', 'project', 'tag']);
  });

  it('omits empty sections', () => {
    const grouped = groupResults([
      { type: 'task', id: '1', title: 'x', href: '/tasks/1' },
    ]);
    expect(grouped).toHaveLength(1);
    expect(grouped[0]?.type).toBe('task');
  });
});
