import { Check } from 'lucide-react';
import { toast } from 'sonner';
import {
  useNotificationProfilesStore,
} from '@/stores/notificationProfilesStore';
import { setActiveProfileId } from '@/api/firestore/notificationPreferences';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { URGENCY_TIERS } from './notificationCatalog';
import type { NotificationProfile } from '@/api/firestore/notificationProfiles';

/**
 * Profiles list with an "Active" badge + radio-style selector. The
 * active profile is written to `users/{uid}/prefs/notification_prefs`
 * (key `active_notification_profile_id`) so Android picks it up via
 * `GenericPreferenceSyncService`.
 *
 * Create / rename / delete of profiles is Android-only — the web list
 * mirrors what Android has pushed.
 */
export function ProfilesScreen() {
  // Stable-ref selectors (Zustand v5): primitive + array refs only.
  const profiles = useNotificationProfilesStore((s) => s.profiles);
  const activeProfileLocalId = useNotificationProfilesStore(
    (s) => s.activeProfileLocalId,
  );
  const initialized = useNotificationProfilesStore((s) => s.initialized);

  const handleSelect = async (profile: NotificationProfile) => {
    if (profile.localId == null) {
      toast.error('Profile is missing a local id — wait for sync to settle.');
      return;
    }
    let uid: string | null = null;
    try {
      uid = getFirebaseUid();
    } catch {
      uid = null;
    }
    if (!uid) {
      toast.error('Sign in to change the active profile.');
      return;
    }
    try {
      await setActiveProfileId(uid, profile.localId);
      toast.success(`Active profile: ${profile.name}`);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to update active profile');
    }
  };

  if (!initialized) {
    return (
      <EmptyState message="Loading profiles…" hint="Reading from Firestore." />
    );
  }

  if (profiles.length === 0) {
    return (
      <EmptyState
        message="No notification profiles yet"
        hint="Profiles are created on Android. Once they sync, they'll show up here so you can pick the active one."
      />
    );
  }

  return (
    <div className="flex flex-col gap-1">
      <p className="text-xs text-[var(--color-text-secondary)] mb-3">
        Your active profile applies to every incoming notification, unless a
        per-category override is in place.
      </p>
      <ul className="flex flex-col gap-1" role="radiogroup" aria-label="Active profile">
        {profiles.map((profile) => {
          const isActive = profile.localId === activeProfileLocalId;
          return (
            <li key={profile.cloudId}>
              <button
                type="button"
                role="radio"
                aria-checked={isActive}
                onClick={() => handleSelect(profile)}
                className={`flex w-full items-start gap-3 rounded-lg border p-3 text-left transition-colors ${
                  isActive
                    ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10'
                    : 'border-[var(--color-border)] hover:bg-[var(--color-bg-secondary)]'
                }`}
              >
                <span
                  aria-hidden="true"
                  className={`mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-full border ${
                    isActive
                      ? 'border-[var(--color-accent)] bg-[var(--color-accent)]'
                      : 'border-[var(--color-border)]'
                  }`}
                >
                  {isActive && <Check className="h-3 w-3 text-white" />}
                </span>
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <p className="text-sm font-semibold text-[var(--color-text-primary)]">
                      {profile.name || 'Untitled Profile'}
                    </p>
                    {isActive && (
                      <span className="inline-flex items-center rounded-full bg-[var(--color-accent)] px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-white">
                        Active
                      </span>
                    )}
                    {profile.isBuiltIn && (
                      <span className="inline-flex items-center rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-[10px] font-medium text-[var(--color-text-secondary)]">
                        Built-In
                      </span>
                    )}
                  </div>
                  <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
                    {profileSummary(profile)}
                  </p>
                </div>
              </button>
            </li>
          );
        })}
      </ul>
      <p className="mt-4 text-xs text-[var(--color-text-secondary)]">
        Creating, renaming, and deleting profiles is currently Android-only.
        Switch the active profile here and the change syncs back to your
        phone instantly.
      </p>
    </div>
  );
}

function profileSummary(profile: NotificationProfile): string {
  const tier =
    URGENCY_TIERS.find((t) => t.key === profile.urgencyTierKey)?.label ??
    'Medium';
  const parts: string[] = [tier];
  if (profile.silent) parts.push('Silent');
  if (profile.escalation) parts.push('Escalates');
  return parts.join(' • ');
}

function EmptyState({ message, hint }: { message: string; hint: string }) {
  return (
    <div className="rounded-lg border border-dashed border-[var(--color-border)] p-6 text-center">
      <p className="text-sm font-medium text-[var(--color-text-primary)]">
        {message}
      </p>
      <p className="mt-1 text-xs text-[var(--color-text-secondary)]">{hint}</p>
    </div>
  );
}
