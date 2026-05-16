import { useMemo, useState } from 'react';
import { Check } from 'lucide-react';
import { toast } from 'sonner';
import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import { updateProfile } from '@/api/firestore/notificationProfiles';
import { getFirebaseUid } from '@/stores/firebaseUid';
import {
  VIBRATION_INTENSITIES,
  VIBRATION_PRESETS,
} from './notificationCatalog';
import type {
  NotificationProfile,
  VibrationIntensityKey,
  VibrationPresetKey,
} from '@/api/firestore/notificationProfiles';

/**
 * Per-profile vibration picker. Vibration patterns are read-only here
 * in the sense that web cannot actually buzz — Android owns playback.
 * We persist the choice to `notification_profiles[i].vibrationPresetKey`
 * + `vibrationIntensityKey` so Android applies it.
 */
export function VibrationScreen() {
  const profiles = useNotificationProfilesStore((s) => s.profiles);
  const profilesInitialized = useNotificationProfilesStore((s) => s.initialized);

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

  const handlePreset = async (preset: VibrationPresetKey) => {
    await persist({ vibrationPresetKey: preset });
  };
  const handleIntensity = async (intensity: VibrationIntensityKey) => {
    await persist({ vibrationIntensityKey: intensity });
  };

  async function persist(patch: {
    vibrationPresetKey?: VibrationPresetKey;
    vibrationIntensityKey?: VibrationIntensityKey;
  }) {
    if (!targetProfile) return;
    let uid: string | null = null;
    try {
      uid = getFirebaseUid();
    } catch {
      uid = null;
    }
    if (!uid) {
      toast.error('Sign in to change vibration.');
      return;
    }
    setSaving(true);
    try {
      await updateProfile(uid, targetProfile.cloudId, patch);
      toast.success('Vibration updated');
    } catch (e) {
      toast.error((e as Error).message || 'Failed to update vibration');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <ProfileSelector
        profiles={profiles}
        selected={targetProfile.cloudId}
        onChange={setTargetProfileId}
      />

      <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3 text-xs text-[var(--color-text-secondary)]">
        Vibration plays on Android. Custom recorded patterns stay on the
        device that recorded them.
      </div>

      <div>
        <h3 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
          Pattern
        </h3>
        <ul className="grid grid-cols-1 gap-1 sm:grid-cols-2" role="radiogroup">
          {VIBRATION_PRESETS.map((preset) => {
            const isSelected =
              targetProfile.vibrationPresetKey === preset.key;
            return (
              <Row
                key={preset.key}
                label={preset.label}
                selected={isSelected}
                disabled={saving}
                onSelect={() => handlePreset(preset.key)}
              />
            );
          })}
        </ul>
      </div>

      <div>
        <h3 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
          Intensity
        </h3>
        <ul className="grid grid-cols-1 gap-1 sm:grid-cols-3" role="radiogroup">
          {VIBRATION_INTENSITIES.map((intensity) => {
            const isSelected =
              targetProfile.vibrationIntensityKey === intensity.key;
            return (
              <Row
                key={intensity.key}
                label={intensity.label}
                selected={isSelected}
                disabled={saving}
                onSelect={() => handleIntensity(intensity.key)}
              />
            );
          })}
        </ul>
      </div>
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

function Row({
  label,
  selected,
  disabled,
  onSelect,
}: {
  label: string;
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
        <span className="text-[var(--color-text-primary)] font-medium">
          {label}
        </span>
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
