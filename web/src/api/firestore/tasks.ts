import {
  collection,
  doc,
  getDoc,
  getDocs,
  addDoc,
  deleteDoc,
  query,
  where,
  orderBy,
  onSnapshot,
  type Unsubscribe,
  type DocumentData,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';
import type { CognitiveLoad, LifeCategory, Task, TaskMode, TaskStatus } from '@/types/task';
import {
  timestampToDateStr,
  timestampToTimeStr,
  timestampToIso,
  dateStrToTimestamp,
  isoToTimestamp,
  startOfTodayMs,
  endOfTodayMs,
  startOfDaysFromNowMs,
  androidToWebPriority,
  webToAndroidPriority,
} from './converters';

// ── Collection reference ──────────────────────────────────────

function tasksCol(uid: string) {
  return collection(firestore, 'users', uid, 'tasks');
}

function taskDoc(uid: string, taskId: string) {
  return doc(firestore, 'users', uid, 'tasks', taskId);
}

// ── Firestore doc → Web Task ──────────────────────────────────

function docToTask(docId: string, data: DocumentData, uid: string): Task {
  const tagIds: string[] = Array.isArray(data.tagIds)
    ? data.tagIds.filter((x: unknown): x is string => typeof x === 'string')
    : [];
  return {
    id: docId,
    project_id: data.projectId ?? '',
    user_id: uid,
    parent_id: data.parentTaskId ?? null,
    title: data.title ?? '',
    description: data.description ?? null,
    notes: data.notes ?? null,
    status: mapStatus(data),
    priority: androidToWebPriority(data.priority ?? 0),
    due_date: timestampToDateStr(data.dueDate),
    due_time: timestampToTimeStr(data.dueTime),
    planned_date: timestampToDateStr(data.plannedDate),
    completed_at: timestampToIso(data.completedAt),
    urgency_score: 0,
    recurrence_json: data.recurrenceRule ?? null,
    eisenhower_quadrant: data.eisenhowerQuadrant ?? null,
    eisenhower_updated_at: timestampToIso(data.eisenhowerUpdatedAt),
    estimated_duration: data.estimatedDuration ?? null,
    actual_duration: null,
    sort_order: data.sortOrder ?? 0,
    depth: 0,
    created_at: timestampToIso(data.createdAt) ?? new Date().toISOString(),
    updated_at: timestampToIso(data.updatedAt) ?? new Date().toISOString(),
    subtasks: [],
    tags: [],
    tag_ids: tagIds,
    life_category: parseLifeCategory(data.lifeCategory),
    task_mode: parseTaskMode(data.taskMode),
    cognitive_load: parseCognitiveLoad(data.cognitiveLoad),
    user_overrode_quadrant: data.userOverrodeQuadrant === true,
    is_flagged: data.isFlagged === true,
    progress_percent:
      typeof data.progressPercent === 'number' ? data.progressPercent : null,
    phase_id: typeof data.phaseId === 'string' ? data.phaseId : null,
  };
}

function parseLifeCategory(value: unknown): LifeCategory | null {
  if (typeof value !== 'string') return null;
  switch (value) {
    case 'WORK':
    case 'PERSONAL':
    case 'SELF_CARE':
    case 'HEALTH':
    case 'UNCATEGORIZED':
      return value;
    default:
      return null;
  }
}

function parseTaskMode(value: unknown): TaskMode | null {
  if (typeof value !== 'string') return null;
  switch (value) {
    case 'WORK':
    case 'PLAY':
    case 'RELAX':
    case 'UNCATEGORIZED':
      return value;
    default:
      return null;
  }
}

function parseCognitiveLoad(value: unknown): CognitiveLoad | null {
  if (typeof value !== 'string') return null;
  switch (value) {
    case 'EASY':
    case 'MEDIUM':
    case 'HARD':
    case 'UNCATEGORIZED':
      return value;
    default:
      return null;
  }
}

function mapStatus(data: DocumentData): TaskStatus {
  // If the doc has a web-style status field, prefer it
  if (data.webStatus) return data.webStatus as TaskStatus;
  return data.isCompleted ? 'done' : 'todo';
}

// ── Web Task → Firestore doc ──────────────────────────────────

/**
 * Combine a `YYYY-MM-DD` date string and an optional `HH:mm` time string
 * into the Long-millis representation Android stores in `tasks.due_time`.
 *
 * Android's `due_time` column is the FULL date+time (millis since epoch),
 * not a wall-clock-only value (see `TaskEntity.dueTime: Long?`). When the
 * caller has only the time, we anchor it to the date so cross-device reads
 * pick up the right wall clock; when they have only the date we return
 * null so we don't fabricate a time.
 */
function buildDueTimeMillis(
  dateStr: string | null | undefined,
  timeStr: string | null | undefined,
): number | null {
  if (!timeStr) return null;
  const datePart = dateStr ?? new Date().toISOString().slice(0, 10);
  const ms = new Date(`${datePart}T${timeStr}:00`).getTime();
  return Number.isFinite(ms) ? ms : null;
}

function taskCreateToDoc(
  data: Partial<Task> & { title: string } & {
    isFlagged?: boolean;
    lifeCategory?: string | null;
    taskMode?: string | null;
    cognitiveLoad?: string | null;
    eisenhowerReason?: string | null;
    userOverrodeQuadrant?: boolean;
  },
): Record<string, unknown> {
  const now = Date.now();
  // Build the doc additively so Android-only fields stay absent (Firestore
  // simply omits them — Android applies its own defaults on first read).
  // Web *must not* write `null`/`false` placeholders for fields it doesn't
  // own; doing so destroyed Android-side state on every save (parity bug
  // PR #836 audit § Surface 3 / T-S1+T-S2).
  const doc: Record<string, unknown> = {
    title: data.title,
    description: data.description ?? null,
    notes: data.notes ?? null,
    priority: webToAndroidPriority(data.priority ?? 4),
    isCompleted: data.status === 'done',
    webStatus: data.status ?? 'todo',
    projectId: data.project_id ?? '',
    parentTaskId: data.parent_id ?? null,
    dueDate: dateStrToTimestamp(data.due_date),
    plannedDate: dateStrToTimestamp(data.planned_date),
    recurrenceRule: data.recurrence_json ?? null,
    estimatedDuration: data.estimated_duration ?? null,
    eisenhowerQuadrant: data.eisenhower_quadrant ?? null,
    eisenhowerUpdatedAt: data.eisenhower_updated_at
      ? isoToTimestamp(data.eisenhower_updated_at)
      : null,
    sortOrder: data.sort_order ?? 0,
    tags: [],
    createdAt: now,
    updatedAt: now,
    completedAt: data.status === 'done' ? now : null,
  };
  // `dueTime`: only write when caller actually has one (e.g. NLP parse,
  // explicit time-picker value). Never `null` — that overwrote any
  // Android-side parsed time.
  const dueTimeMillis = buildDueTimeMillis(data.due_date, data.due_time);
  if (dueTimeMillis !== null) doc.dueTime = dueTimeMillis;
  if (data.isFlagged !== undefined) doc.isFlagged = data.isFlagged;
  if (data.lifeCategory !== undefined && data.lifeCategory !== null) {
    doc.lifeCategory = data.lifeCategory;
  }
  // Mode: same omit-on-null semantics as lifeCategory to avoid clobbering
  // Android-side state (see docs/WORK_PLAY_RELAX.md § Defaults & migration).
  if (data.taskMode !== undefined && data.taskMode !== null) {
    doc.taskMode = data.taskMode;
  }
  // Cognitive load: same omit-on-null semantics — see
  // docs/COGNITIVE_LOAD.md § Defaults & migration.
  if (data.cognitiveLoad !== undefined && data.cognitiveLoad !== null) {
    doc.cognitiveLoad = data.cognitiveLoad;
  }
  if (data.eisenhowerReason !== undefined) doc.eisenhowerReason = data.eisenhowerReason;
  if (data.userOverrodeQuadrant !== undefined) {
    doc.userOverrodeQuadrant = data.userOverrodeQuadrant;
  }
  if (Array.isArray(data.tag_ids)) {
    doc.tagIds = data.tag_ids.filter((x): x is string => typeof x === 'string');
  }
  // Roadmap fields. Both follow omit-on-undefined semantics so an
  // ordinary task create (no roadmap context) doesn't write `null`
  // placeholders that would clobber Android-side state.
  if (data.phase_id !== undefined) doc.phaseId = data.phase_id;
  if (data.progress_percent !== undefined) doc.progressPercent = data.progress_percent;
  return doc;
}

function taskUpdateToDoc(
  data: Record<string, unknown>,
  now: number = Date.now(),
): Record<string, unknown> {
  // Merge-mode write: include ONLY the fields the caller actually changed.
  // Anything not present here Firestore leaves untouched — protecting
  // Android-only fields like `isFlagged`, `lifeCategory`, `eisenhowerReason`,
  // `userOverrodeQuadrant`, all Focus-Release fields, `archived_at`,
  // `source_habit_id`, `scheduled_start_time`, `reminder_offset` from being
  // clobbered on every web edit (parity audit PR #836 § Surface 3, T-S2).
  //
  // The caller threads `now` through so the same wall-clock value lands on
  // both the doc's `updatedAt` and the LWW guard's comparison — see
  // `lww.ts` for why a fresh `Date.now()` inside the transaction would
  // race.
  const doc: Record<string, unknown> = { updatedAt: now };
  if (data.title !== undefined) doc.title = data.title;
  if (data.description !== undefined) doc.description = data.description;
  if (data.notes !== undefined) doc.notes = data.notes;
  if (data.priority !== undefined) doc.priority = webToAndroidPriority(data.priority as number);
  if (data.status !== undefined) {
    doc.isCompleted = data.status === 'done';
    doc.webStatus = data.status;
    if (data.status === 'done') doc.completedAt = now;
  }
  if (data.project_id !== undefined) doc.projectId = data.project_id;
  if (data.parent_id !== undefined) doc.parentTaskId = data.parent_id;
  if (data.due_date !== undefined) doc.dueDate = dateStrToTimestamp(data.due_date as string | null);
  if (data.planned_date !== undefined) {
    doc.plannedDate = dateStrToTimestamp(data.planned_date as string | null);
  }
  if (data.due_time !== undefined) {
    // Allow the caller to either set or clear the time. When clearing
    // (passing `null`/empty), write `null` explicitly; when setting,
    // anchor against the new dueDate if the caller passed one in the
    // same edit, otherwise fall back to today.
    const timeVal = data.due_time as string | null;
    if (timeVal === null || timeVal === '') {
      doc.dueTime = null;
    } else {
      doc.dueTime = buildDueTimeMillis(
        (data.due_date as string | undefined) ?? null,
        timeVal,
      );
    }
  }
  if (data.sort_order !== undefined) doc.sortOrder = data.sort_order;
  if (data.recurrence_json !== undefined) doc.recurrenceRule = data.recurrence_json;
  if (data.estimated_duration !== undefined) doc.estimatedDuration = data.estimated_duration;
  if (data.eisenhower_quadrant !== undefined) doc.eisenhowerQuadrant = data.eisenhower_quadrant;
  // `userOverrodeQuadrant` should travel with manual quadrant moves so
  // Android's auto-classifier doesn't undo the user's choice on next sync.
  // Callers (e.g. EisenhowerScreen drag handler) opt in by passing it
  // alongside `eisenhower_quadrant`; we never set it implicitly.
  if (data.userOverrodeQuadrant !== undefined) {
    doc.userOverrodeQuadrant = data.userOverrodeQuadrant;
  }
  if (data.eisenhowerReason !== undefined) doc.eisenhowerReason = data.eisenhowerReason;
  if (data.lifeCategory !== undefined) doc.lifeCategory = data.lifeCategory;
  if (data.taskMode !== undefined) doc.taskMode = data.taskMode;
  if (data.cognitiveLoad !== undefined) doc.cognitiveLoad = data.cognitiveLoad;
  if (data.isFlagged !== undefined) doc.isFlagged = data.isFlagged;
  if (data.tag_ids !== undefined && Array.isArray(data.tag_ids)) {
    doc.tagIds = (data.tag_ids as string[]).filter((x) => typeof x === 'string');
  }
  // Roadmap fields. `phase_id: null` clears the link; omit leaves it
  // alone. `progress_percent: null` clears fractional progress (binary
  // task again); omit leaves it alone.
  if (data.phase_id !== undefined) doc.phaseId = data.phase_id;
  if (data.progress_percent !== undefined) doc.progressPercent = data.progress_percent;
  return doc;
}

/**
 * Replace the task's tag ID list outright. Used by the batch applier's
 * TAG_CHANGE path (slice 15) and by any future UI that edits tag chips
 * directly on a task.
 *
 * Guarded by [lwwUpdate]: an in-flight Android task edit with a newer
 * `updatedAt` would otherwise have its non-tag fields silently
 * clobbered by Firestore's merge semantics. See parity audit A.2.
 */
export async function setTagsForTask(
  uid: string,
  taskId: string,
  tagIds: string[],
): Promise<void> {
  const now = Date.now();
  await lwwUpdate(taskDoc(uid, taskId), {
    tagIds: tagIds.filter((x) => typeof x === 'string'),
    updatedAt: now,
  });
}

// ── CRUD operations ──────────────────────────────────────────

export async function getTodayTasks(uid: string): Promise<Task[]> {
  const todayStart = startOfTodayMs();
  const todayEnd = endOfTodayMs();
  const q = query(
    tasksCol(uid),
    where('dueDate', '>=', todayStart),
    where('dueDate', '<', todayEnd),
    where('isCompleted', '==', false),
  );
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToTask(d.id, d.data(), uid));
}

