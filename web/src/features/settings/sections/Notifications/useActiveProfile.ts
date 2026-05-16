import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import type { NotificationProfile } from '@/api/firestore/notificationProfiles';

/**
 * Resolves the currently-active notification profile from the store.
 * Falls back to the first profile when the active-profile pref hasn't
 * been written yet (Android always seeds a built-in starter set, so the
 * first profile is a safe fallback). Returns `null` only when the user
 * has no profiles at all.
 *
 * Selectors are split so each subscription pulls a stable scalar/
 * reference off the store — fresh-object selectors trigger React #185
 * (memory: `feedback-zustand-selector-must-return-stable-ref`).
 */
export function useActiveProfile(): NotificationProfile | null {
  const profiles = useNotificationProfilesStore((s) => s.profiles);
  const activeProfileCloudId = useNotificationProfilesStore(
    (s) => s.activeProfileCloudId,
  );
  return (
    profiles.find((p) => p.cloud_id === activeProfileCloudId) ??
    profiles[0] ??
    null
  );
}
