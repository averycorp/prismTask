import {
  collection,
  onSnapshot,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Real-time mirror of `users/{uid}/daily_essential_slot_completions` —
 * the materialised per-day slot completion rows that older Android
 * clients write via `SyncMapper.dailyEssentialSlotCompletionToMap`.
 *
 * Status (parity Batch 5 PR-9, decision D-E4): post-PR-9 Android no
 * longer pushes new rows to this Firestore collection — the
 * `BackendSyncService` path mirrors into Room directly. The collection
 * still receives writes from pre-PR-9 Android clients, and the existing
 * Firestore rows are not deleted. Wiring this listener lets web read
 * the legacy + still-arriving rows without a manual refresh, which
 * powers cross-device Daily Essentials completion visibility for users
 * who haven't updated their Android client yet.
 *
 * `med_ids_json` is a JSON-encoded array of synthetic dose keys (e.g.
 * `"specific_time:lipitor"`) that survive medication renames — no FK
 * translation needed at this layer; consumers parse / lookup keys
 * directly.
 */

export interface DailyEssentialSlotCompletion {
  /** Firestore doc id (== Android `cloud_id`). */
  id: string;
  /** Epoch ms of the local logical day (Android's `DayBoundary` resolves). */
  date: number;
  /** Wall-clock "HH:mm" or the literal "anytime" for interval doses. */
  slot_key: string;
  /** JSON-encoded array of synthetic dose keys. Default "[]". */
  med_ids_json: string;
  /** Epoch ms the user marked the slot taken. Null while unchecked. */
  taken_at: number | null;
  created_at: number;
  updated_at: number;
}

function completionsCol(uid: string) {
  return collection(firestore, 'users', uid, 'daily_essential_slot_completions');
}

function docToCompletion(
  docId: string,
  data: DocumentData,
): DailyEssentialSlotCompletion {
  return {
    id: docId,
    date: typeof data.date === 'number' ? data.date : 0,
    slot_key: typeof data.slotKey === 'string' ? data.slotKey : '',
    med_ids_json:
      typeof data.medIdsJson === 'string' && data.medIdsJson.length > 0
        ? data.medIdsJson
        : '[]',
    taken_at: typeof data.takenAt === 'number' ? data.takenAt : null,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : 0,
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

export function subscribeToDailyEssentialSlotCompletions(
  uid: string,
  callback: (rows: DailyEssentialSlotCompletion[]) => void,
): Unsubscribe {
  return onSnapshot(completionsCol(uid), (snap) => {
    callback(snap.docs.map((d) => docToCompletion(d.id, d.data())));
  });
}