export async function getOverdueTasks(uid: string): Promise<Task[]> {
  const todayStart = startOfTodayMs();
  const q = query(
    tasksCol(uid),
    where('dueDate', '<', todayStart),
    where('isCompleted', '==', false),
    orderBy('dueDate', 'asc'),
  );
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToTask(d.id, d.data(), uid));
}

export async function getUpcomingTasks(uid: string, days = 7): Promise<Task[]> {
  const todayEnd = endOfTodayMs();
  const futureEnd = startOfDaysFromNowMs(days + 1);
  const q = query(
    tasksCol(uid),
    where('dueDate', '>=', todayEnd),
    where('dueDate', '<', futureEnd),
    where('isCompleted', '==', false),
    orderBy('dueDate', 'asc'),
  );
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToTask(d.id, d.data(), uid));
}

export async function getTasksByProject(uid: string, projectId: string): Promise<Task[]> {
  const q = query(
    tasksCol(uid),
    where('projectId', '==', projectId),
    orderBy('sortOrder', 'asc'),
  );
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToTask(d.id, d.data(), uid));
}

export async function getAllTasks(uid: string): Promise<Task[]> {
  const q = query(tasksCol(uid), orderBy('sortOrder', 'asc'));
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToTask(d.id, d.data(), uid));
}

