import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToSelfCareLogs,
  subscribeToSelfCareSteps,
  toggleStep as firestoreToggleStep,
  type SelfCareLog,
  type SelfCareStep,
} from '@/api/firestore/selfCare';
import { getFirebaseUid } from '@/stores/firebaseUid';

interface SelfCareState {
  logs: SelfCareLog[];
  steps: SelfCareStep[];
  isLoading: boolean;
  error: string | null;

  subscribeToLogs: (uid: string) => Unsubscribe;
  subscribeToSteps: (uid: string) => Unsubscribe;

  /** Find today's log row for the given routine, where "today" is the
   *  caller-supplied logical-day epoch-ms (already aligned to
   *  `startOfDayHour`). Mirrors `SelfCareDao.getLogForDate`. */
  getTodayLog: (routineType: string, todayMs: number) => SelfCareLog | null;
  toggleStep: (
    routineType: string,
    todayMs: number,
    stepId: string,
  ) => Promise<void>;
  reset: () => void;
}

export const useSelfCareStore = create<SelfCareState>((set, get) => ({
  logs: [],
  steps: [],
  isLoading: false,
  error: null,

  subscribeToLogs: (uid: string) =>
    subscribeToSelfCareLogs(uid, (logs) => set({ logs })),

  subscribeToSteps: (uid: string) =>
    subscribeToSelfCareSteps(uid, (steps) => set({ steps })),

  getTodayLog: (routineType, todayMs) => {
    return (
      get().logs.find(
        (l) => l.routine_type === routineType && l.date === todayMs,
      ) ?? null
    );
  },

  toggleStep: async (routineType, todayMs, stepId) => {
    try {
      const uid = getFirebaseUid();
      const existing = get().getTodayLog(routineType, todayMs);
      await firestoreToggleStep(uid, routineType, todayMs, stepId, existing);
      // The onSnapshot listener mirrors the write back into local state,
      // so no manual set() needed here.
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },

  reset: () => set({ logs: [], steps: [], error: null }),
}));
