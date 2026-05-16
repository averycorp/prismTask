import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToSortPreferences,
  type SortPreferencesSnapshot,
} from '@/api/firestore/sortPreferences';

/**
 * Live cache of the current user's sort preferences doc — Android's
 * `users/{uid}/settings/sort_preferences` flat keyed map.
 *
 * Per-project entries use the
 * `sort_project_cloud_<cloudId>` / `sort_direction_project_cloud_<cloudId>`
 * key shape so consumers translate the cloud id (== Firestore project
 * doc id) back to a UI project before reading the value. Other keys
 * are flat (`sort_today`, `sort_direction_today`, etc.) and read
 * verbatim.
 *
 * `updated_at` is broken out so an LWW consumer can drop stale local
 * reads without scanning the whole map.
 *
 * Web's existing sort surfaces are local-only today; this listener
 * primes the cache so cross-device sort prefs can be applied once a
 * consumer surface picks them up.
 */
interface SortPreferencesState {
  /** Last snapshot from Firestore — empty map until first emit. */
  snapshot: SortPreferencesSnapshot;

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToSortPreferences: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

const EMPTY: SortPreferencesSnapshot = {
  updated_at: 0,
  preferences: {},
};

export const useSortPreferencesStore = create<SortPreferencesState>((set) => ({
  snapshot: EMPTY,

  subscribeToSortPreferences: (uid) =>
    subscribeToSortPreferences(uid, (snapshot) => {
      set({ snapshot });
    }),

  reset: () => set({ snapshot: EMPTY }),
}));
