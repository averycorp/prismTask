import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import type {
  TaskTemplate,
  TemplateCreate,
  TemplateUpdate,
  TemplateUseRequest,
  TemplateUseResponse,
} from '@/types/template';
import * as firestoreTemplates from '@/api/firestore/taskTemplates';
import * as firestoreTasks from '@/api/firestore/tasks';
import { getFirebaseUid } from '@/stores/firebaseUid';
import type { Task, TaskPriority } from '@/types/task';

/**
 * Live cache + write surface for task templates.
 *
 * Parity audit § B.10 (2026-05-15): the store now reads and writes
 * through Firestore (`users/{uid}/task_templates`) instead of the
 * deprecated FastAPI REST endpoints, putting web on the same
 * collection Android already uses. The public store API
 * (`fetch` / `create` / `update` / `remove` / `use`) is preserved
 * byte-for-byte so existing callers — `TemplateListScreen`,
 * `TemplateEditorModal`, `NLPInput` — keep working without edits.
 *
 * The `use` action ("apply this template → create a task") is now
 * client-side: it instantiates a Task in the user's Firestore
 * tasks collection mirroring Android's
 * `TaskTemplateRepository.createTaskFromTemplate`, then bumps the
 * template's usage counter so the analytics fields
 * (`usage_count`, `last_used_at`) stay in sync.
 */

function getUid(): string {
  return getFirebaseUid();
}

interface TemplateState {
  templates: TaskTemplate[];
  isLoading: boolean;
  error: string | null;

  fetch: (category?: string, sortBy?: string) => Promise<void>;
  create: (data: TemplateCreate) => Promise<TaskTemplate>;
  createFromTask: (taskId: string) => Promise<TaskTemplate>;
  update: (id: string, data: TemplateUpdate) => Promise<TaskTemplate>;
  remove: (id: string) => Promise<void>;
  use: (id: string, data?: TemplateUseRequest) => Promise<TemplateUseResponse>;
  clearError: () => void;

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToTaskTemplates: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

function sortTemplates(
  templates: TaskTemplate[],
  sortBy?: string,
): TaskTemplate[] {
  const copy = [...templates];
  switch (sortBy) {
    case 'usage':
      return copy.sort((a, b) => b.usage_count - a.usage_count);
    case 'name':
      return copy.sort((a, b) => a.name.localeCompare(b.name));
    case 'last_used':
      return copy.sort((a, b) => {
        const ax = a.last_used_at ?? '';
        const bx = b.last_used_at ?? '';
        return bx.localeCompare(ax);
      });
    case 'created':
    default:
      return copy.sort((a, b) => b.created_at.localeCompare(a.created_at));
  }
}

function parseTemplateSubtasks(json: string | null): string[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed)
      ? parsed.filter((s): s is string => typeof s === 'string')
      : [];
  } catch {
    return [];
  }
}

function parseTemplateTagIds(json: string | null): string[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    if (!Array.isArray(parsed)) return [];
    // Tag IDs can be stored as numbers (legacy Android Long) or
    // strings (web Firestore doc IDs). Stringify both so the
    // resulting `tag_ids` array always matches the web Task shape.
    return parsed.map((id) => String(id));
  } catch {
    return [];
  }
}

