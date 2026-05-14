import {
  collection,
  doc,
  getDoc,
  getDocs,
  setDoc,
  deleteDoc,
  onSnapshot,
  type Unsubscribe,
  type DocumentData,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Firestore write path for the `task_completions` history table that
 * powers cross-device analytics (completion grid, day-of-week / hour
 * distributions, on-time rate, completion-rate sparkline, etc.).
 *
 * Android writes a `task_completions` row on every task-toggle via
 * `TaskCompletionRepository.recordCompletion` and pushes it under
 * `users/{uid}/task_completions` (see `SyncService.kt` push block
 * around line 520 + `SyncMapper.taskCompletionToMap`). Web previously
 * only flipped `tasks.status = 'done'` and never wrote the history
 * row, so the analytics chart was blank for web-completed tasks.
 *
 * Parity audit item B.6 — see
 * `docs/audits/ANDROID_WEB_PARITY_AUDIT_2026-05-13.md`.
 *
 * The write path uses Pattern A canonical-row dedup
 * (`docs/audits/WEB_CANONICAL_ROW_DEDUP_PARITY_AUDIT.md`): a
 * deterministic doc id of `${taskCloudId}__${completedDateLocal}` plus
 * `setDoc(..., { merge: true })` so two devices completing the same
 * task on the same logical day converge on a single Firestore doc
 * rather than racing into duplicate siblings.
 */

// ── Collection / doc refs ─────────────────────────────────────

function taskCompletionsCol(uid: string) {
  return collection(firestore, 'users', uid, 'task_completions');
}

function taskCompletionDoc(uid: string, completionId: string) {
  return doc(firestore, 'users', uid, 'task_completions', completionId);
}

/**
 * Deterministic doc id for the natural-key
 * `(taskCloudId, completedDateLocal)`.
 *
 * Mirrors `habitCompletionId` (`web/src/api/firestore/habits.ts`).
 * Keys on the TZ-neutral `completedDateLocal` (`YYYY-MM-DD`) rather
 * than the legacy `completedDate` epoch ms, so two devices in
 * different timezones agree on the doc path for the same logical day.
 *
 * `task_completions` rows written by Android predate this contract —
 * Android writes a Firestore-allocated random doc id and the
 * `completedDate` epoch only. Cross-device collapse for those legacy
 * rows happens at read time on Android via the natural-key dedup in
 * `SyncPullOrchestrator`; on web we don't read this collection back
 * to a deduped UI (analytics-only), so legacy duplicates from Android
 * coexisting with canonical-id docs from web is acceptable.
 */
export function taskCompletionId(
  taskCloudId: string,
  completedDateLocal: string,
): string {
  return `${taskCloudId}__${completedDateLocal}`;
}

// ── Types ─────────────────────────────────────────────────────

export interface TaskCompletion {
  id: string;
  task_id: string;
  completed_date_local: string;
  completed_date: number;
  completed_at: number;
  priority: number;
  was_overdue: boolean;
  days_to_complete: number | null;
  tags: string | null;
}

function docToTaskCompletion(
  docId: string,
  data: DocumentData,
): TaskCompletion {
  // Prefer the TZ-neutral `completedDateLocal` (written by web /
  // future Android revisions). Fall back to deriving from the legacy
  // `completedDate` epoch ms for rows written by current Android
  // builds. Mirrors the habit-completion read shape.
  const legacyEpoch =
    typeof data.completedDate === 'number' ? data.completedDate : 0;
  const localKey =
    typeof data.completedDateLocal === 'string' &&
    data.completedDateLocal.length > 0
      ? data.completedDateLocal
      : epochToLocalDateString(legacyEpoch);
  return {
    id: docId,
    task_id: typeof data.taskId === 'string' ? data.taskId : '',
    completed_date_local: localKey,
    completed_date: legacyEpoch,
    completed_at:
      typeof data.completedAtTime === 'number' ? data.completedAtTime : 0,
    priority: typeof data.priority === 'number' ? data.priority : 0,
    was_overdue: data.wasOverdue === true,
    days_to_complete:
      typeof data.daysToComplete === 'number' ? data.daysToComplete : null,
    tags: typeof data.tags === 'string' ? data.tags : null,
  };
}

function epochToLocalDateString(epoch: number): string {
  if (!epoch || epoch <= 0) return '';
  const d = new Date(epoch);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

// ── Write path ───────────────────────────────────────────────

export interface RecordTaskCompletionInput {
  /** Firestore doc id of the task being completed. */
  taskCloudId: string;
  /** SoD-relative logical-day key (`YYYY-MM-DD`). Caller is responsible
   *  for resolving this via `logicalToday(...)` against the user's
   *  configured start-of-day hour so cross-device DST comparisons
   *  match Android's `DayBoundary` byte-for-byte. */
  completedDateLocal: string;
  /** Epoch ms of the completion moment. Defaults to `Date.now()`. */
  completedAt?: number;
  /** Optional priority of the task at the moment of completion.
   *  Stored on the history row so analytics keeps the priority
   *  distribution stable even after later priority edits. */
  priority?: number;
  /** Whether the task was past its due date when completed. */
  wasOverdue?: boolean;
  /** Days between task creation and completion. */
  daysToComplete?: number;
  /** Comma-separated tag names — Android stores them this way. */
  tags?: string;
}

export async function recordTaskCompletion(
  uid: string,
  input: RecordTaskCompletionInput,
): Promise<TaskCompletion> {
  const completedAt = input.completedAt ?? Date.now();
  const completedDateMs = new Date(
    input.completedDateLocal + 'T00:00:00',
  ).getTime();
  const completionId = taskCompletionId(
    input.taskCloudId,
    input.completedDateLocal,
  );
  const ref = taskCompletionDoc(uid, completionId);

  // Field names mirror Android's `SyncMapper.taskCompletionToMap`
  // exactly so the pull path on Android decodes this doc without
  // changes. The web-added `completedDateLocal` field is additive —
  // `mapToTaskCompletion` ignores unknown keys.
  const payload: Record<string, unknown> = {
    taskId: input.taskCloudId,
    completedDate: completedDateMs,
    completedDateLocal: input.completedDateLocal,
    completedAtTime: completedAt,
    priority: input.priority ?? 0,
    wasOverdue: input.wasOverdue ?? false,
    daysToComplete: input.daysToComplete ?? null,
    tags: input.tags ?? null,
    // `projectId` and `spawnedRecurrenceId` are intentionally omitted
    // on the web write path — projectId is denormalised on Android
    // only for FK back-reference (Android's `task_completions` row
    // has a `project_id` Room FK) and `spawnedRecurrenceId` is the
    // local Android Room id of the next-instance row, which is
    // meaningless from web's perspective.
  };

  await setDoc(ref, payload, { merge: true });
  return docToTaskCompletion(completionId, payload);
}

export async function removeTaskCompletion(
  uid: string,
  taskCloudId: string,
  completedDateLocal: string,
): Promise<void> {
  const completionId = taskCompletionId(taskCloudId, completedDateLocal);
  await deleteDoc(taskCompletionDoc(uid, completionId));
}

// ── Read path ────────────────────────────────────────────────

export async function getAllTaskCompletions(
  uid: string,
): Promise<TaskCompletion[]> {
  const snap = await getDocs(taskCompletionsCol(uid));
  return snap.docs.map((d) => docToTaskCompletion(d.id, d.data()));
}

export async function getTaskCompletion(
  uid: string,
  taskCloudId: string,
  completedDateLocal: string,
): Promise<TaskCompletion | null> {
  const completionId = taskCompletionId(taskCloudId, completedDateLocal);
  const snap = await getDoc(taskCompletionDoc(uid, completionId));
  if (!snap.exists()) return null;
  return docToTaskCompletion(snap.id, snap.data()!);
}

// ── Real-time listener ───────────────────────────────────────

export function subscribeToTaskCompletions(
  uid: string,
  callback: (completions: TaskCompletion[]) => void,
): Unsubscribe {
  return onSnapshot(taskCompletionsCol(uid), (snap) => {
    const completions = snap.docs.map((d) =>
      docToTaskCompletion(d.id, d.data()),
    );
    callback(completions);
  });
}
