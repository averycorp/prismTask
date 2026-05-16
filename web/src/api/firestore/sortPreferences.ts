import {
  doc,
  onSnapshot,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Real-time mirror of `users/{uid}/settings/sort_preferences` — the
 * flat keyed map of sort-mode / sort-direction preferences that Android
 * pushes from `SortPreferencesSyncService.pushNow`.
 *
 * Layout from Android: every key is a string, plus an `updated_at`
 * number. Per-project entries use a `sort_project_cloud_<cloudId>`
 * (mode) + `sort_direction_project_cloud_<cloudId>` (direction) key
 * pair, where `<cloudId>` is the Firestore doc id of the project.
 * Other keys are flat (`sort_today`, `sort_direction_today`, etc.).
 *
 * Document path note: the Android service writes to the `settings`
 * subcollection (`users/{uid}/settings/sort_preferences`), NOT
 * `prefs/`. The web spec mentioned `prefs/` but the running Android
 * code is canonical — see `SortPreferencesSyncService.kt:51`. Web
 * follows Android.
 *
 * Web today has no native sort surfaces wired to this collection; the
 * listener exists so cross-device sort preferences land in the cache
 * for downstream consumers (project list ordering parity work).
 */

export interface SortPreferencesSnapshot {
  /** Epoch ms — when the remote doc was last updated. 0 when missing. */
  updated_at: number;
  /** Flat map of preference key → string value. `updated_at` is
   *  extracted into its own field and not duplicated here. */
  preferences: Record<string, string>;
}

const EMPTY: SortPreferencesSnapshot = {
  updated_at: 0,
  preferences: {},
};

function sortPrefsDoc(uid: string) {
  return doc(firestore, 'users', uid, 'settings', 'sort_preferences');
}

function snapshotToPrefs(data: DocumentData | undefined): SortPreferencesSnapshot {
  if (!data) return EMPTY;
  const updatedAt =
    typeof data.updated_at === 'number' ? data.updated_at : 0;
  const preferences: Record<string, string> = {};
  for (const [key, value] of Object.entries(data)) {
    if (key === 'updated_at') continue;
    if (typeof value === 'string') preferences[key] = value;
  }
  return { updated_at: updatedAt, preferences };
}

export function subscribeToSortPreferences(
  uid: string,
  callback: (snapshot: SortPreferencesSnapshot) => void,
): Unsubscribe {
  return onSnapshot(sortPrefsDoc(uid), (snap) => {
    callback(snapshotToPrefs(snap.exists() ? snap.data() : undefined));
  });
}
