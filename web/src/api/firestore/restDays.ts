import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Firestore-native rest-day primitive. Mirrors Android's `RestDayEntity`
 * at the shape level (see `docs/REST_DAY.md` for the philosophy). One
 * row per logical date the user has marked as a rest day. While the
 * flag is on, the forgiveness-first streak walk treats the date as
 * "kept by definition" — it does NOT consume the grace window and does
 * NOT count as a miss.
 *
 * Storage shape:
 *  - Path: `users/{uid}/restDays/{isoDate}` where `isoDate` is
 *    `yyyy-MM-dd` of the user's *logical* (Start-of-Day-aware) day.
 *    Using the ISO date as the doc id makes mark/unmark idempotent
 *    (`setDoc` with merge is a no-op on the same id, `deleteDoc` is a
 *    no-op when the row is already absent) and keeps the row stable
 *    across timezone shifts.
 *  - Body: `{ date: string, createdAt: Timestamp, updatedAt: Timestamp }`.
 *    `date` mirrors the doc id; `createdAt` / `updatedAt` are server
 *    timestamps for parity with Android's `created_at` / `updated_at`
 *    columns.
 *
 * Web doesn't fire the rest-of-app non-medication notifications today
 * — quiet hours / habit reminders / task reminders are Android-side —
 * so the gate seam Android wires through `RestDayGate` is N/A here.
 * The web primitive is purely for: (a) streak-fold parity, and (b) the
 * soft Today-screen banner.
 */

export interface RestDay {
  /** ISO `yyyy-MM-dd` of the logical day this row marks as a rest day. */
  date: string;
  /** Server-millis when the row was first written. */
  created_at: number;
  /** Server-millis of the last write. Currently equal to `created_at` —
   *  rest days are mark/unmark, never edited in place. */
  updated_at: number;
}

function restDaysCol(uid: string) {
  return collection(firestore, 'users', uid, 'restDays');
}

function restDayDoc(uid: string, isoDate: string) {
  return doc(firestore, 'users', uid, 'restDays', isoDate);
}

function docToRestDay(id: string, data: DocumentData): RestDay {
  // `createdAt` / `updatedAt` can be a Firestore Timestamp (server) or a
  // local-write echo with millis; tolerate both shapes. The exact ms
  // doesn't matter to the streak walk — only the date does — so we
  // default missing values to 0 rather than throwing.
  const toMs = (v: unknown): number => {
    if (typeof v === 'number') return v;
    if (v && typeof v === 'object' && 'toMillis' in (v as object)) {
      try {
        return (v as { toMillis: () => number }).toMillis();
      } catch {
        return 0;
      }
    }
    return 0;
  };
  return {
    date: typeof data.date === 'string' ? data.date : id,
    created_at: toMs(data.createdAt),
    updated_at: toMs(data.updatedAt),
  };
}

/**
 * Mark `isoDate` as a rest day. Idempotent — re-tapping the toggle on
 * a date that is already a rest day is a no-op. Uses `setDoc` with
 * `merge: true` so server timestamps populate on first write and
 * subsequent writes don't clear pre-existing fields.
 */
export async function markRestDay(uid: string, isoDate: string): Promise<void> {
  await setDoc(
    restDayDoc(uid, isoDate),
    {
      date: isoDate,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    },
    { merge: true },
  );
}

/**
 * Unmark `isoDate`. No-op if the row doesn't exist (deleteDoc is
 * idempotent for missing docs).
 */
export async function unmarkRestDay(
  uid: string,
  isoDate: string,
): Promise<void> {
  await deleteDoc(restDayDoc(uid, isoDate));
}

/** One-shot fetch of every rest-day ISO date the user has marked. */
export async function getAllRestDays(uid: string): Promise<Set<string>> {
  const snap = await getDocs(
    query(restDaysCol(uid), orderBy('__name__', 'desc')),
  );
  return new Set(snap.docs.map((d) => d.id));
}

/** One-shot "is `isoDate` a rest day?" check. */
export async function isRestDay(uid: string, isoDate: string): Promise<boolean> {
  const snap = await getDoc(restDayDoc(uid, isoDate));
  return snap.exists();
}

// ── Real-time listener ───────────────────────────────────────

/**
 * Subscribe to the user's rest-day collection. Wired from
 * `useFirestoreSync` so a rest day marked on Android surfaces on web
 * (streak fold + Today banner) without a refresh, and vice versa.
 *
 * Yields a `Set<string>` of ISO dates because every downstream consumer
 * (streak walk, banner) cares about set-membership, not metadata.
 * Doc IDs are ISO dates so `orderBy('__name__', 'desc')` keeps the
 * snapshot in a deterministic recent-first order.
 */
export function subscribeToRestDays(
  uid: string,
  callback: (dates: Set<string>) => void,
): Unsubscribe {
  const q = query(restDaysCol(uid), orderBy('__name__', 'desc'));
  return onSnapshot(q, (snap) => {
    callback(new Set(snap.docs.map((d) => d.id)));
  });
}

/**
 * Hydrate every rest-day row for the user, including createdAt /
 * updatedAt metadata. Currently unused — exposed for parity with the
 * `getRecent*` shape on neighbouring collections (check-in logs,
 * focus-release logs) so a future analytics surface can plot the
 * cadence without re-querying.
 */
export async function getAllRestDayRows(uid: string): Promise<RestDay[]> {
  const snap = await getDocs(
    query(restDaysCol(uid), orderBy('__name__', 'desc')),
  );
  return snap.docs.map((d) => docToRestDay(d.id, d.data()));
}
