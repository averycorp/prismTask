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
 * Accessibility preferences synced cross-device with Android.
 *
 * Mirrors Android's `A11yPreferences` DataStore (`a11y_prefs`) which
 * syncs through `GenericPreferenceSyncService` at
 * `users/{uid}/prefs/a11y_prefs`. The generic shape uses
 * `__pref_types` type tags so Android's pull side reconstructs the
 * correct `Preferences.Key<Boolean>`.
 *
 * Fields synced from web:
 *  - `reduce_motion` (bool)  — maps to web `useA11yStore.reducedMotion`
 *  - `high_contrast` (bool)  — maps to web `useA11yStore.highContrast`
 *
 * Field intentionally NOT synced from web:
 *  - `large_touch_targets` — Android-only knob (web has no equivalent
 *    surface). Web pull side reads it via the snapshot but doesn't act
 *    on it; web writes never touch the key so an Android-side value
 *    survives. Mirrors `taskBehaviorPreferences.ts`'s "web only owns a
 *    subset" pattern.
 *
 * The `__pref_types` map must list every field web actually writes —
 * Android's `PreferenceSyncSerialization.applyRemote` ignores keys
 * that aren't in the types map. Merge=true preserves any Android-
 * authored type tags for `large_touch_targets`.
 *
 * Parity audit A.5b (a11y slice).
 */

const DOC_NAME = 'a11y_prefs';
const FIELD_REDUCE_MOTION = 'reduce_motion';
const FIELD_HIGH_CONTRAST = 'high_contrast';
const META_TYPES = '__pref_types';
const META_UPDATED_AT = '__pref_updated_at';

export const DEFAULT_REDUCE_MOTION = false;
export const DEFAULT_HIGH_CONTRAST = false;

export interface A11yPreferencesSnapshot {
  reduceMotion: boolean;
  highContrast: boolean;
}

function prefsDoc(uid: string) {
  return doc(firestore, 'users', uid, 'prefs', DOC_NAME);
}

function readSnapshot(data: DocumentData | undefined): A11yPreferencesSnapshot {
  if (!data) {
    return {
      reduceMotion: DEFAULT_REDUCE_MOTION,
      highContrast: DEFAULT_HIGH_CONTRAST,
    };
  }
  return {
    reduceMotion:
      typeof data[FIELD_REDUCE_MOTION] === 'boolean'
        ? data[FIELD_REDUCE_MOTION]
        : DEFAULT_REDUCE_MOTION,
    highContrast:
      typeof data[FIELD_HIGH_CONTRAST] === 'boolean'
        ? data[FIELD_HIGH_CONTRAST]
        : DEFAULT_HIGH_CONTRAST,
  };
}

export async function getA11yPreferences(
  uid: string,
): Promise<A11yPreferencesSnapshot> {
  const snap = await getDoc(prefsDoc(uid));
  return readSnapshot(snap.exists() ? snap.data() : undefined);
}

export async function setReduceMotion(
  uid: string,
  enabled: boolean,
): Promise<void> {
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_REDUCE_MOTION]: enabled,
      // Tag the type so Android's GenericPreferenceSyncService pull
      // side reconstructs `Preferences.Key<Boolean>`. Merge=true so
      // any Android-only type tags (e.g. `large_touch_targets: 'bool'`)
      // survive.
      [META_TYPES]: { [FIELD_REDUCE_MOTION]: 'bool' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

export async function setHighContrast(
  uid: string,
  enabled: boolean,
): Promise<void> {
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_HIGH_CONTRAST]: enabled,
      [META_TYPES]: { [FIELD_HIGH_CONTRAST]: 'bool' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

export function subscribeToA11yPreferences(
  uid: string,
  cb: (snapshot: A11yPreferencesSnapshot) => void,
): Unsubscribe {
  return onSnapshot(prefsDoc(uid), (snap) => {
    cb(readSnapshot(snap.exists() ? snap.data() : undefined));
  });
}
