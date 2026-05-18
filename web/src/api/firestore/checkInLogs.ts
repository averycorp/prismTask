import {
  collection,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  query,
  deleteDoc,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';

/**
 * Firestore-native morning check-in logs. Mirrors Android's
 * `CheckInLogEntity` at the field level. Backend has no check-in
 * endpoints; keeping this client-side means the streak + prompt
 * gating work end-to-end on web without a round-trip.
 *
 * One log per logical day — doc-id is the ISO date so
 * `setDoc(mergeable)` is idempotent (re-submitting overwrites).
 */

export interface CheckInLog {
  /** ISO `YYYY-MM-DD` of the logical day this log is for. */
  id: string;
  date_iso: string;
  /** Free-form CSV of "steps" the user acknowledged — e.g.
   *  "hydrated,medicated,stretched". */
  steps_completed_csv: string;
  medications_confirmed: boolean;
  tasks_reviewed: boolean;
  habits_completed: boolean;
  created_at: number;
  updated_at: number;
}

function logsCol(uid: string) {
  return collection(firestore, 'users', uid, 'check_in_logs');
}

function logDoc(uid: string, dateIso: string) {
  return doc(firestore, 'users', uid, 'check_in_logs', dateIso);
}

function docToLog(id: string, data: DocumentData): CheckInLog {
  return {
    id,
    date_iso: typeof data.dateIso === 'string' ? data.dateIso : id,
    steps_completed_csv:
      typeof data.stepsCompletedCsv === 'string' ? data.stepsCompletedCsv : '',
    medications_confirmed: !!data.medicationsConfirmed,
    tasks_reviewed: !!data.tasksReviewed,
    habits_completed: !!data.habitsCompleted,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : Date.now(),
  };
}

export interface CheckInInput {
  date_iso: string;
  steps_completed_csv?: string;
  medications_confirmed?: boolean;
  tasks_reviewed?: boolean;
  habits_completed?: boolean;
}

export async function setCheckIn(
  uid: string,
  input: CheckInInput,
): Promise<CheckInLog> {
  // LWW guard — two devices submitting a same-day check-in in
  // quick succession should not silently overwrite each other's
  // step-set. The doc id is the ISO date, so first-create wins on
  // missing-doc; subsequent setCheckIn calls compare `updatedAt`.
  // Parity audit A.2.
  const now = Date.now();
  const payload = {
    dateIso: input.date_iso,
    stepsCompletedCsv: input.steps_completed_csv ?? '',
    medicationsConfirmed: input.medications_confirmed ?? false,
    tasksReviewed: input.tasks_reviewed ?? false,
    habitsCompleted: input.habits_completed ?? false,
    createdAt: now,
    updatedAt: now,
  };
  await lwwUpdate(logDoc(uid, input.date_iso), payload);
  return docToLog(input.date_iso, payload);
}

export async function getCheckIn(
  uid: string,
  dateIso: string,
): Promise<CheckInLog | null> {
  const snap = await getDoc(logDoc(uid, dateIso));
  if (!snap.exists()) return null;
  return docToLog(snap.id, snap.data()!);
}

export async function clearCheckIn(uid: string, dateIso: string): Promise<void> {
  await deleteDoc(logDoc(uid, dateIso));
}

export async function getRecentCheckIns(
  uid: string,
  limit = 60,
): Promise<CheckInLog[]> {
  // Doc IDs are ISO dates. We pull everything (cheap for a 60-day streak
  // window) and sort client-side instead of relying on
  // `orderBy('__name__', 'desc')`, which would force a Firestore
  // single-field descending-name index exemption.
  const snap = await getDocs(query(logsCol(uid)));
  const logs = snap.docs.map((d) => docToLog(d.id, d.data()));
  logs.sort((a, b) => (a.date_iso < b.date_iso ? 1 : a.date_iso > b.date_iso ? -1 : 0));
  return logs.slice(0, limit);
}

// ── Real-time listener ───────────────────────────────────────

/**
 * Subscribe to the user's morning check-in collection. Wired from
 * `useFirestoreSync` so a check-in submitted on Android surfaces in
 * the web check-in card + streak count without a refresh. Closes
 * parity audit § A.1b residual for `check_in_logs`.
 *
 * No `orderBy` clause: Firestore's default `__name__ ASC` is implied,
 * which avoids the composite-index requirement an explicit
 * `__name__ DESC` would impose. Live consumers (`MorningCheckInBanner`
 * checks `logs.some((l) => l.date_iso === todayIso)`; the store just
 * caches the array) don't depend on iteration order.
 */
export function subscribeToCheckIns(
  uid: string,
  callback: (logs: CheckInLog[]) => void,
): Unsubscribe {
  const q = query(logsCol(uid));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToLog(d.id, d.data())));
  });
}
