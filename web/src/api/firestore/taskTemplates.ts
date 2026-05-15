import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';
import { timestampToIso } from './converters';
import type { TaskTemplate } from '@/types/template';

/**
 * Firestore-native task templates. Stored at
 * `users/{uid}/task_templates`. Mirrors Android's `TaskTemplateEntity`
 * (`app/src/main/java/com/averycorp/prismtask/data/local/entity/TaskTemplateEntity.kt`)
 * via the shared Android sync shape captured in
 * `SyncMapper.taskTemplateToMap` / `mapToTaskTemplate`.
 *
 * Parity audit § B.10 — closes the disjoint-store gap where web stored
 * templates only on the FastAPI backend while Android wrote/read from
 * the `task_templates` Firestore collection. After this migration both
 * platforms read and write the same per-user Firestore collection.
 *
 * Field mapping is additive: web only owns a subset of the fields on
 * `TaskTemplateEntity`. Android-only book-keeping fields (`localId`,
 * `remoteId`, `userId`, `isBuiltIn`, `templateKey`) are surfaced when
 * present on read but the web create path leaves them absent so
 * Android-side state survives a web edit — same merge-only contract
 * used by `habits.ts` and `tasks.ts` (parity audit PR #836 § Surface
 * 2 / 3).
 */

// ── Collection references ─────────────────────────────────────

function templatesCol(uid: string) {
  return collection(firestore, 'users', uid, 'task_templates');
}

function templateDoc(uid: string, templateId: string) {
  return doc(firestore, 'users', uid, 'task_templates', templateId);
}

// ── Firestore doc → Web TaskTemplate ──────────────────────────

function docToTemplate(
  docId: string,
  data: DocumentData,
  uid: string,
): TaskTemplate {
  return {
    id: docId,
    user_id: uid,
    name: typeof data.name === 'string' ? data.name : '',
    description: typeof data.description === 'string' ? data.description : null,
    icon: typeof data.icon === 'string' ? data.icon : null,
    category: typeof data.category === 'string' ? data.category : null,
    template_title:
      typeof data.templateTitle === 'string' ? data.templateTitle : null,
    template_description:
      typeof data.templateDescription === 'string'
        ? data.templateDescription
        : null,
    template_priority:
      typeof data.templatePriority === 'number' ? data.templatePriority : null,
    template_project_id:
      typeof data.templateProjectId === 'string'
        ? data.templateProjectId
        : null,
    template_tags_json:
      typeof data.templateTagsJson === 'string' ? data.templateTagsJson : null,
    template_recurrence_json:
      typeof data.templateRecurrenceJson === 'string'
        ? data.templateRecurrenceJson
        : null,
    template_duration:
      typeof data.templateDuration === 'number' ? data.templateDuration : null,
    template_subtasks_json:
      typeof data.templateSubtasksJson === 'string'
        ? data.templateSubtasksJson
        : null,
    is_built_in: data.isBuiltIn === true,
    usage_count: typeof data.usageCount === 'number' ? data.usageCount : 0,
    last_used_at: timestampToIso(data.lastUsedAt),
    created_at: timestampToIso(data.createdAt) ?? new Date().toISOString(),
    updated_at: timestampToIso(data.updatedAt) ?? new Date().toISOString(),
  };
}

// ── Web → Firestore doc ──────────────────────────────────────

/**
 * Inputs accepted by `createTemplate` / `updateTemplate`. Mirrors the
 * shape of `TemplateCreate` / `TemplateUpdate` in
 * `web/src/types/template.ts` but loosens nullability so an explicit
 * `null` clears a field on update.
 */
export interface TaskTemplateInput {
  name?: string;
  description?: string | null;
  icon?: string | null;
  category?: string | null;
  template_title?: string | null;
  template_description?: string | null;
  template_priority?: number | null;
  template_project_id?: string | null;
  template_tags_json?: string | null;
  template_recurrence_json?: string | null;
  template_duration?: number | null;
  template_subtasks_json?: string | null;
}

function templateCreateToDoc(
  data: TaskTemplateInput & { name: string },
): Record<string, unknown> {
  const now = Date.now();
  return {
    name: data.name,
    description: data.description ?? null,
    icon: data.icon ?? null,
    category: data.category ?? null,
    templateTitle: data.template_title ?? null,
    templateDescription: data.template_description ?? null,
    templatePriority: data.template_priority ?? null,
    templateProjectId: data.template_project_id ?? null,
    templateTagsJson: data.template_tags_json ?? null,
    templateRecurrenceJson: data.template_recurrence_json ?? null,
    templateDuration: data.template_duration ?? null,
    templateSubtasksJson: data.template_subtasks_json ?? null,
    usageCount: 0,
    lastUsedAt: null,
    createdAt: now,
    updatedAt: now,
  };
}

