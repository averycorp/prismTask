import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  createLog as firestoreCreateLog,
  deleteLog as firestoreDeleteLog,
  subscribeToHabitLogs,
  type HabitLog,
  type HabitLogInput,
} from '@/api/firestore/habitLogs';
import { getFirebaseUid } from '@/stores/firebaseUid';

/**
 * Web mirror of Android's `HabitLogDao` / `HabitRepository.logActivity`
 * surface. Holds every habit_log under the current user keyed by
 * `habit_id` so per-habit detail screens can render history without
 * an extra round-trip.
 *
 * Parity audit § B.3b — booking dialog + history view share this store.
 */
interface HabitLogState {
  /** habit_id (cloud id) → logs newest-first. */
  logsByHabit: Record<string, HabitLog[]>;
  isLoading: boolean;
  error: string | null;

  subscribeToLogs: (uid: string) => Unsubscribe;

  logActivity: (input: HabitLogInput) => Promise<HabitLog>;
  deleteLog: (logId: string) => Promise<void>;

  getLogsFor: (habitId: string) => HabitLog[];
  getLastLogDate: (habitId: string) => number | null;
  getLogCount: (habitId: string) => number;

  reset: () => void;
}

function groupByHabit(logs: HabitLog[]): Record<string, HabitLog[]> {
  const grouped: Record<string, HabitLog[]> = {};
  for (const log of logs) {
    if (!grouped[log.habit_id]) grouped[log.habit_id] = [];
    grouped[log.habit_id].push(log);
  }
  // Newest-first within each habit — matches Android's
  // `getLogsForHabit ORDER BY date DESC`.
  for (const id of Object.keys(grouped)) {
    grouped[id].sort((a, b) => b.date - a.date);
  }
  return grouped;
}

export const useHabitLogStore = create<HabitLogState>((set, get) => ({
  logsByHabit: {},
  isLoading: false,
  error: null,

  subscribeToLogs: (uid: string) =>
    subscribeToHabitLogs(uid, (logs) => {
      set({ logsByHabit: groupByHabit(logs) });
    }),

  logActivity: async (input) => {
    const uid = getFirebaseUid();
    // Optimistic insert — listener will reconcile with the canonical
    // doc-id once Firestore round-trips.
    const optimistic: HabitLog = {
      id: `temp_${Date.now()}`,
      habit_id: input.habit_id,
      date: input.date,
      notes:
        input.notes != null && input.notes.trim().length > 0
          ? input.notes.trim()
          : null,
      created_at: Date.now(),
    };
    set((state) => ({
      logsByHabit: {
        ...state.logsByHabit,
        [input.habit_id]: [
          optimistic,
          ...(state.logsByHabit[input.habit_id] ?? []),
        ],
      },
    }));
    try {
      const created = await firestoreCreateLog(uid, input);
      set((state) => ({
        logsByHabit: {
          ...state.logsByHabit,
          [input.habit_id]: [
            created,
            ...(state.logsByHabit[input.habit_id] ?? []).filter(
              (l) => l.id !== optimistic.id,
            ),
          ].sort((a, b) => b.date - a.date),
        },
      }));
      return created;
    } catch (e) {
      // Roll back the optimistic insert so we don't strand a temp id.
      set((state) => ({
        logsByHabit: {
          ...state.logsByHabit,
          [input.habit_id]: (state.logsByHabit[input.habit_id] ?? []).filter(
            (l) => l.id !== optimistic.id,
          ),
        },
        error: (e as Error).message,
      }));
      throw e;
    }
  },

  deleteLog: async (logId) => {
    const uid = getFirebaseUid();
    const previous = get().logsByHabit;
    // Optimistic remove.
    set((state) => {
      const next: Record<string, HabitLog[]> = {};
      for (const [habitId, logs] of Object.entries(state.logsByHabit)) {
        next[habitId] = logs.filter((l) => l.id !== logId);
      }
      return { logsByHabit: next };
    });
    try {
      await firestoreDeleteLog(uid, logId);
    } catch (e) {
      // Restore on failure.
      set({ logsByHabit: previous, error: (e as Error).message });
      throw e;
    }
  },

  getLogsFor: (habitId) => get().logsByHabit[habitId] ?? [],

  getLastLogDate: (habitId) => {
    const logs = get().logsByHabit[habitId];
    if (!logs || logs.length === 0) return null;
    return logs[0].date;
  },

  getLogCount: (habitId) => (get().logsByHabit[habitId] ?? []).length,

  reset: () => set({ logsByHabit: {}, error: null, isLoading: false }),
}));
