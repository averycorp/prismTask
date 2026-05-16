import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToTaskTimings,
  type TaskTiming,
} from '@/api/firestore/taskTimings';

/**
 * Live cache of the current user's `task_timings` rows.
 *
 * Wired by `useFirestoreSync` so cross-device Pomodoro / manual log /
 * timer entries land in the analytics surface without a manual refresh.
 * Closes the 5th missing Firestore listener flagged in v1.9 web parity
 * notes — `task_timings` powers the productivity-score chart + per-task
 * time-tracking bar chart on `TaskAnalyticsScreen` (Pro-gated).
 *
 * The listener is read-only on web today: Pomodoro / manual log writes
 * still go through the Android client. Web write parity is a separate
 * follow-up; this store unblocks the read side first.
 */
interface TaskTimingsState {
  timings: TaskTiming[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToTaskTimings: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useTaskTimingsStore = create<TaskTimingsState>((set) => ({
  timings: [],

  subscribeToTaskTimings: (uid) =>
    subscribeToTaskTimings(uid, (timings) => {
      set({ timings });
    }),

  reset: () => set({ timings: [] }),
}));
