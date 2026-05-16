import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToNlpShortcuts,
  type NlpShortcut,
} from '@/api/firestore/nlpShortcuts';

/**
 * Live cache of the current user's NLP quick-add shortcuts.
 *
 * Read-only on web in this unit (Sync Sweep B, 2 of 23). Android writes
 * via `SyncService.uploadRoomConfigFamily('nlp_shortcuts')`. Downstream
 * UI consumes the cached shortcuts when expanding text in the
 * quick-add bar before any other NLP parsing.
 *
 * Selector usage note: read individual fields off the store
 * (`s => s.shortcuts`) — fresh-object selectors trigger React #185.
 */
interface NlpShortcutsState {
  shortcuts: NlpShortcut[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToShortcuts: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useNlpShortcutsStore = create<NlpShortcutsState>((set) => ({
  shortcuts: [],

  subscribeToShortcuts: (uid) => {
    return subscribeToNlpShortcuts(uid, (shortcuts) => {
      set({ shortcuts });
    });
  },

  reset: () => set({ shortcuts: [] }),
}));
