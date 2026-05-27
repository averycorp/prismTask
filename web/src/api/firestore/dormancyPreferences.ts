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
 * Global dormancy threshold (days), synced cross-device with Android.
 *
 * Mirrors Android's `KEY_DORMANCY_THRESHOLD_DAYS` int preferences key in
 * `UserPreferencesDataStore` (DataStore name `user_prefs`), which Android's
 * `GenericPreferenceSyncService` serializes to `users/{uid}/prefs/user_prefs`.
 * The Firestore field name is the raw DataStore key (`dormancy_threshold_days`)
 * and the sibling `__pref_types[<key>] = "int"` tag is required so Android's
 * pull side reconstructs a `Preferences.Key<Int>`. We merge=true so we don't
 * clobber other `user_prefs` keys (AI flags, urgency weights, etc.).
 *
 * Default 7, clamped to [1, 90] — matches Android's
 * `UserPreferencesDataStore.DEFAULT/MIN/MAX_DORMANCY_THRESHOLD_DAYS`.
 *
 * Dormancy Re-Entry (v1.9.x), Checkpoint 6 — web read/write parity for the
 * global threshold.
 */

const DOC_NAME = 'user_prefs';
const FIELD_DORMANCY_THRESHOLD = 'dormancy_threshold_days';
const META_TYPES = '__pref_types';
const META_UPDATED_AT = '__pref_updated_at';

export const DEFAULT_DORMANCY_THRESHOLD_DAYS = 7;
export const MIN_DORMANCY_THRESHOLD_DAYS = 1;
export const MAX_DORMANCY_THRESHOLD_DAYS = 90;

function prefsDoc(uid: string) {
  return doc(firestore, 'users', uid, 'prefs', DOC_NAME);
}

function readThreshold(data: DocumentData | undefined): number {
  if (!data) return DEFAULT_DORMANCY_THRESHOLD_DAYS;
  const v = data[FIELD_DORMANCY_THRESHOLD];
  if (typeof v === 'number' && Number.isFinite(v)) {
    const i = Math.trunc(v);
    if (i >= MIN_DORMANCY_THRESHOLD_DAYS && i <= MAX_DORMANCY_THRESHOLD_DAYS) return i;
  }
  return DEFAULT_DORMANCY_THRESHOLD_DAYS;
}

export async function getDormancyThresholdDays(uid: string): Promise<number> {
  const snap = await getDoc(prefsDoc(uid));
  return readThreshold(snap.exists() ? snap.data() : undefined);
}

export async function setDormancyThresholdDays(
  uid: string,
  days: number,
): Promise<void> {
  const clamped = Math.max(
    MIN_DORMANCY_THRESHOLD_DAYS,
    Math.min(MAX_DORMANCY_THRESHOLD_DAYS, Math.trunc(days)),
  );
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_DORMANCY_THRESHOLD]: clamped,
      [META_TYPES]: { [FIELD_DORMANCY_THRESHOLD]: 'int' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

export function subscribeToDormancyThresholdDays(
  uid: string,
  cb: (days: number) => void,
): Unsubscribe {
  return onSnapshot(prefsDoc(uid), (snap) =>
    cb(readThreshold(snap.exists() ? snap.data() : undefined)),
  );
}
