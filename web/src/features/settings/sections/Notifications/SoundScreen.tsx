import { useMemo, useState } from 'react';
import { Check } from 'lucide-react';
import { toast } from 'sonner';
import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import { useCustomSoundsStore } from '@/stores/customSoundsStore';
import { updateProfile } from '@/api/firestore/notificationProfiles';
import { customSoundId } from '@/api/firestore/customSounds';
import { getFirebaseUid } from '@/stores/firebaseUid';
import {
  BUILT_IN_SOUNDS,
  SYSTEM_DEFAULT_SOUND_ID,
  findBuiltInSound,
  isCustomSoundId,
} from './notificationCatalog';
import type { NotificationProfile } from '@/api/firestore/notificationProfiles';
import type { CustomSound } from '@/api/firestore/customSounds';

/**
 * Per-profile sound picker.
 *
 * Web is settings-only — Android actually plays the sound. We surface
 * the list of built-in sounds + the user's custom uploads (read-only,
 * pushed from Android) so the user can pick which one their active
 * profile plays. The choice is written back to
 * `notification_profiles[i].soundId` and Android picks it up on the
 * next pull.
 */
export function SoundScreen() {
  const profiles = useNotificationProfilesStore((s) => s.profiles);
  const customSounds = useCustomSoundsStore((s) => s.customSounds);
  const customSoundsInitialized = useCustomSoundsStore((s) => s.initialized);
  const profilesInitialized = useNotificationProfilesStore((s) => s.initialized);

  // Default-target the first non-built-in profile if there's exactly
  // one to edit, otherwise let the user pick.
  const [targetProfileId, setTargetProfileId] = useState<string | null>(null);
  const targetProfile = useMemo<NotificationProfile | null>(() => {
    if (profiles.length === 0) return null;
    if (targetProfileId) {
      return profiles.find((p) => p.cloudId === targetProfileId) ?? profiles[0];
    }
    return profiles[0];
  }, [profiles, targetProfileId]);

  const [saving, setSaving] = useState(false);

  if (!profilesInitialized) {
    return <EmptyState message="Loading profiles…" />;
  }
  if (!targetProfile) {
    return (
      <EmptyState
        message="No notification profiles to configure"
        hint="Create a profile on Android first."
      />
    );
  }

  const handleSelect = async (soundId: string) => {
    if (!targetProfile) return;
    let uid: string | null = null;
    try {
      uid = getFirebaseUid();
    } catch {
      uid = null;
    }
    if (!uid) {
      toast.error('Sign in to change the sound.');
      return;
    }
    setSaving(true);
    try {
      await updateProfile(uid, targetProfile.cloudId, { soundId });
      const label =
        findBuiltInSound(soundId)?.label ??
        customSounds.find((s) => customSoundId(s) === soundId)?.name ??
        'sound';
      toast.success(`Sound set to ${label}`);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to update sound');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <ProfileSelector
        profiles={profiles}
        selected={targetProfile.cloudId}
        onChange={setTargetProfileId}
      />

      <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3 text-xs text-[var(--color-text-secondary)]">
        Sounds play on Android. Custom uploads are managed there too —
        they show up here once they sync.
      </div>

      <div>
        <h3 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
          Built-In Sounds
        </h3>
        <ul
          className="grid grid-cols-1 gap-1 sm:grid-cols-2"
          role="radiogroup"
          aria-label="Built-in sounds"
        >
          {BUILT_IN_SOUNDS.map((sound) => {
            const isSelected = targetProfile.soundId === sound.id;
            return (
              <SoundRow
                key={sound.id}
                label={sound.label}
                secondary={sound.categoryLabel}
                selected={isSelected}
                disabled={saving}
                onSelect={() => handleSelect(sound.id)}
              />
            );
          })}
        </ul>
      </div>

      <div>
        <h3 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
          My Uploads
        </h3>
        {!customSoundsInitialized ? (
          <p className="text-xs text-[var(--color-text-secondary)]">Loading…</p>
        ) : customSounds.length === 0 ? (
          <p className="text-xs text-[var(--color-text-secondary)]">
            No custom uploads yet. Upload sounds from Android and they'll
            appear here.
          </p>
        ) : (
          <ul
            className="grid grid-cols-1 gap-1 sm:grid-cols-2"
            role="radiogroup"
            aria-label="Custom uploads"
          >
            {customSounds.map((sound) => {
              const id = customSoundId(sound);
              const isSelected = targetProfile.soundId === id;
              return (
                <SoundRow
                  key={sound.cloudId}
                  label={sound.name}
                  secondary={`${sound.format.toUpperCase()} • ${(
                    sound.durationMs / 1000
                  ).toFixed(1)}s`}
                  selected={isSelected}
                  disabled={saving}
                  onSelect={() => handleSelect(id)}
                />
              );
            })}
          </ul>
        )}
      </div>

      {targetProfile.soundId !== SYSTEM_DEFAULT_SOUND_ID && (
        <button
          type="button"
          onClick={() => handleSelect(SYSTEM_DEFAULT_SOUND_ID)}
          className="self-start text-xs text-[var(--color-accent)] hover:underline"
          disabled={saving}
        >
          Reset to system default
        </button>
      )}
      {targetProfile.soundId && isCustomSoundId(targetProfile.soundId) &&
        !customSounds.some(
          (s) => customSoundId(s) === targetProfile.soundId,
        ) && (
          <p className="text-xs text-amber-700">
            The active sound is a custom upload not yet synced to this
            browser. Open Android once to push it.
          </p>
        )}
    </div>
  );
}

function ProfileSelector({
  profiles,
  selected,
  onChange,
}: {
  profiles: ReadonlyArray<NotificationProfile>;
  selected: string;
  onChange: (id: string) => void;
}) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
        Profile
      </label>
      <select
        value={selected}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
      >
        {profiles.map((p) => (
          <option key={p.cloudId} value={p.cloudId}>
            {p.name || 'Untitled Profile'}
          </option>
        ))}
      </select>
    </div>
  );
}

