import { describe, it, expect } from 'vitest';
import {
  nonArchivedProjects,
  pickerProjects,
} from '@/utils/projectFilters';
import type { Project } from '@/types/project';

function p(
  id: string,
  status: Project['status'] = 'active',
): Pick<Project, 'id' | 'status'> {
  return { id, status };
}

describe('nonArchivedProjects', () => {
  it('drops archived rows', () => {
    const list = [p('a'), p('b', 'archived'), p('c', 'completed')];
    expect(nonArchivedProjects(list).map((x) => x.id)).toEqual(['a', 'c']);
  });

  it('returns an empty array when every project is archived', () => {
    expect(
      nonArchivedProjects([p('x', 'archived'), p('y', 'archived')]),
    ).toEqual([]);
  });

  it('does not mutate the input', () => {
    const list = [p('a'), p('b', 'archived')];
    const snapshot = JSON.stringify(list);
    nonArchivedProjects(list);
    expect(JSON.stringify(list)).toBe(snapshot);
  });
});

describe('pickerProjects', () => {
  it('hides archived projects by default (no current id)', () => {
    const list = [p('a'), p('b', 'archived')];
    expect(pickerProjects(list, null).map((x) => x.id)).toEqual(['a']);
    expect(pickerProjects(list, undefined).map((x) => x.id)).toEqual(['a']);
    expect(pickerProjects(list, '').map((x) => x.id)).toEqual(['a']);
  });

  it('preserves the currently-selected archived project', () => {
    const list = [p('a'), p('b', 'archived'), p('c', 'archived')];
    expect(pickerProjects(list, 'b').map((x) => x.id)).toEqual(['a', 'b']);
  });

  it('passes through when no project is archived', () => {
    const list = [p('a'), p('b'), p('c', 'completed')];
    expect(pickerProjects(list, 'a').map((x) => x.id)).toEqual([
      'a',
      'b',
      'c',
    ]);
  });

  it('does not duplicate the current project when it is not archived', () => {
    const list = [p('a'), p('b')];
    expect(pickerProjects(list, 'a').map((x) => x.id)).toEqual(['a', 'b']);
  });
});
