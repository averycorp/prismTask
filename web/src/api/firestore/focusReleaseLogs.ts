import {
  addDoc,
  collection,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Firestore-native focus-release logs. Mirrors Android's
 * `FocusReleaseLogEntity` at the shape level. A log captures one
 * ND-friendly focus session — its planned duration, actual duration,
 * release state, and a note on why the user called it.
 *
 * Stored at `users/{uid}/focus_release_logs`. Append-only on the
 * happy path (users rarely edit history; delete lives in Settings
 * → Maintenance if we ever need it).
 */

export type ReleaseState =
  | 'shipped'
  | 'good_enough'
  | 'partial'
  | 'abandoned';

export interface FocusReleaseLog {
  id: string;
  /** Firestore doc id of the task worked on, or null for untethered
   *  sessions. */
  task_id: string | null;
  task_title_snapshot: string;
  planned_minutes: number;
  actual_minutes: number;
  release_state: ReleaseState;
  note: string;
  started_at: number;
  ended_at: number;
}

function logsCol(uid: string) {
  return collection(firestore, 'users', uid, 'focus_release_logs');
}

function docToLog(id: string, data: DocumentData): FocusReleaseLog {
  const releaseState =
    data.releaseState === 'shipped' ||
    data.releaseState === 'good_enough' ||
    data.releaseState === 'partial' ||
    data.releaseState === 'abandoned'
      ? (data.releaseState as ReleaseState)
      : 'partial';
  return {
    id,
    task_id: typeof data.taskId === 'string' ? data.taskId : null,
    task_title_snapshot:
      typeof data.taskTitleSnapshot === 'string' ? data.taskTitleSnapshot : '',
    planned_minutes:
      typeof data.plannedMinutes === 'number' ? data.plannedMinutes : 0,
    actual_minutes:
      typeof data.actualMinutes === 'number' ? data.actualMinutes : 0,
    release_state: releaseState,
    note: typeof data.note === 'string' ? data.note : '',
    started_at: typeof data.startedAt === 'number' ? data.startedAt : 0,
    ended_at: typeof data.endedAt === 'number' ? data.endedAt : 0,
  };
}

export interface FocusReleaseInput {
  task_id?: string | null;
  task_title_snapshot: string;
  planned_minutes: number;
  actual_minutes: number;
  release_state: ReleaseState;
  note?: string;
  started_at: number;
  ended_at: number;
}

export async function createLog(
  uid: string,
  input: FocusReleaseInput,
): Promise<FocusReleaseLog> {
  const payload = {
    taskId: input.task_id ?? null,
    taskTitleSnapshot: input.task_title_snapshot,
    plannedMinutes: input.planned_minutes,
    actualMinutes: input.actual_minutes,
    releaseState: input.release_state,
    note: input.note ?? '',
    startedAt: input.started_at,
    endedAt: input.ended_at,
  };
  const ref = await addDoc(logsCol(uid), payload);
  return docToLog(ref.id, payload);
}

export async function getRecentLogs(
  uid: string,
  limit = 50,
): Promise<FocusReleaseLog[]> {
  const snap = await getDocs(
    query(logsCol(uid), orderBy('startedAt', 'desc')),
  );
  return snap.docs.slice(0, limit).map((d) => docToLog(d.id, d.data()));
}

// ── Real-time listener ───────────────────────────────────────

/**
 * Subscribe to the user's focus-release log collection. Wired from
 * `useFirestoreSync` so a session shipped on Android shows up in the
 * web focus-release history without a refresh. Closes parity audit
 * § A.1b residual for `focus_release_logs`.
 *
 * Ordered descending by `startedAt` to match `getRecentLogs`.
 */
export function subscribeToFocusLogs(
  uid: string,
  callback: (logs: FocusReleaseLog[]) => void,
): Unsubscribe {
  const q = query(logsCol(uid), orderBy('startedAt', 'desc'));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToLog(d.id, d.data())));
  });
}
