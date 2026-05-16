import {
  collection,
  onSnapshot,
  orderBy,
  query,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Real-time mirror of `users/{uid}/task_timings` — the time-tracking
 * history rows that power productivity-score and per-task time analytics
 * (see Android `TaskTimingEntity` + `TaskTimingRepository`).
 *
 * Android is the source of truth here today: Pomodoro completions,
 * manual "Log time" entries, and explicit timer stops all write into
 * Room and push to Firestore via `SyncMapper.taskTimingToMap`. Web
 * previously had no read path for this collection so analytics charts
 * built from timings (the Pro-gated bar chart on `TaskAnalyticsScreen`)
 * never lit up for cross-device sessions. Web parity sweep wires the
 * read-only listener; write parity is a follow-up.
 *
 * Snake_case keys on the UI side; the Android push uses camelCase keys
 * (`taskId`, `startedAt`, `endedAt`, `durationMinutes`, `source`,
 * `notes`, `createdAt`) — `docToTaskTiming` accepts both for forward
 * compatibility.
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
