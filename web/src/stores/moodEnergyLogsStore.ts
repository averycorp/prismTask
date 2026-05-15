import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToMoodLogs,
  type MoodEnergyLog,
} from '@/api/firestore/moodEnergyLogs';

/**
 * Live cache of the current user's mood/energy logs.
 *
 * Populated by the Firestore real-time listener wired from
 * `useFirestoreSync`. Existing consumers (`MoodScreen`,
 * `SelfCareNudgeCard`, `BoundaryTodayBanner`, mood-correlation utils)
 * fetch imperatively via `getLogsInRange(uid, ...)` today, which leaves
 * them stale across devices — a log added on Android won't surface on
 * the desktop banner until the page reloads. Closes parity audit § A.1b
 * residual for `mood_energy_logs`.
 *
 * Mutating writes (createLog / updateLog / deleteLog) still go through
 * the direct firestore helpers; the listener reconciles the local cache
 * from the resulting snapshot. Consumers that need a date-windowed
 * read can continue to call `getLogsInRange` directly — this store is
 * an additive cache, not a required dependency.
 */
interface MoodEnergyLogsState {
  logs: MoodEnergyLog[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToLogs: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useMoodEnergyLogsStore = create<MoodEnergyLogsState>((set) => ({
  logs: [],

  subscribeToLogs: (uid) => {
    return subscribeToMoodLogs(uid, (logs) => {
      set({ logs });
    });
  },

  reset: () => set({ logs: [] }),
}));
