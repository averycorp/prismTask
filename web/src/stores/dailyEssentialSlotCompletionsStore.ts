import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToDailyEssentialSlotCompletions,
  type DailyEssentialSlotCompletion,
} from '@/api/firestore/dailyEssentialSlotCompletions';

/**
 * Live cache of the current user's `daily_essential_slot_completions`
 * rows.
 *
 * Wired by `useFirestoreSync`. As of parity Batch 5 PR-9 the canonical
 * write path is `BackendSyncService` → Postgres; older Android clients
 * still push rows to this Firestore collection though, and the cache
 * keeps web in sync with both writers. Once every Android client has
 * upgraded past PR-9 the collection becomes effectively frozen — the
 * listener is still useful for legacy rows and read-only history.
 */
interface DailyEssentialSlotCompletionsState {
  completions: DailyEssentialSlotCompletion[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToCompletions: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useDailyEssentialSlotCompletionsStore = create<
  DailyEssentialSlotCompletionsState
>((set) => ({
  completions: [],

  subscribeToCompletions: (uid) =>
    subscribeToDailyEssentialSlotCompletions(uid, (completions) => {
      set({ completions });
    }),

  reset: () => set({ completions: [] }),
}));
