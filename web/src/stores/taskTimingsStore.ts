import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  addTaskTiming,
  subscribeToTaskTimings,
  type AddTaskTimingInput,
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
 * Web write parity (audit item 7): `logTiming` appends a row to
 * Firestore + does an optimistic local update so the bar chart reacts
 * immediately. Used by Pomodoro session-complete; manual "Log time" UI
 * on web is a separate follow-up that will reuse this action.
 */
interface TaskTimingsState {
  timings: TaskTiming[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToTaskTimings: (uid: string) => Unsubscribe;

  /**
   * Append a `task_timing` row. Performs an optimistic local insert
   * keyed on `temp_<createdAt>` so the analytics chart updates without
   * waiting for the Firestore snapshot. The real-time listener swaps
   * the optimistic row for the canonical Firestore doc once the write
   * lands. On write failure the optimistic row is rolled back and the
   * error is re-thrown so callers can decide UX policy (Pomodoro logs
   * are best-effort and swallow the error).
   */
  logTiming: (uid: string, input: AddTaskTimingInput) => Promise<TaskTiming>;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useTaskTimingsStore = create<TaskTimingsState>((set, get) => ({
  timings: [],

  subscribeToTaskTimings: (uid) =>
    subscribeToTaskTimings(uid, (timings) => {
      set({ timings });
    }),

  logTiming: async (uid, input) => {
    const createdAt = input.createdAt ?? Date.now();
    const tempId = `temp_${createdAt}_${Math.random().toString(36).slice(2, 10)}`;
    const optimistic: TaskTiming = {
      id: tempId,
      task_id: input.taskCloudId,
      started_at: input.startedAt ?? null,
      ended_at: input.endedAt ?? null,
      duration_minutes: input.durationMinutes,
      source: input.source ?? 'manual',
      notes: input.notes ?? null,
      created_at: createdAt,
    };
    set({ timings: [optimistic, ...get().timings] });
    try {
      const saved = await addTaskTiming(uid, { ...input, createdAt });
      // Replace the optimistic row with the canonical Firestore one.
      // The real-time listener will also fire, but settling state here
      // first keeps optimistic ordering stable until the snapshot lands.
      set({
        timings: get().timings.map((t) => (t.id === tempId ? saved : t)),
      });
      return saved;
    } catch (err) {
      // Roll back the optimistic row so the chart doesn't show a
      // phantom entry that never lands in Firestore.
      set({ timings: get().timings.filter((t) => t.id !== tempId) });
      throw err;
    }
  },

  reset: () => set({ timings: [] }),
}));
