import {
  doc,
  getDoc,
  onSnapshot,
  setDoc,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Active-notification-profile preference, synced cross-device with
 * Android.
 *
 * Mirrors Android's `ACTIVE_PROFILE_ID` longPreferencesKey in
 * `app/src/main/java/com/averycorp/prismtask/data/preferences/NotificationPreferences.kt`
 * (DataStore name `notification_prefs`, key
 * `active_notification_profile_id`).
 *
 * Storage path matches Android's `GenericPreferenceSyncService` which
 * serializes the `notification_prefs` DataStore to
 * `users/{uid}/prefs/notification_prefs`. The `__pref_types` sibling
 * tag is required so Android's pull side reconstructs a
 * `Preferences.Key<Long>` (see `PreferenceSyncSerialization.kt`).
 *
 * Default is `-1` (no active profile selected; Android falls back to
 * the built-in default profile).
 *
 * Backing parity unit 20 of 23 (Notification profiles UI).
 */

const DOC_NAME = 'notification_prefs';
const FIELD_ACTIVE_PROFILE_ID = 'active_notification_profile_id';
const META_TYPES = '__pref_types';
const META_UPDATED_AT = '__pref_updated_at';

export const DEFAULT_ACTIVE_PROFILE_ID = -1;

function prefsDoc(uid: string) {
  return doc(firestore, 'users', uid, 'prefs', DOC_NAME);
}

function readActiveProfileId(data: DocumentData | undefined): number {
  if (!data) return DEFAULT_ACTIVE_PROFILE_ID;
  const v = data[FIELD_ACTIVE_PROFILE_ID];
  if (typeof v === 'number' && Number.isFinite(v)) return Math.trunc(v);
  return DEFAULT_ACTIVE_PROFILE_ID;
}

export async function getActiveProfileId(uid: string): Promise<number> {
  const snap = await getDoc(prefsDoc(uid));
  return readActiveProfileId(snap.exists() ? snap.data() : undefined);
}

export async function setActiveProfileId(
  uid: string,
  profileLocalId: number,
): Promise<void> {
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_ACTIVE_PROFILE_ID]: Math.trunc(profileLocalId),
      [META_TYPES]: { [FIELD_ACTIVE_PROFILE_ID]: 'long' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

export function subscribeToActiveProfileId(
  uid: string,
  cb: (id: number) => void,
): Unsubscribe {
  return onSnapshot(prefsDoc(uid), (snap) =>
    cb(readActiveProfileId(snap.exists() ? snap.data() : undefined)),
  );
}
