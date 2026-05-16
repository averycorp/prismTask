import { Check } from 'lucide-react';
import { toast } from 'sonner';
import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { NotificationsSubScreenLayout } from './SubScreenLayout';

/**
 * Profiles list — radio-row selection mirroring Android's
 * `NotificationProfilesScreen.kt`. Tapping a row writes the active
 * profile cloud id to `users/{uid}/prefs/notification_prefs` and the
 * optimistic update in the store flips the radio immediately.
 *
 * No create/delete here — Android still owns the canonical author
 * surface (the built-in seed lives there). This screen exists so a
 * web-only user can at least *choose* between profiles their other
 * device authored, which is the primary missing-piece reported in the
 * #1567 parity audit.
 */
export function ProfilesScreen() {
  const profiles = useNotificationProfilesStore((s) => s.profiles);
  const activeProfileCloudId = useNotificationProfilesStore(
    (s) => s.activeProfileCloudId,
  );
  const setActiveProfile = useNotificationProfilesStore(
    (s) => s.setActiveProfile,
  );

  const handleSelect = async (cloudId: string) => {
    let uid: string;
    try {
      uid = getFirebaseUid();
    } catch {
      toast.error('Sign in to change the active notification profile.');
      return;
    }
    try {
      await setActiveProfile(uid, cloudId);
    } catch (err) {
      toast.error(
        `Could not switch profile: ${(err as Error).message ?? 'unknown error'}`,
      );
    }
  };

  return (
    <NotificationsSubScreenLayout
      title="Profiles"
      subtitle="Pick the named delivery bundle PrismTask should use. Switch any time — the change applies to every incoming notification."
    >
      {profiles.length === 0 ? (
        <div className="rounded-xl border border-dashed border-[var(--color-border)] bg-[var(--color-bg-card)] p-6 text-center text-sm text-[var(--color-text-secondary)]">
          No profiles yet. Create one on Android — the built-in starter
          set ships there — and it will sync down to this list within
          seconds.
        </div>
      ) : (
        <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] divide-y divide-[var(--color-border)]">
          {profiles.map((profile) => {
            const selected = profile.cloud_id === activeProfileCloudId;
            return (
              <button
                key={profile.cloud_id}
                onClick={() => handleSelect(profile.cloud_id)}
                aria-pressed={selected}
                className="flex w-full items-center gap-3 p-4 text-left transition-colors hover:bg-[var(--color-bg-secondary)]"
              >
                <span
                  aria-hidden="true"
                  className={`flex h-5 w-5 items-center justify-center rounded-full border ${
                    selected
                      ? 'border-[var(--color-accent)] bg-[var(--color-accent)]'
                      : 'border-[var(--color-border)] bg-[var(--color-bg-secondary)]'
                  }`}
                >
                  {selected && <Check className="h-3 w-3 text-white" />}
                </span>
                <div className="flex-1">
                  <p className="text-sm font-semibold text-[var(--color-text-primary)]">
                    {profile.name}
                  </p>
                  <p className="text-xs text-[var(--color-text-secondary)]">
                    {summarizeProfile(profile)}
                  </p>
                </div>
                {profile.is_built_in && (
                  <span className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">
                    Built-In
                  </span>
                )}
              </button>
            );
          })}
        </div>
      )}
    </NotificationsSubScreenLayout>
  );
}

function summarizeProfile(p: {
  silent: boolean;
  volume_override: boolean;
  escalation: boolean;
  urgency_tier_key: string;
  offsets_csv: string;
}): string {
  const parts: string[] = [];
  parts.push(formatOffsets(p.offsets_csv));
  parts.push(capitalize(p.urgency_tier_key));
  if (p.silent) parts.push('Silent');
  if (p.volume_override && !p.silent) parts.push('Override Volume');
  if (p.escalation) parts.push('Escalates');
  return parts.join(' · ');
}

function capitalize(s: string): string {
  if (!s) return s;
  return s[0].toUpperCase() + s.slice(1);
}

function formatOffsets(csv: string): string {
  const parts = csv
    .split(',')
    .map((x) => Number.parseInt(x.trim(), 10))
    .filter((n) => Number.isFinite(n));
  if (parts.length === 0) return 'At Due Time';
  return parts.map(offsetLabel).join(' · ');
}

function offsetLabel(ms: number): string {
  if (ms === 0) return 'At Due';
  if (ms >= 86_400_000) return `${Math.round(ms / 86_400_000)}d Before`;
  if (ms >= 3_600_000) return `${Math.round(ms / 3_600_000)}h Before`;
  if (ms >= 60_000) return `${Math.round(ms / 60_000)}m Before`;
  return `${ms}ms Before`;
}
