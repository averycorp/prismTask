import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToNotificationProfiles,
  type NotificationProfile,
} from '@/api/firestore/notificationProfiles';

/**
 * Live cache of the current user's notification profiles.
 *
 * Read-only on web in this unit (Sync Sweep B, 2 of 23): Android writes
 * via `SyncService.uploadRoomConfigFamily('notification_profiles')` and
 * web mirrors the snapshot into Zustand for downstream UI units to
 * read. The notification-profiles UI unit will land the create / update
 * / delete surface separately.
 *
 * Selector usage note: read individual fields off the store
 * (`s => s.profiles`) — Zustand v5 selectors that return fresh objects
 * trigger React #185 in production (see PR #1521 / memory note on
 * stable refs).
 */
interface NotificationProfilesState {
  profiles: NotificationProfile[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToProfiles: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useNotificationProfilesStore = create<NotificationProfilesState>(
  (set) => ({
    profiles: [],

    subscribeToProfiles: (uid) => {
      return subscribeToNotificationProfiles(uid, (profiles) => {
        set({ profiles });
      });
    },

    reset: () => set({ profiles: [] }),
  }),
);
