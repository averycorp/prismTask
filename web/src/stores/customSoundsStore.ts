import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToCustomSounds,
  type CustomSound,
} from '@/api/firestore/customSounds';

/**
 * Live cache of the current user's custom notification sounds.
 *
 * Read-only on web in this unit (Sync Sweep B, 2 of 23). Android uploads
 * the metadata via `SyncService.uploadRoomConfigFamily('custom_sounds')`
 * — the audio blob itself stays on the authoring device, so web treats
 * this as a metadata-only catalog.
 *
 * Selector usage note: read individual fields off the store
 * (`s => s.sounds`) — fresh-object selectors trigger React #185.
 */
interface CustomSoundsState {
  sounds: CustomSound[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToSounds: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useCustomSoundsStore = create<CustomSoundsState>((set) => ({
  sounds: [],

  subscribeToSounds: (uid) => {
    return subscribeToCustomSounds(uid, (sounds) => {
      set({ sounds });
    });
  },

  reset: () => set({ sounds: [] }),
}));
