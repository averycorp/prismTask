import {
  addDoc,
  collection,
  onSnapshot,
  orderBy,
  query,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Firestore mirror of `users/{uid}/task_timings` — the time-tracking
 * history rows that power productivity-score and per-task time analytics
 * (see Android `TaskTimingEntity` + `TaskTimingRepository`).
 *
 * Read path lit up first (cross-device Pomodoro / manual log / timer
 * entries from Android land in the analytics surface). Write parity
 * shipped in audit item 7 — Pomodoro session-complete on web now
 * auto-logs one row per session task, mirroring Android's
 * `SmartPomodoroViewModel.autoLogPomodoroSessionTime`. Manual "Log
 * time" UI on web is a separate follow-up.
 *
 * Snake_case keys on the UI side; the Android push uses camelCase keys
 * (`taskId`, `startedAt`, `endedAt`, `durationMinutes`, `source`,
 * `notes`, `createdAt`) — `docToTaskTiming` accepts both for forward
 * compatibility and `addTaskTiming` writes the same camelCase shape so
 * Android's `SyncMapper.mapToTaskTiming` decodes the doc without
 * changes.
 */

export interface TaskTiming {
  /** Firestore doc id (== Android `cloud_id`). */
  id: string;
  /** Cloud id of the parent task. Empty string when the parent hasn't
   *  been pushed yet (rare; Android only writes timings after the task
   *  push completes, but the field is nullable for safety). */
  task_id: string;
  /** Epoch ms when the interval started. Null for manual log entries
   *  that record just a duration without a wall-clock window. */
  started_at: number | null;
  /** Epoch ms when the interval ended. Null for the same reason. */
  ended_at: number | null;
  duration_minutes: number;
  /** "manual" | "pomodoro" | "timer". Free-form to mirror Android. */
  source: string;
  notes: string | null;
  created_at: number;
}

function timingsCol(uid: string) {
  return collection(firestore, 'users', uid, 'task_timings');
}

function docToTaskTiming(docId: string, data: DocumentData): TaskTiming {
  return {
    id: docId,
    task_id: typeof data.taskId === 'string' ? data.taskId : '',
    started_at: typeof data.startedAt === 'number' ? data.startedAt : null,
    ended_at: typeof data.endedAt === 'number' ? data.endedAt : null,
    duration_minutes:
      typeof data.durationMinutes === 'number' ? data.durationMinutes : 0,
    source: typeof data.source === 'string' ? data.source : 'manual',
    notes: typeof data.notes === 'string' ? data.notes : null,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : 0,
  };
}

export function subscribeToTaskTimings(
  uid: string,
  callback: (timings: TaskTiming[]) => void,
): Unsubscribe {
  // Order by `createdAt` descending so the most recent rows are first —
  // the bar chart and recent-timing UI both scan from newest backward.
  const q = query(timingsCol(uid), orderBy('createdAt', 'desc'));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToTaskTiming(d.id, d.data())));
  });
}

// ── Write path ───────────────────────────────────────────────

/** Mirrors `TaskTimingEntity.SOURCE_*` so the source label survives
 *  Android round-trips byte-for-byte. */
export type TaskTimingSource = 'manual' | 'pomodoro' | 'timer';

export interface AddTaskTimingInput {
  /** Firestore doc id of the parent task. Must be non-empty so the
   *  Android pull side can rehydrate the FK. */
  taskCloudId: string;
  /** Minutes credited to the task. Must be > 0 — Android's repository
   *  asserts the same invariant. */
  durationMinutes: number;
  /** "manual" | "pomodoro" | "timer". Defaults to "manual" to mirror
   *  `TaskTimingEntity.source`'s Room default. */
  source?: TaskTimingSource;
  /** Epoch ms when the interval started. Nullable for manual log
   *  entries that record just a duration. */
  startedAt?: number | null;
  /** Epoch ms when the interval ended. Nullable for the same reason. */
  endedAt?: number | null;
  notes?: string | null;
  /** Epoch ms when the row was logged. Defaults to `Date.now()`. */
  createdAt?: number;
}

/**
 * Append a row to `users/{uid}/task_timings`. Doc id is Firestore
 * auto-generated (matching Android's `SyncService` upload path which
 * calls `userCollection("task_timings")?.document()` with no arg —
 * timings are append-only, not natural-key dedup'd like
 * `task_completions`).
 *
 * Field shape is identical to `SyncMapper.taskTimingToMap` so the
 * Android pull (`SyncPullOrchestrator` → `SyncMapper.mapToTaskTiming`)
 * decodes the doc without changes.
 */
export async function addTaskTiming(
  uid: string,
  input: AddTaskTimingInput,
): Promise<TaskTiming> {
  if (input.durationMinutes <= 0) {
    throw new Error(
      `durationMinutes must be > 0 (got ${input.durationMinutes})`,
    );
  }
  if (!input.taskCloudId) {
    throw new Error('taskCloudId is required');
  }
  const createdAt = input.createdAt ?? Date.now();
  // `localId` is omitted on the web write path. Android's
  // `taskTimingToMap` includes it as the Room PK, but `mapToTaskTiming`
  // ignores it on read — the Room insert allocates a fresh local id.
  const payload: Record<string, unknown> = {
    taskId: input.taskCloudId,
    startedAt: input.startedAt ?? null,
    endedAt: input.endedAt ?? null,
    durationMinutes: input.durationMinutes,
    source: input.source ?? 'manual',
    notes: input.notes ?? null,
    createdAt,
  };
  const ref = await addDoc(timingsCol(uid), payload);
  return docToTaskTiming(ref.id, payload);
}
