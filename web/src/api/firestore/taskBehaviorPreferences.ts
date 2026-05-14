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
 * Start-of-Day hour preference, synced cross-device with Android.
 *
 * Mirrors Android's `DAY_START_HOUR` int preferences key in
 * `app/src/main/java/com/averycorp/prismtask/data/preferences/TaskBehaviorPreferences.kt`
 * (DataStore name `task_behavior_prefs`).
 *
 * Storage path matches Android's `GenericPreferenceSyncService` which
 * serializes the `task_behavior_prefs` DataStore to
 * `users/{uid}/prefs/task_behavior_prefs` (see
 * `app/src/main/java/com/averycorp/prismtask/di/PreferenceSyncModule.kt`).
 * The field name on the Firestore document is the raw DataStore key
 * (`day_start_hour`) and the sibling `__pref_types[<key>] = "int"` tag is
 * required so Android's pull side reconstructs a `Preferences.Key<Int>`
 * (see `PreferenceSyncSerialization.kt`). We merge=true on write so we
 * don't clobber other task-behavior keys Android may have already
 * written to the same doc (urgency weights, default sort, etc.).
 *
 * Default is `0` (midnight) — matches Android's default.
 *
 * Parity audit item A.5a — closes the cross-device hole where
 * `startOfDayHour` was localStorage-only on web.
 */

const DOC_NAME = 'task_behavior_prefs';
const FIELD_DAY_START_HOUR = 'day_start_hour';
const META_TYPES = '__pref_types';
const META_UPDATED_AT = '__pref_updated_at';

export const DEFAULT_DAY_START_HOUR = 0;

function prefsDoc(uid: string) {
  return doc(firestore, 'users', uid, 'prefs', DOC_NAME);
}

function readHour(data: DocumentData | undefined): number {
  if (!data) return DEFAULT_DAY_START_HOUR;
  const v = data[FIELD_DAY_START_HOUR];
  if (typeof v === 'number' && Number.isFinite(v)) {
    // Defensive: Android coerces hour into [0, 23] before write
    // (see TaskBehaviorPreferences.setDayStartHour). Re-clamp on the
    // read side so a malformed remote value can't crash callers that
    // assume the contract.
    const i = Math.trunc(v);
    if (i >= 0 && i <= 23) return i;
  }
  return DEFAULT_DAY_START_HOUR;
}

export async function getDayStartHour(uid: string): Promise<number> {
  const snap = await getDoc(prefsDoc(uid));
  return readHour(snap.exists() ? snap.data() : undefined);
}

export async function setDayStartHour(
  uid: string,
  hour: number,
): Promise<void> {
  const clamped = Math.max(0, Math.min(23, Math.trunc(hour)));
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_DAY_START_HOUR]: clamped,
      // Tag the type so Android's GenericPreferenceSyncService pull
      // side reconstructs the correct `Preferences.Key<Int>`. Merge=true
      // means we don't clobber type tags Android wrote for sibling
      // keys (urgency weights, default sort, …) in this same doc.
      [META_TYPES]: { [FIELD_DAY_START_HOUR]: 'int' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

export function subscribeToDayStartHour(
  uid: string,
  cb: (hour: number) => void,
): Unsubscribe {
  return onSnapshot(prefsDoc(uid), (snap) =>
    cb(readHour(snap.exists() ? snap.data() : undefined)),
  );
}
