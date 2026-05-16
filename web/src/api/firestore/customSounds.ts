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
 * Read-only Firestore listener for `users/{uid}/custom_sounds`.
 *
 * Android writes via `SyncService.uploadRoomConfigFamily` →
 * `SyncMapper.customSoundToMap` (`CustomSoundEntity`). The `uri` field
 * is a per-device sandbox path (`file:///data/user/0/.../sounds/<file>`)
 * — syncing it lets sibling devices *see that a custom sound exists*
 * but the audio blob itself is not portable. Web treats this as a
 * metadata-only catalog for now; playback falls back to system default
 * if a user-defined sound is referenced from a notification profile.
 *
 * Parity Sync Sweep B unit (2 of 23). Write surface deferred.
 */
export interface CustomSound {
  /** Firestore doc id — authoritative identity on web. */
  cloud_id: string;
  /** Android Room rowid echo. */
  local_id: number | null;
  name: string;
  original_filename: string;
  /** Local sandbox URI on the device that authored the row. Not portable. */
  uri: string;
  /** One of `mp3` / `wav` / `m4a` / `ogg`. */
  format: string;
  size_bytes: number;
  duration_ms: number;
  created_at: number;
  updated_at: number;
}

function soundsCol(uid: string) {
  return collection(firestore, 'users', uid, 'custom_sounds');
}

function docToSound(id: string, data: DocumentData): CustomSound {
  return {
    cloud_id: id,
    local_id: typeof data.localId === 'number' ? data.localId : null,
    name: typeof data.name === 'string' ? data.name : '',
    original_filename:
      typeof data.originalFilename === 'string' ? data.originalFilename : '',
    uri: typeof data.uri === 'string' ? data.uri : '',
    format: typeof data.format === 'string' ? data.format : '',
    size_bytes: typeof data.sizeBytes === 'number' ? data.sizeBytes : 0,
    duration_ms: typeof data.durationMs === 'number' ? data.durationMs : 0,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

export async function getCustomSounds(uid: string): Promise<CustomSound[]> {
  const snap = await getDocs(
    query(soundsCol(uid), orderBy('createdAt', 'asc')),
  );
  return snap.docs.map((d) => docToSound(d.id, d.data()));
}

/**
 * Subscribe to the user's custom-sounds collection. Wired from
 * `useFirestoreSync` so a sound uploaded on Android shows up in any
 * web-side notification-profile editor that lists sound options.
 * Read-only — upload path is Android-only today.
 */
export function subscribeToCustomSounds(
  uid: string,
  callback: (sounds: CustomSound[]) => void,
): Unsubscribe {
  const q = query(soundsCol(uid), orderBy('createdAt', 'asc'));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToSound(d.id, d.data())));
  });
}
