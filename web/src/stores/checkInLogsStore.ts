import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToCheckIns,
  type CheckInLog,
} from '@/api/firestore/checkInLogs';

/**
 * Live cache of the current user's morning check-in logs.
 *
 * Populated by the Firestore real-time listener wired from
 * `useFirestoreSync`. `MorningCheckInCard.tsx` and streak-utility
 * consumers fetch imperatively via `getCheckIn` / `getRecentCheckIns`
 * today, so a check-in submitted on Android won't surface on the web
 * card until the page reloads. Closes parity audit § A.1b residual
 * for `check_in_logs`.
 *
 * Mutating writes (setCheckIn / clearCheckIn) still go through the
 * direct firestore helpers; the listener reconciles the local cache
 * from the resulting snapshot.
 */
interface CheckInLogsState {
  logs: CheckInLog[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToCheckIns: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useCheckInLogsStore = create<CheckInLogsState>((set) => ({
  logs: [],

  subscribeToCheckIns: (uid) => {
    return subscribeToCheckIns(uid, (logs) => {
      set({ logs });
    });
  },

  reset: () => set({ logs: [] }),
}));
