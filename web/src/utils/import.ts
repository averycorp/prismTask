import { exportApi } from '@/api/export';

/**
 * Maximum number of tasks displayed in the import preview list. Extra
 * tasks are rolled up into an "and N more …" footer in the UI.
 */
export const IMPORT_PREVIEW_TASK_LIMIT = 10;

/**
 * Per-task display projection for the import preview screen. Mirrors
 * Android's `TaskPreviewRow` (`domain/usecase/ProjectImporter.kt`) — title,
 * due date, priority, tags, and the nested subtask tree, so users can
 * verify field-level detail before committing the import.
 *
 * All non-title fields are optional / defaulted so the parser stays
 * backward-compatible with legacy backups that only carried titles.
 */
export interface PreviewTask {
  title: string;
  dueDate: string | null;
  priority: number | null;
  tags: string[];
  subtasks: PreviewTask[];
}

export interface ImportPreview {
  version: number;
  goalCount: number;
  projectCount: number;
  taskCount: number;
  tagCount: number;
  habitCount: number;
  templateCount: number;
  /**
   * Up to {@link IMPORT_PREVIEW_TASK_LIMIT} top-level tasks for field-level
   * preview. The UI shows "and N more …" when `taskCount > tasks.length`.
   * Subtasks are nested under their parent rather than counted separately.
   */
  tasks: PreviewTask[];
}

interface RawTask {
  id?: number | string | null;
  parent_id?: number | string | null;
  title?: unknown;
  due_date?: unknown;
  priority?: unknown;
  tags?: unknown;
  subtasks?: unknown;
  [key: string]: unknown;
}

function toPreviewTask(raw: RawTask, subtasks: PreviewTask[]): PreviewTask {
  const title = typeof raw.title === 'string' ? raw.title.trim() : '';
  const dueDate =
    typeof raw.due_date === 'string' && raw.due_date.length > 0
      ? raw.due_date
      : null;
  const priority =
    typeof raw.priority === 'number' && Number.isFinite(raw.priority)
      ? raw.priority
      : null;
  const tags = Array.isArray(raw.tags)
    ? raw.tags
        .map((t) => (typeof t === 'string' ? t : null))
        .filter((t): t is string => !!t && t.length > 0)
    : [];
  return {
    title,
    dueDate,
    priority,
    tags,
    subtasks,
  };
}

/**
 * Build the per-task preview list from a raw `tasks[]` array. Handles two
 * shapes:
 *  - Flat list with `parent_id` (backend `/export/json` shape) — children
 *    are grouped under their parent.
 *  - Nested list with `subtasks[]` already inlined (defensive, in case a
 *    future export pre-nests them).
 *
 * Only the first {@link IMPORT_PREVIEW_TASK_LIMIT} top-level tasks are
 * returned; the caller renders an "and N more …" footer using
 * `taskCount - tasks.length`.
 */
function buildTaskPreviews(rawTasks: unknown): PreviewTask[] {
  if (!Array.isArray(rawTasks)) return [];

  const tasks = rawTasks.filter(
    (t): t is RawTask => !!t && typeof t === 'object',
  );
  if (tasks.length === 0) return [];

  // Group children by parent_id when present.
  const byParent = new Map<string, RawTask[]>();
  const topLevel: RawTask[] = [];
  let usesParentId = false;
  for (const t of tasks) {
    const parentId = t.parent_id;
    if (parentId !== null && parentId !== undefined && parentId !== '') {
      usesParentId = true;
      const key = String(parentId);
      const bucket = byParent.get(key) ?? [];
      bucket.push(t);
      byParent.set(key, bucket);
    } else {
      topLevel.push(t);
    }
  }

  const roots = usesParentId ? topLevel : tasks;

  const buildOne = (raw: RawTask): PreviewTask => {
    // Prefer explicit nested `subtasks[]` when supplied; otherwise look up
    // children via parent_id grouping.
    let childRaws: RawTask[] = [];
    if (Array.isArray(raw.subtasks)) {
      childRaws = raw.subtasks.filter(
        (s): s is RawTask => !!s && typeof s === 'object',
      );
    } else if (
      raw.id !== null &&
      raw.id !== undefined &&
      raw.id !== '' &&
      byParent.has(String(raw.id))
    ) {
      childRaws = byParent.get(String(raw.id)) ?? [];
    }
    const subtasks = childRaws.map(buildOne);
    return toPreviewTask(raw, subtasks);
  };

  return roots.slice(0, IMPORT_PREVIEW_TASK_LIMIT).map(buildOne);
}

export function validateImportFile(data: unknown): ImportPreview | null {
  if (!data || typeof data !== 'object') return null;
  const obj = data as Record<string, unknown>;

  if (!obj.version || typeof obj.version !== 'number') return null;

  return {
    version: obj.version as number,
    goalCount: Array.isArray(obj.goals) ? obj.goals.length : 0,
    projectCount: Array.isArray(obj.projects) ? obj.projects.length : 0,
    taskCount: Array.isArray(obj.tasks) ? obj.tasks.length : 0,
    tagCount: Array.isArray(obj.tags) ? obj.tags.length : 0,
    habitCount: Array.isArray(obj.habits) ? obj.habits.length : 0,
    templateCount: Array.isArray(obj.templates) ? obj.templates.length : 0,
    tasks: buildTaskPreviews(obj.tasks),
  };
}

export async function parseImportFile(file: File): Promise<{ data: unknown; preview: ImportPreview }> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const data = JSON.parse(e.target?.result as string);
        const preview = validateImportFile(data);
        if (!preview) {
          reject(new Error('Invalid import file format. Missing "version" field.'));
          return;
        }
        resolve({ data, preview });
      } catch {
        reject(new Error('Failed to parse JSON file.'));
      }
    };
    reader.onerror = () => reject(new Error('Failed to read file.'));
    reader.readAsText(file);
  });
}

export async function importData(
  file: File,
  mode: 'merge' | 'replace',
  onProgress?: (step: string) => void,
): Promise<Record<string, number>> {
  onProgress?.('Uploading and importing data...');
  const result = await exportApi.importJson(file, mode);
  return result;
}
