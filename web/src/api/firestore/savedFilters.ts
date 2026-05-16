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
 * Read-only Firestore listener for `users/{uid}/saved_filters`.
 *
 * Android writes via `SyncService.uploadRoomConfigFamily` →
 * `SyncMapper.savedFilterToMap` (`SavedFilterEntity`). The filter
 * configuration is opaque JSON on the wire so future `TaskFilter`
 * fields round-trip without a schema change.
 *
 * Parity Sync Sweep B unit (2 of 23). Write surface deferred to the
 * saved-filters UI unit.
 */
export interface SavedFilter {
  /** Firestore doc id — authoritative identity on web. */
  cloud_id: string;
  /** Android Room rowid echo. */
  local_id: number | null;
  name: string;
  /** Gson-serialized `TaskFilter` blob. Parsed lazily by consumers. */
  filter_json: string;
  icon_emoji: string | null;
  sort_order: number;
  created_at: number;
  updated_at: number;
}

function filtersCol(uid: string) {
  return collection(firestore, 'users', uid, 'saved_filters');
}

function docToFilter(id: string, data: DocumentData): SavedFilter {
  return {
    cloud_id: id,
    local_id: typeof data.localId === 'number' ? data.localId : null,
    name: typeof data.name === 'string' ? data.name : '',
    filter_json: typeof data.filterJson === 'string' ? data.filterJson : '',
    icon_emoji: typeof data.iconEmoji === 'string' ? data.iconEmoji : null,
    sort_order: typeof data.sortOrder === 'number' ? data.sortOrder : 0,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

export async function getSavedFilters(uid: string): Promise<SavedFilter[]> {
  // Match Android's display order: `sort_order` ASC, then `created_at`
  // as a tiebreaker. The DAO uses the same compound order, so the
  // listener payload matches what Android shows in the filter chips.
  const snap = await getDocs(
    query(filtersCol(uid), orderBy('sortOrder', 'asc'), orderBy('createdAt', 'asc')),
  );
  return snap.docs.map((d) => docToFilter(d.id, d.data()));
}

/**
 * Subscribe to the user's saved-filter collection. Wired from
 * `useFirestoreSync` so a filter preset saved on Android surfaces in
 * the web filter-chip row without a refresh. Read-only.
 */
export function subscribeToSavedFilters(
  uid: string,
  callback: (filters: SavedFilter[]) => void,
): Unsubscribe {
  const q = query(
    filtersCol(uid),
    orderBy('sortOrder', 'asc'),
    orderBy('createdAt', 'asc'),
  );
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToFilter(d.id, d.data())));
  });
}
