import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToSavedFilters,
  type SavedFilter,
} from '@/api/firestore/savedFilters';

/**
 * Live cache of the current user's saved filter presets.
 *
 * Read-only on web in this unit (Sync Sweep B, 2 of 23). Android writes
 * via `SyncService.uploadRoomConfigFamily('saved_filters')`. The
 * `filter_json` blob is opaque on the wire — UI consumers parse it
 * lazily when applying a preset.
 *
 * Selector usage note: read individual fields off the store
 * (`s => s.filters`) — fresh-object selectors trigger React #185.
 */
interface SavedFiltersState {
  filters: SavedFilter[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToFilters: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useSavedFiltersStore = create<SavedFiltersState>((set) => ({
  filters: [],

  subscribeToFilters: (uid) => {
    return subscribeToSavedFilters(uid, (filters) => {
      set({ filters });
    });
  },

  reset: () => set({ filters: [] }),
}));
