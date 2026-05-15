import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  where,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Firestore-native bookable-habit activity logs. Mirrors Android's
 * `HabitLogEntity` (`app/.../data/local/entity/HabitLogEntity.kt`)
 * shape and the push payload built by
 * `SyncMapper.habitLogToMap` — `{ habitCloudId, date, notes, createdAt }`.
 *
 * Stored at `users/{uid}/habit_logs`. Android writes via random
 * auto-id (`userCollection("habit_logs").document()` in
 * `SyncService.kt:435`), so the web side mirrors that — no deterministic
 * doc id, no LWW guard. Activity logs are append-only by intent: the
 * Android editor offers Delete but never Update, and the only natural
 * key would be `(habitCloudId, date_ms)` which by design supports
 * multiple entries on the same day. Parity audit § B.3b.
 *
 * Reads pull every log for the current user once (the listener mirrors
 * the same query). Per-habit filtering happens client-side after the
 * snapshot lands so the listener can stream activity for all habits
 * with a single subscription — same shape as `subscribeToCompletions`
 * in `habits.ts`.
 */

export interface HabitLog {
  /** Firestore doc id (random, mirrors Android's auto-id push path). */
  id: string;
  /** Cloud id of the parent habit. Matches `habitCloudId` in the
   *  Android push payload; doc-level FK lives on the habit row itself. */
  habit_id: string;
  /** Epoch ms of the activity, mirrored from Android's `date` column.
   *  Android stores wall-clock ms (not SoD-normalized) so two logs on
   *  the same calendar day with different times stay distinct. */
  date: number;
  /** Free-form notes — null when the user left it blank on Android. */
  notes: string | null;
  /** Epoch ms when the log row was created. */
  created_at: number;
}

function logsCol(uid: string) {
  return collection(firestore, 'users', uid, 'habit_logs');
}

function logDoc(uid: string, id: string) {
  return doc(firestore, 'users', uid, 'habit_logs', id);
}

function docToLog(id: string, data: DocumentData): HabitLog {
  return {
    id,
    habit_id: typeof data.habitCloudId === 'string' ? data.habitCloudId : '',
    date: typeof data.date === 'number' ? data.date : 0,
    notes: typeof data.notes === 'string' ? data.notes : null,
    created_at:
      typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
  };
}

export interface HabitLogInput {
  /** Cloud id of the parent habit (Firestore doc id from `habits`). */
  habit_id: string;
  /** Epoch ms — caller decides whether to use `Date.now()` (book-now)
   *  or a back-dated time picked in the UI. */
  date: number;
  /** Free-form notes; empty string is normalized to null to match
   *  Android's `notes?.trim()?.ifEmpty { null }` shape. */
  notes?: string | null;
}

export async function createLog(
  uid: string,
  input: HabitLogInput,
): Promise<HabitLog> {
  const now = Date.now();
  const trimmed = (input.notes ?? '').trim();
  const payload = {
    habitCloudId: input.habit_id,
    date: input.date,
    notes: trimmed.length > 0 ? trimmed : null,
    createdAt: now,
  };
  const ref = await addDoc(logsCol(uid), payload);
  return docToLog(ref.id, payload);
}

export async function deleteLog(uid: string, id: string): Promise<void> {
  await deleteDoc(logDoc(uid, id));
}

export async function getLogsForHabit(
  uid: string,
  habitId: string,
): Promise<HabitLog[]> {
  const snap = await getDocs(
    query(
      logsCol(uid),
      where('habitCloudId', '==', habitId),
      orderBy('date', 'desc'),
    ),
  );
  return snap.docs.map((d) => docToLog(d.id, d.data()));
}

export async function getAllLogs(uid: string): Promise<HabitLog[]> {
  const snap = await getDocs(query(logsCol(uid), orderBy('date', 'desc')));
  return snap.docs.map((d) => docToLog(d.id, d.data()));
}

export function subscribeToHabitLogs(
  uid: string,
  callback: (logs: HabitLog[]) => void,
): Unsubscribe {
  // Stream every habit_log under the user so the store keeps an
  // up-to-date map keyed by habit_id without per-habit subscriptions.
  // Mirrors `subscribeToCompletions` in `habits.ts`.
  return onSnapshot(query(logsCol(uid), orderBy('date', 'desc')), (snap) => {
    callback(snap.docs.map((d) => docToLog(d.id, d.data())));
  });
}
