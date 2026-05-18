import { describe, it, expect } from 'vitest';
import {
  IMPORT_PREVIEW_TASK_LIMIT,
  validateImportFile,
} from '@/utils/import';

describe('validateImportFile — extended preview shape', () => {
  it('returns null for non-object input', () => {
    expect(validateImportFile(null)).toBeNull();
    expect(validateImportFile(undefined)).toBeNull();
    expect(validateImportFile('not an object')).toBeNull();
    expect(validateImportFile(42)).toBeNull();
  });

  it('returns null when version is missing or non-numeric', () => {
    expect(validateImportFile({})).toBeNull();
    expect(validateImportFile({ version: '1' })).toBeNull();
    expect(validateImportFile({ version: 0 })).toBeNull();
  });

  it('parses a legacy backup with title-only tasks and no metadata', () => {
    const data = {
      version: 1,
      tasks: [
        { title: 'Buy groceries' },
        { title: 'Walk the dog' },
      ],
    };

    const preview = validateImportFile(data);
    expect(preview).not.toBeNull();
    expect(preview!.taskCount).toBe(2);
    expect(preview!.tasks).toHaveLength(2);
    expect(preview!.tasks[0]).toEqual({
      title: 'Buy groceries',
      dueDate: null,
      priority: null,
      tags: [],
      subtasks: [],
    });
  });

  it('extracts per-task field-level detail (due date, priority, tags)', () => {
    const data = {
      version: 1,
      tasks: [
        {
          id: 1,
          title: 'Ship Q2 audit',
          due_date: '2026-06-01',
          priority: 1,
          tags: ['work', 'urgent'],
        },
      ],
    };

    const preview = validateImportFile(data);
    expect(preview!.tasks[0]).toMatchObject({
      title: 'Ship Q2 audit',
      dueDate: '2026-06-01',
      priority: 1,
      tags: ['work', 'urgent'],
    });
  });

  it('nests children under parent via parent_id (flat backend export shape)', () => {
    const data = {
      version: 1,
      tasks: [
        { id: 1, parent_id: null, title: 'Project A' },
        { id: 2, parent_id: 1, title: 'Subtask A.1' },
        { id: 3, parent_id: 1, title: 'Subtask A.2' },
        { id: 4, parent_id: null, title: 'Project B' },
      ],
    };

    const preview = validateImportFile(data);
    expect(preview!.taskCount).toBe(4); // raw count from JSON
    expect(preview!.tasks).toHaveLength(2); // two top-level parents
    expect(preview!.tasks[0].title).toBe('Project A');
    expect(preview!.tasks[0].subtasks.map((s) => s.title)).toEqual([
      'Subtask A.1',
      'Subtask A.2',
    ]);
    expect(preview!.tasks[1].subtasks).toHaveLength(0);
  });

  it('honours nested subtasks[] when supplied inline', () => {
    const data = {
      version: 1,
      tasks: [
        {
          title: 'Parent',
          subtasks: [
            { title: 'Child', priority: 2, due_date: '2026-07-04' },
          ],
        },
      ],
    };

    const preview = validateImportFile(data);
    expect(preview!.tasks[0].subtasks).toHaveLength(1);
    expect(preview!.tasks[0].subtasks[0]).toMatchObject({
      title: 'Child',
      priority: 2,
      dueDate: '2026-07-04',
    });
  });

  it('caps top-level preview rows at IMPORT_PREVIEW_TASK_LIMIT', () => {
    const tasks = Array.from({ length: IMPORT_PREVIEW_TASK_LIMIT + 5 }, (_, i) => ({
      id: i + 1,
      parent_id: null,
      title: `Task ${i + 1}`,
    }));
    const preview = validateImportFile({ version: 1, tasks });
    expect(preview!.taskCount).toBe(IMPORT_PREVIEW_TASK_LIMIT + 5);
    expect(preview!.tasks).toHaveLength(IMPORT_PREVIEW_TASK_LIMIT);
  });

  it('drops malformed tag values defensively', () => {
    const data = {
      version: 1,
      tasks: [
        {
          title: 'Mixed tags',
          tags: ['work', 42, null, '', 'home'],
        },
      ],
    };
    const preview = validateImportFile(data);
    expect(preview!.tasks[0].tags).toEqual(['work', 'home']);
  });

  it('returns empty tasks[] when no tasks array present', () => {
    const preview = validateImportFile({ version: 1, projects: [] });
    expect(preview!.taskCount).toBe(0);
    expect(preview!.tasks).toEqual([]);
  });

  it('preserves existing collection counts', () => {
    const data = {
      version: 2,
      goals: [{}, {}],
      projects: [{}],
      tasks: [{ title: 't' }],
      tags: [{}, {}, {}],
      habits: [{}, {}],
      templates: [{}],
    };
    const preview = validateImportFile(data);
    expect(preview).toMatchObject({
      version: 2,
      goalCount: 2,
      projectCount: 1,
      taskCount: 1,
      tagCount: 3,
      habitCount: 2,
      templateCount: 1,
    });
  });
});