export async function getTask(uid: string, taskId: string): Promise<Task | null> {
  const snap = await getDoc(taskDoc(uid, taskId));
  if (!snap.exists()) return null;
  return docToTask(snap.id, snap.data()!, uid);
}

export async function createTask(
  uid: string,
  data: Partial<Task> & { title: string },
): Promise<Task> {
  const firestoreData = taskCreateToDoc(data);
  const ref = await addDoc(tasksCol(uid), firestoreData);
  return docToTask(ref.id, firestoreData, uid);
}

export async function updateTask(
  uid: string,
  taskId: string,
  data: Record<string, unknown>,
): Promise<Task> {
  // LWW guard. We stamp `now` once and thread it into both the patch
  // payload and the guard's comparison so a Firestore-side race can't
  // see one timestamp on the doc and a different one on the precondition
  // (parity audit A.2). On stale-abort we still re-read the doc and
  // return the remote state — the caller's reducer applies the same
  // state as the snapshot listener will, so the optimistic UI doesn't
  // diverge from the eventual Firestore state.
  const now = Date.now();
  const firestoreData = taskUpdateToDoc(data, now);
  await lwwUpdate(taskDoc(uid, taskId), firestoreData as Parameters<typeof lwwUpdate>[1]);
  // Re-read the full document to return updated task
  const snap = await getDoc(taskDoc(uid, taskId));
  return docToTask(snap.id, snap.data()!, uid);
}

export async function deleteTask(uid: string, taskId: string): Promise<void> {
  await deleteDoc(taskDoc(uid, taskId));
}

export async function getSubtasks(uid: string, parentTaskId: string): Promise<Task[]> {
  const q = query(
    tasksCol(uid),
    where('parentTaskId', '==', parentTaskId),
    orderBy('sortOrder', 'asc'),
  );
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToTask(d.id, d.data(), uid));
}

// ── Real-time listener ───────────────────────────────────────

export function subscribeToTasks(
  uid: string,
  callback: (tasks: Task[]) => void,
): Unsubscribe {
  const q = query(tasksCol(uid), orderBy('updatedAt', 'desc'));
  return onSnapshot(q, (snap) => {
    const tasks = snap.docs.map((d) => docToTask(d.id, d.data(), uid));
    callback(tasks);
  });
}
