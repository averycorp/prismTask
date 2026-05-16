import {
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
 * Read-only Firestore listener for `users/{uid}/nlp_shortcuts`.
 *
 * Android writes via `SyncService.uploadRoomConfigFamily` →
 * `SyncMapper.nlpShortcutToMap` (`NlpShortcutEntity`). Each row is a
 * user-defined trigger → expansion pair the quick-add bar substitutes
 * in before any other NLP parsing runs.
 *
 * Parity Sync Sweep B unit (2 of 23). Write surface deferred.
 */
export interface NlpShortcut {
  /** Firestore doc id — authoritative identity on web. */
  cloud_id: string;
  /** Android Room rowid echo. */
  local_id: number | null;
  trigger: string;
  expansion: string;
  sort_order: number;
  created_at: number;
  updated_at: number;
}

function shortcutsCol(uid: string) {
  return collection(firestore, 'users', uid, 'nlp_shortcuts');
}

function docToShortcut(id: string, data: DocumentData): NlpShortcut {
  return {
    cloud_id: id,
    local_id: typeof data.localId === 'number' ? data.localId : null,
    trigger: typeof data.trigger === 'string' ? data.trigger : '',
    expansion: typeof data.expansion === 'string' ? data.expansion : '',
    sort_order: typeof data.sortOrder === 'number' ? data.sortOrder : 0,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

export async function getNlpShortcuts(uid: string): Promise<NlpShortcut[]> {
  const snap = await getDocs(
    query(
      shortcutsCol(uid),
      orderBy('sortOrder', 'asc'),
      orderBy('createdAt', 'asc'),
    ),
  );
  return snap.docs.map((d) => docToShortcut(d.id, d.data()));
}

/**
 * Subscribe to the user's nlp-shortcut collection. Wired from
 * `useFirestoreSync` so a trigger added on Android works in web
 * quick-add immediately. Read-only.
 */
export function subscribeToNlpShortcuts(
  uid: string,
  callback: (shortcuts: NlpShortcut[]) => void,
): Unsubscribe {
  const q = query(
    shortcutsCol(uid),
    orderBy('sortOrder', 'asc'),
    orderBy('createdAt', 'asc'),
  );
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToShortcut(d.id, d.data())));
  });
}