export const useTemplateStore = create<TemplateState>((set, get) => ({
  templates: [],
  isLoading: false,
  error: null,

  fetch: async (category, sortBy) => {
    set({ isLoading: true, error: null });
    try {
      const uid = getUid();
      const all = await firestoreTemplates.getTaskTemplates(uid);
      // Category filter + sort are applied client-side. The previous
      // REST endpoint did the same filter on the server; mirroring it
      // here keeps the public store contract identical so callers
      // (`TemplateListScreen`, `NLPInput`) need no edits.
      const filtered = category
        ? all.filter((t) => t.category === category)
        : all;
      const templates = sortTemplates(filtered, sortBy);
      set({ templates, isLoading: false });
    } catch (e) {
      set({ error: (e as Error).message, isLoading: false });
    }
  },

  create: async (data) => {
    const uid = getUid();
    const template = await firestoreTemplates.createTemplate(uid, {
      name: data.name,
      description: data.description,
      icon: data.icon,
      category: data.category,
      template_title: data.template_title,
      template_description: data.template_description,
      template_priority: data.template_priority,
      template_project_id: data.template_project_id,
      template_tags_json: data.template_tags_json,
      template_recurrence_json: data.template_recurrence_json,
      template_duration: data.template_duration,
      template_subtasks_json: data.template_subtasks_json,
    });
    // Optimistic local insert — the snapshot listener will reconcile
    // any drift on the next tick. Without this the create-modal
    // close → list re-render path showed an empty grid for ~150ms.
    set((state) => ({ templates: [template, ...state.templates] }));
    return template;
  },

  createFromTask: async (_taskId) => {
    // Capture-task-as-template is an Android-only flow today (see
    // `TaskTemplateRepository.createTemplateFromTask`); the web UI
    // never wired it. Throw rather than silent no-op so a future
    // caller doesn't get an empty result.
    throw new Error(
      'createFromTask is not implemented on web. Use the template editor to author a new template.',
    );
  },

  update: async (id, data) => {
    const uid = getUid();
    const updated = await firestoreTemplates.updateTemplate(uid, id, {
      name: data.name,
      description: data.description,
      icon: data.icon,
      category: data.category,
      template_title: data.template_title,
      template_description: data.template_description,
      template_priority: data.template_priority,
      template_project_id: data.template_project_id,
      template_tags_json: data.template_tags_json,
      template_recurrence_json: data.template_recurrence_json,
      template_duration: data.template_duration,
      template_subtasks_json: data.template_subtasks_json,
    });
    set((state) => ({
      templates: state.templates.map((t) => (t.id === id ? updated : t)),
    }));
    return updated;
  },

  remove: async (id) => {
    const uid = getUid();
    await firestoreTemplates.deleteTemplate(uid, id);
    set((state) => ({
      templates: state.templates.filter((t) => t.id !== id),
    }));
  },

  use: async (id, data) => {
    // Client-side template → task instantiation. Mirrors Android's
    // `TaskTemplateRepository.createTaskFromTemplate`:
    //
    //   1. Resolve the template (use the live cache if present —
    //      avoids an extra round-trip for the common case where the
    //      user is applying a template they're already looking at).
    //   2. Spawn a root task with the blueprint fields.
    //   3. Spawn one subtask per `template_subtasks_json` entry.
    //   4. Bump the template's usage counter.
    //
    // The public store contract returns `{ task_id, message }` so the
    // existing toast / navigation paths in `TemplateListScreen` and
    // `NLPInput` keep working unchanged.
    const uid = getUid();
    let template = get().templates.find((t) => t.id === id) ?? null;
    if (!template) {
      template = await firestoreTemplates.getTaskTemplate(uid, id);
    }
    if (!template) {
      throw new Error('Template not found');
    }

    const tagIds = parseTemplateTagIds(template.template_tags_json);
    const subtaskTitles = parseTemplateSubtasks(template.template_subtasks_json);

    const rootTaskData: Partial<Task> & { title: string } = {
      title: template.template_title || template.name,
      description: template.template_description ?? null,
      priority: (template.template_priority as TaskPriority) ?? undefined,
      project_id: data?.project_id ?? template.template_project_id ?? '',
      due_date: data?.due_date ?? null,
      recurrence_json: template.template_recurrence_json ?? null,
      estimated_duration: template.template_duration ?? null,
      tag_ids: tagIds.length > 0 ? tagIds : undefined,
      status: 'todo',
    };

    const rootTask = await firestoreTasks.createTask(uid, rootTaskData);

    // Subtasks land in parallel — order is anchored by the explicit
    // `sort_order: i` on each row, not the Firestore commit order.
    // Best-effort: a failing subtask doesn't abort the apply flow;
    // the root task is already in place, so the user-visible action
    // succeeded.
    await Promise.all(
      subtaskTitles.map((title, i) =>
        firestoreTasks
          .createTask(uid, {
            title,
            parent_id: rootTask.id,
            sort_order: i,
            status: 'todo',
          } as Partial<Task> & { title: string })
          .catch(() => undefined),
      ),
    );

    // Bump usage counter — read-modify-write through the firestore
    // helper so the LWW guard wraps it. Failure here is non-fatal:
    // the task already landed, so the user-visible action succeeded.
    try {
      const bumped = await firestoreTemplates.incrementUsage(uid, id);
      set((state) => ({
        templates: state.templates.map((t) => (t.id === id ? bumped : t)),
      }));
    } catch {
      // Fall back to a local-only optimistic bump so the UI still
      // reflects "used N times" until the next snapshot reconciles.
      set((state) => ({
        templates: state.templates.map((t) =>
          t.id === id
            ? {
                ...t,
                usage_count: t.usage_count + 1,
                last_used_at: new Date().toISOString(),
              }
            : t,
        ),
      }));
    }

    return {
      task_id: rootTask.id,
      message: `Created task from "${template.name}"`,
    };
  },

  clearError: () => set({ error: null }),

  subscribeToTaskTemplates: (uid) => {
    return firestoreTemplates.subscribeToTaskTemplates(uid, (templates) => {
      set({ templates });
    });
  },

  reset: () =>
    set({
      templates: [],
      isLoading: false,
      error: null,
    }),
}));
