import type { Task } from '@/types/task';
import type { Project } from '@/types/project';
import type { Habit } from '@/types/habit';
import type { Tag } from '@/types/tag';

export type SearchResultType = 'task' | 'project' | 'habit' | 'note' | 'tag';

export type SearchTypeFilter = 'all' | SearchResultType;

export interface SearchResult {
  type: SearchResultType;
  id: string;
  title: string;
  /** Optional one-line snippet shown under the title (description, notes, etc.). */
  snippet?: string;
  /** Destination route opened when the user clicks the row. */
  href: string;
  /** Optional CSS color for the leading badge (mirrors the Tag/Project pill). */
  color?: string;
}

/**
 * Trim, lowercase, and collapse multi-space queries so the matcher
 * tolerates whitespace noise from voice dictation and copy-paste.
 */
export function normalizeQuery(query: string): string {
  return query.trim().toLowerCase().replace(/\s+/g, ' ');
}

function matches(value: string | null | undefined, needle: string): boolean {
  if (!value) return false;
  return value.toLowerCase().includes(needle);
}

/**
 * Pure client-side search across the data already synced into Zustand
 * stores. Mirrors the Android `SearchViewModel` shape (tasks + tags +
 * projects), and additionally surfaces habits and task notes per the
 * unit 23 web parity spec.
 *
 * Notes are not their own collection on either client — the spec's
 * "Notes" type is fulfilled by surfacing task `notes` matches as
 * `type: 'note'` rows so they show under the Notes chip while still
 * deep-linking back to the parent task.
 */
export function searchAll(
  query: string,
  data: {
    tasks: Task[];
    projects: Project[];
    habits: Habit[];
    tags: Tag[];
  },
  filter: SearchTypeFilter = 'all',
): SearchResult[] {
  const needle = normalizeQuery(query);
  if (!needle) return [];

  const results: SearchResult[] = [];

  if (filter === 'all' || filter === 'task') {
    for (const task of data.tasks) {
      if (
        matches(task.title, needle) ||
        matches(task.description, needle)
      ) {
        results.push({
          type: 'task',
          id: task.id,
          title: task.title,
          snippet: task.description ?? undefined,
          href: `/tasks/${task.id}`,
        });
      }
    }
  }

  if (filter === 'all' || filter === 'project') {
    for (const project of data.projects) {
      if (
        matches(project.title, needle) ||
        matches(project.description, needle)
      ) {
        results.push({
          type: 'project',
          id: project.id,
          title: project.title,
          snippet: project.description ?? undefined,
          href: `/projects/${project.id}`,
          color: project.color,
        });
      }
    }
  }

  if (filter === 'all' || filter === 'habit') {
    for (const habit of data.habits) {
      if (matches(habit.name, needle) || matches(habit.description, needle)) {
        results.push({
          type: 'habit',
          id: habit.id,
          title: habit.name,
          snippet: habit.description ?? undefined,
          href: `/habits`,
        });
      }
    }
  }

  if (filter === 'all' || filter === 'note') {
    for (const task of data.tasks) {
      if (matches(task.notes, needle)) {
        results.push({
          type: 'note',
          id: `note-${task.id}`,
          title: task.title,
          snippet: task.notes ?? undefined,
          href: `/tasks/${task.id}`,
        });
      }
    }
  }

  if (filter === 'all' || filter === 'tag') {
    for (const tag of data.tags) {
      if (tag.archived) continue;
      if (matches(tag.name, needle)) {
        results.push({
          type: 'tag',
          id: tag.id,
          title: tag.name,
          href: '/tags',
          color: tag.color ?? undefined,
        });
      }
    }
  }

  return results;
}

/**
 * Bucket results by type for the section-grouped UI on
 * `SearchScreen.tsx`. Returns the sections in the canonical order so
 * the screen header order stays deterministic regardless of which
 * result types matched first.
 */
export function groupResults(
  results: SearchResult[],
): { type: SearchResultType; results: SearchResult[] }[] {
  const order: SearchResultType[] = ['task', 'project', 'habit', 'note', 'tag'];
  const buckets = new Map<SearchResultType, SearchResult[]>();
  for (const type of order) buckets.set(type, []);
  for (const r of results) buckets.get(r.type)?.push(r);
  return order
    .map((type) => ({ type, results: buckets.get(type) ?? [] }))
    .filter((section) => section.results.length > 0);
}

export const SEARCH_TYPE_LABELS: Record<SearchResultType, string> = {
  task: 'Tasks',
  project: 'Projects',
  habit: 'Habits',
  note: 'Notes',
  tag: 'Tags',
};