function templateUpdateToDoc(
  data: TaskTemplateInput,
  now: number = Date.now(),
): Record<string, unknown> {
  // Merge-mode patch: include only the fields the caller actually
  // changed. Anything not present here Firestore leaves untouched,
  // protecting Android-only fields (`isBuiltIn`, `templateKey`,
  // `remoteId`, `userId`) from being clobbered by every web save.
  const payload: Record<string, unknown> = { updatedAt: now };
  if (data.name !== undefined) payload.name = data.name;
  if (data.description !== undefined) payload.description = data.description;
  if (data.icon !== undefined) payload.icon = data.icon;
  if (data.category !== undefined) payload.category = data.category;
  if (data.template_title !== undefined) {
    payload.templateTitle = data.template_title;
  }
  if (data.template_description !== undefined) {
    payload.templateDescription = data.template_description;
  }
  if (data.template_priority !== undefined) {
    payload.templatePriority = data.template_priority;
  }
  if (data.template_project_id !== undefined) {
    payload.templateProjectId = data.template_project_id;
  }
  if (data.template_tags_json !== undefined) {
    payload.templateTagsJson = data.template_tags_json;
  }
  if (data.template_recurrence_json !== undefined) {
    payload.templateRecurrenceJson = data.template_recurrence_json;
  }
  if (data.template_duration !== undefined) {
    payload.templateDuration = data.template_duration;
  }
  if (data.template_subtasks_json !== undefined) {
    payload.templateSubtasksJson = data.template_subtasks_json;
  }
  return payload;
}

// ── CRUD operations ──────────────────────────────────────────

export async function getTaskTemplates(uid: string): Promise<TaskTemplate[]> {
  const q = query(templatesCol(uid), orderBy('createdAt', 'desc'));
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToTemplate(d.id, d.data(), uid));
}

export async function getTaskTemplate(
  uid: string,
  templateId: string,
): Promise<TaskTemplate | null> {
  const snap = await getDoc(templateDoc(uid, templateId));
  if (!snap.exists()) return null;
  return docToTemplate(snap.id, snap.data()!, uid);
}

export async function createTemplate(
  uid: string,
  data: TaskTemplateInput & { name: string },
): Promise<TaskTemplate> {
  const payload = templateCreateToDoc(data);
  const ref = await addDoc(templatesCol(uid), payload);
  return docToTemplate(ref.id, payload, uid);
}

export async function updateTemplate(
  uid: string,
  templateId: string,
  data: TaskTemplateInput,
): Promise<TaskTemplate> {
  // LWW guard: an in-flight Android-side edit (e.g. usage-count bump
  // on apply) shouldn't be silently overwritten by a concurrent web
  // rename. Same contract as `habits.ts` / `tasks.ts`. Parity audit
  // A.2.
  const now = Date.now();
  const payload = templateUpdateToDoc(data, now);
  await lwwUpdate(
    templateDoc(uid, templateId),
    payload as Parameters<typeof lwwUpdate>[1],
  );
  const snap = await getDoc(templateDoc(uid, templateId));
  return docToTemplate(snap.id, snap.data()!, uid);
}

export async function deleteTemplate(
  uid: string,
  templateId: string,
): Promise<void> {
  await deleteDoc(templateDoc(uid, templateId));
}

/**
 * Bump a template's usage counter and timestamp. Called from the
 * client-side "apply template" path after the spawned task lands so
 * the analytics fields (`usage_count`, `last_used_at`) stay in sync.
 * Android does the same bookkeeping inside
 * `TaskTemplateRepository.createTaskFromTemplate` via
 * `templateDao.incrementUsage` — kept in lockstep here.
 */
export async function incrementUsage(
  uid: string,
  templateId: string,
): Promise<TaskTemplate> {
  const snap = await getDoc(templateDoc(uid, templateId));
  if (!snap.exists()) {
    throw new Error('Template not found');
  }
  const existing = snap.data() ?? {};
  const currentCount =
    typeof existing.usageCount === 'number' ? existing.usageCount : 0;
  const now = Date.now();
  const payload: Record<string, unknown> = {
    usageCount: currentCount + 1,
    lastUsedAt: now,
    updatedAt: now,
  };
  await lwwUpdate(
    templateDoc(uid, templateId),
    payload as Parameters<typeof lwwUpdate>[1],
  );
  const updated = await getDoc(templateDoc(uid, templateId));
  return docToTemplate(updated.id, updated.data()!, uid);
}

// ── Real-time listener ───────────────────────────────────────

/**
 * Subscribe to the user's task_templates collection. Wired from
 * `useFirestoreSync` so cross-device edits (Android creates a
 * template, web should reflect it immediately in the picker without
 * a manual fetch). Closes parity audit § B.10.
 */
export function subscribeToTaskTemplates(
  uid: string,
  callback: (templates: TaskTemplate[]) => void,
): Unsubscribe {
  const q = query(templatesCol(uid), orderBy('createdAt', 'desc'));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToTemplate(d.id, d.data(), uid)));
  });
}