function SoundRow({
  label,
  secondary,
  selected,
  disabled,
  onSelect,
}: {
  label: string;
  secondary: string;
  selected: boolean;
  disabled: boolean;
  onSelect: () => void;
}) {
  return (
    <li>
      <button
        type="button"
        role="radio"
        aria-checked={selected}
        disabled={disabled}
        onClick={onSelect}
        className={`flex w-full items-center gap-2 rounded-md border px-3 py-2 text-left text-sm transition-colors ${
          selected
            ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10'
            : 'border-[var(--color-border)] hover:bg-[var(--color-bg-secondary)]'
        } disabled:cursor-not-allowed disabled:opacity-60`}
      >
        <span
          aria-hidden="true"
          className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-full border ${
            selected
              ? 'border-[var(--color-accent)] bg-[var(--color-accent)]'
              : 'border-[var(--color-border)]'
          }`}
        >
          {selected && <Check className="h-3 w-3 text-white" />}
        </span>
        <div className="flex-1">
          <p className="font-medium text-[var(--color-text-primary)]">{label}</p>
          <p className="text-xs text-[var(--color-text-secondary)]">
            {secondary}
          </p>
        </div>
      </button>
    </li>
  );
}

function EmptyState({ message, hint }: { message: string; hint?: string }) {
  return (
    <div className="rounded-lg border border-dashed border-[var(--color-border)] p-6 text-center">
      <p className="text-sm font-medium text-[var(--color-text-primary)]">
        {message}
      </p>
      {hint && (
        <p className="mt-1 text-xs text-[var(--color-text-secondary)]">{hint}</p>
      )}
    </div>
  );
}

// Export for usage in custom-sound list elsewhere
export type { CustomSound };
