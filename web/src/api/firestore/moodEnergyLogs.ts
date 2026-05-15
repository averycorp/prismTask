import {
  collection,
  deleteDoc,
  doc,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  setDoc,
  where,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';

/**
 * Firestore-native mood & energy logs. Mirrors Android's
 * `MoodEnergyLogEntity` (`app/.../data/local/entity/MoodEnergyLogEntity.kt`)
 * without a backend round-trip — the backend has no mood endpoints.
 *
 * Stored at `users/{uid}/mood_energy_logs`. `dateIso` is the ISO
 * `YYYY-MM-DD` of the logical day the entry belongs to (not the
 * creation instant), so multi-entry days (morning + afternoon) are
 * possible and range queries are simple.
 *
 * Doc ids are deterministic — `${dateIso}__${timeOfDay}` — to mirror
 * Android's unique index on `(date, time_of_day)`. A second log in the
 * same slot merges into the existing doc (`setDoc(..., { merge: true })`)
 * rather than creating a duplicate; this prevents Android Room's unique
 * constraint from rejecting the second pull and leaving an orphaned
 * cloud row. Same convention as `medicationSlots.ts` tier states and
 * `checkInLogs.ts`.
 */

export type TimeOfDay = 'morning' | 'afternoon' | 'evening' | 'night';

export interface MoodEnergyLog {
  id: string;
  date_iso: string;
  /** 1–5, with 5 = best. */
  mood: number;
  /** 1–5, with 5 = peak energy. */
  energy: number;
  notes: string;
  time_of_day: TimeOfDay;
  created_at: number;
  updated_at: number;
}

function logsCol(uid: string) {
  return collection(firestore, 'users', uid, 'mood_energy_logs');
}

function logDoc(uid: string, id: string) {
  return doc(firestore, 'users', uid, 'mood_energy_logs', id);
}

/**
 * Deterministic doc id for the natural-key `(dateIso, timeOfDay)`.
 * Mirrors Android's unique index `(date, time_of_day)` on
 * `mood_energy_logs` so a second log in the same slot merges into the
 * existing doc instead of producing two cloud rows that collide on
 * pull. Format mirrors `medicationSlots.ts` (`${dateIso}__${slotKey}`).
 */
function moodLogId(dateIso: string, timeOfDay: TimeOfDay): string {
  return `${dateIso}__${timeOfDay}`;
}

function clampScale(n: unknown): number {
  const v = typeof n === 'number' ? Math.round(n) : 3;
  if (!Number.isFinite(v)) return 3;
  if (v < 1) return 1;
  if (v > 5) return 5;
  return v;
}

function docToLog(id: string, data: DocumentData): MoodEnergyLog {
  const tod = data.timeOfDay;
  const time_of_day: TimeOfDay =
    tod === 'afternoon' || tod === 'evening' || tod === 'night'
      ? tod
      : 'morning';
  return {
    id,
    date_iso: typeof data.dateIso === 'string' ? data.dateIso : '',
    mood: clampScale(data.mood),
    energy: clampScale(data.energy),
    notes: typeof data.notes === 'string' ? data.notes : '',
    time_of_day,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : Date.now(),
  };
}

export interface MoodEnergyLogInput {
  date_iso: string;
  mood: number;
  energy: number;
  notes?: string;
  time_of_day?: TimeOfDay;
}

export async function createLog(
  uid: string,
  input: MoodEnergyLogInput,
): Promise<MoodEnergyLog> {
  const now = Date.now();
  const timeOfDay: TimeOfDay = input.time_of_day ?? 'morning';
  const payload = {
    dateIso: input.date_iso,
    mood: clampScale(input.mood),
    energy: clampScale(input.energy),
    notes: input.notes ?? '',
    timeOfDay,
    createdAt: now,
    updatedAt: now,
  };
  const id = moodLogId(input.date_iso, timeOfDay);
  // setDoc + merge gives us idempotent upsert keyed by the natural
  // (date, time_of_day) tuple — a second log in the same slot updates
  // the existing doc rather than creating a duplicate that would later
  // fail Android Room's unique index on `(date, time_of_day)`.
  await setDoc(logDoc(uid, id), payload, { merge: true });
  return docToLog(id, payload);
}

export async function updateLog(
  uid: string,
  id: string,
  input: Partial<MoodEnergyLogInput>,
): Promise<void> {
  // LWW guard — a same-slot edit from a sibling device shouldn't be
  // overwritten by a stale web push. `createLog` keeps its current
  // canonical-id merge semantics: it's the natural-key write path
  // that intentionally collapses duplicate same-slot entries.
  // Parity audit A.2.
  const now = Date.now();
  const payload: Record<string, unknown> = { updatedAt: now };
  if (input.date_iso !== undefined) payload.dateIso = input.date_iso;
  if (input.mood !== undefined) payload.mood = clampScale(input.mood);
  if (input.energy !== undefined) payload.energy = clampScale(input.energy);
  if (input.notes !== undefined) payload.notes = input.notes;
  if (input.time_of_day !== undefined) payload.timeOfDay = input.time_of_day;
  await lwwUpdate(logDoc(uid, id), payload as Parameters<typeof lwwUpdate>[1]);
}

export async function deleteLog(uid: string, id: string): Promise<void> {
  await deleteDoc(logDoc(uid, id));
}

export async function getLogsInRange(
  uid: string,
  startIso: string,
  endIso: string,
): Promise<MoodEnergyLog[]> {
  const snap = await getDocs(
    query(
      logsCol(uid),
      where('dateIso', '>=', startIso),
      where('dateIso', '<=', endIso),
      orderBy('dateIso', 'asc'),
      orderBy('createdAt', 'asc'),
    ),
  );
  return snap.docs.map((d) => docToLog(d.id, d.data()));
}

// ── Real-time listener ───────────────────────────────────────

/**
 * Subscribe to the user's mood/energy log collection. Wired from
 * `useFirestoreSync` so cross-device logs (Android writes a morning
 * entry, web should reflect it on Today / Mood screens without a
 * refresh). Closes parity audit § A.1b residual for `mood_energy_logs`.
 *
 * Ordered ascending by `dateIso` then `createdAt` to match the existing
 * range-query shape — consumers that want descending recent-first
 * traversal can slice/reverse on read.
 */
export function subscribeToMoodLogs(
  uid: string,
  callback: (logs: MoodEnergyLog[]) => void,
): Unsubscribe {
  const q = query(
    logsCol(uid),
    orderBy('dateIso', 'asc'),
    orderBy('createdAt', 'asc'),
  );
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToLog(d.id, d.data())));
  });
}
