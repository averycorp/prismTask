import { create } from 'zustand';
import { doc, onSnapshot, type Unsubscribe } from 'firebase/firestore';
import {
  subscribeToNotificationProfiles,
  type NotificationProfile,
} from '@/api/firestore/notificationProfiles';
import { firestore } from '@/lib/firebase';
import { safeMergeDoc } from '@/lib/firestore/safeMergeDoc';

/**
 * Live cache of the current user's notification profiles.
 *
 * Originally landed read-only in Sync Sweep B (parity unit 2 of 23):
 * Android wrote via `SyncService.uploadRoomConfigFamily('notification_profiles')`
 * and web only mirrored. The Notifications Hub UI unit (this commit)
 * adds the web-side write surface (`updateProfile`) and the active-
 * profile selection (`activeProfile`, `setActiveProfile`) — both layered
 * additively on top of the read-only API so existing callers keep
 * working.
 *
 * Selector usage note: read individual fields off the store
 * (`s => s.profiles`) — Zustand v5 selectors that return fresh objects
 * trigger React #185 in production (see PR #1521 / memory note on
 * stable refs).
 */

/**
 * Path of the user-prefs doc that tracks which profile is currently
 * active for this user. We use the same `users/{uid}/prefs/<name>`
 * convention as `nd_prefs` / `a11y_prefs` so the prefs envelope rules
 * (LWW via `__pref_updated_at`) apply.
 *
 * Web stores `active_profile_cloud_id` (Firestore doc id) rather than
 * the Android Long rowid (`active_notification_profile_id`) because doc
 * ids are the only stable identifier shared between platforms. Android
 * has the rowid → cloud_id mapping locally; web has only the cloud id.
 * Keeping the field name distinct from Android's also means a future
 * sync mapper can translate explicitly rather than silently overwrite.
 */
const ACTIVE_PROFILE_PREFS_DOC = 'notification_prefs';
const ACTIVE_PROFILE_FIELD = 'active_profile_cloud_id';

interface NotificationProfilesState {
  profiles: NotificationProfile[];
  /** Cloud id of the currently-active profile, or null until set. */
  activeProfileCloudId: string | null;

  /** Wire Firestore real-time listener for the profile collection. */
  subscribeToProfiles: (uid: string) => Unsubscribe;

  /** Wire Firestore real-time listener for the active-profile pref. */
  subscribeToActiveProfile: (uid: string) => Unsubscribe;

  /**
   * Update the active-profile preference in Firestore. Optimistic: the
   * local store is updated immediately so the UI reflects the choice;
   * the snapshot listener reconciles if the write fails or races a
   * cross-device update.
   */
  setActiveProfile: (uid: string, cloudId: string) => Promise<void>;

  /**
   * Merge a patch into a notification profile. The patch keys must be
   * Firestore camelCase field names (mirrors `SyncMapper` shape), NOT
   * the snake_case TS view names — see `notificationProfiles.ts` for
   * the field map. Writes go through `safeMergeDoc` so concurrent
   * Android pushes can't be silently clobbered.
   */
  updateProfile: (
    uid: string,
    cloudId: string,
    patch: Record<string, unknown>,
  ) => Promise<void>;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useNotificationProfilesStore = create<NotificationProfilesState>(
  (set, get) => ({
    profiles: [],
    activeProfileCloudId: null,

    subscribeToProfiles: (uid) => {
      return subscribeToNotificationProfiles(uid, (profiles) => {
        set({ profiles });
      });
    },

    subscribeToActiveProfile: (uid) => {
      const ref = doc(firestore, 'users', uid, 'prefs', ACTIVE_PROFILE_PREFS_DOC);
      return onSnapshot(ref, (snap) => {
        if (!snap.exists()) {
          set({ activeProfileCloudId: null });
          return;
        }
        const data = snap.data();
        const raw = data?.[ACTIVE_PROFILE_FIELD];
        set({
          activeProfileCloudId: typeof raw === 'string' ? raw : null,
        });
      });
    },

    setActiveProfile: async (uid, cloudId) => {
      // Optimistic local update — listener reconciles either way.
      set({ activeProfileCloudId: cloudId });
      const ref = doc(
        firestore,
        'users',
        uid,
        'prefs',
        ACTIVE_PROFILE_PREFS_DOC,
      );
      // Prefs envelope: `__pref_updated_at` is the LWW field for this
      // doc family, matching how `nd_prefs` / `a11y_prefs` write.
      await safeMergeDoc(
        ref,
        {
          [ACTIVE_PROFILE_FIELD]: cloudId,
          __pref_types: { [ACTIVE_PROFILE_FIELD]: 'string' },
        },
        null,
        { timestampField: '__pref_updated_at' },
      );
    },

    updateProfile: async (uid, cloudId, patch) => {
      const existing = get().profiles.find((p) => p.cloud_id === cloudId);
      const expectedUpdatedAt = existing?.updated_at ?? null;
      const ref = doc(
        firestore,
        'users',
        uid,
        'notification_profiles',
        cloudId,
      );
      await safeMergeDoc(
        ref,
        patch,
        // `null` if we've never seen the doc or it has no timestamp,
        // else the value we last read. safeMergeDoc treats `null` as
        // create-or-overwrite, which is correct for first-touch.
        expectedUpdatedAt && expectedUpdatedAt > 0 ? expectedUpdatedAt : null,
      );
    },

    reset: () => set({ profiles: [], activeProfileCloudId: null }),
  }),
);
