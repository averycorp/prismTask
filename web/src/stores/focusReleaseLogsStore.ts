import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToFocusLogs,
  type FocusReleaseLog,
} from '@/api/firestore/focusReleaseLogs';

/**
 * Live cache of the current user's focus-release logs.
 *
 * Populated by the Firestore real-time listener wired from
 * `useFirestoreSync`. `FocusReleaseScreen` and analytics consumers
 * fetch imperatively via `getRecentLogs` today, so a session shipped
 * on Android won't surface on the web history until the page reloads.
 * Closes parity audit § A.1b residual for `focus_release_logs`.
 *
 * Mutating writes (createLog) still go through the direct firestore
 * helper; the listener reconciles the local cache from the resulting
 * snapshot.
 */
interface FocusReleaseLogsState {
  logs: FocusReleaseLog[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToFocusLogs: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useFocusReleaseLogsStore = create<FocusReleaseLogsState>((set) => ({
  logs: [],

  subscribeToFocusLogs: (uid) => {
    return subscribeToFocusLogs(uid, (logs) => {
      set({ logs });
    });
  },

  reset: () => set({ logs: [] }),
}));
