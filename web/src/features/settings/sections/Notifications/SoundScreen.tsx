import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import { useCustomSoundsStore } from '@/stores/customSoundsStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import {
  BUILT_IN_SOUNDS,
  SOUND_CATEGORIES,
  type BuiltInSoundOption,
} from '@/lib/notifications/escalationChain';
import {
  CardSurface,
  NotificationsSubScreenLayout,
  SubSectionHeading,
} from './SubScreenLayout';
import { Chip, RadioRow, SliderRow, ToggleRow } from './primitives';
import { useActiveProfile } from './useActiveProfile';

/**
 * Sound picker for the active notification profile.
 *
 * The built-in catalog is local-only (audio blobs live in `res/raw/`
 * on Android, never sync), and the user's custom uploads come in
 * metadata-only via `customSoundsStore` — picking a custom sound on
 * web just records the id, and the Android client plays it back.
 */
export function SoundScreen() {
  const activeProfile = useActiveProfile();
  const customSounds = useCustomSoundsStore((s) => s.sounds);
  const updateProfile = useNotificationProfilesStore((s) => s.updateProfile);

  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [pendingSoundId, setPendingSoundId] = useState<string>(
    activeProfile?.sound_id ?? '__system_default__',
  );
  const [pendingSilent, setPendingSilent] = useState<boolean>(
    activeProfile?.silent ?? false,
  );
  const [pendingVolumeOverride, setPendingVolumeOverride] = useState<boolean>(
    activeProfile?.volume_override ?? false,
  );
  const [pendingVolume, setPendingVolume] = useState<number>(
    activeProfile?.sound_volume_percent ?? 70,
  );
  const [pendingFadeIn, setPendingFadeIn] = useState<number>(
    activeProfile?.sound_fade_in_ms ?? 0,
  );
  const [pendingFadeOut, setPendingFadeOut] = useState<number>(
    activeProfile?.sound_fade_out_ms ?? 0,
  );
  const [saving, setSaving] = useState(false);

  const visibleSounds = useMemo<BuiltInSoundOption[]>(() => {
    if (selectedCategory === null) return [...BUILT_IN_SOUNDS];
    if (selectedCategory === 'custom') return [];
    return BUILT_IN_SOUNDS.filter((s) => s.category === selectedCategory);
  }, [selectedCategory]);

  if (!activeProfile) {
    return (
      <NotificationsSubScreenLayout title="Sound">
        <p className="text-sm text-[var(--color-text-secondary)]">
          No profiles yet — create one on Android first.
        </p>
      </NotificationsSubScreenLayout>
    );
  }

  const handleSave = async () => {
    let uid: string;
    try {
      uid = getFirebaseUid();
    } catch {
      toast.error('Sign in to save sound preferences.');
      return;
    }
    setSaving(true);
    try {
      await updateProfile(uid, activeProfile.cloud_id, {
        soundId: pendingSoundId,
        silent: pendingSilent,
        volumeOverride: pendingVolumeOverride && !pendingSilent,
        soundVolumePercent: Math.round(pendingVolume),
        soundFadeInMs: Math.round(pendingFadeIn),
        soundFadeOutMs: Math.round(pendingFadeOut),
      });
      toast.success('Sound preferences saved.');
    } catch (err) {
      toast.error(
        `Could not save: ${(err as Error).message ?? 'unknown error'}`,
      );
    } finally {
      setSaving(false);
    }
  };

  return (
    <NotificationsSubScreenLayout
      title="Sound"
      subtitle={`Active profile: ${activeProfile.name}`}
    >
      <SubSectionHeading>Global</SubSectionHeading>
      <CardSurface>
        <ToggleRow
          label="Silent for This Profile"
          description="Play no sound at all, even when the device is unmuted."
          checked={pendingSilent}
          onChange={setPendingSilent}
        />
        <ToggleRow
          label="Override Volume"
          description="Play at alarm volume so reminders are heard on silent or DND."
          checked={pendingVolumeOverride && !pendingSilent}
          onChange={setPendingVolumeOverride}
        />
      </CardSurface>

      <SubSectionHeading>Volume & Fades</SubSectionHeading>
      <CardSurface>
        <SliderRow
          label="Volume"
          value={pendingVolume}
          min={0}
          max={100}
          step={1}
          format={(v) => `${Math.round(v)}%`}
          onChange={setPendingVolume}
        />
        <SliderRow
          label="Fade In"
          value={pendingFadeIn}
          min={0}
          max={5000}
          step={100}
          format={(v) => `${(v / 1000).toFixed(1)}s`}
          onChange={setPendingFadeIn}
        />
        <SliderRow
          label="Fade Out"
          value={pendingFadeOut}
          min={0}
          max={5000}
          step={100}
          format={(v) => `${(v / 1000).toFixed(1)}s`}
          onChange={setPendingFadeOut}
        />
      </CardSurface>

      <SubSectionHeading>Category</SubSectionHeading>
      <div className="mb-3 flex flex-wrap gap-2">
        <Chip
          label="All"
          selected={selectedCategory === null}
          onClick={() => setSelectedCategory(null)}
        />
        {SOUND_CATEGORIES.map((c) => (
          <Chip
            key={c.key}
            label={c.label}
            selected={selectedCategory === c.key}
            onClick={() => setSelectedCategory(c.key)}
          />
        ))}
      </div>

      <CardSurface>
        {selectedCategory === 'custom' ? (
          customSounds.length === 0 ? (
            <p className="py-2 text-sm text-[var(--color-text-secondary)]">
              No custom uploads yet. Uploading is supported on Android —
              new sounds appear here once they sync.
            </p>
          ) : (
            customSounds.map((s) => (
              <RadioRow
                key={s.cloud_id}
                label={s.name}
                secondary={`${s.format.toUpperCase()} · ${(s.duration_ms / 1000).toFixed(1)}s`}
                selected={pendingSoundId === customSoundId(s)}
                onSelect={() => setPendingSoundId(customSoundId(s))}
              />
            ))
          )
        ) : (
          visibleSounds.map((s) => (
            <RadioRow
              key={s.id}
              label={s.label}
              secondary={categoryLabel(s.category)}
              selected={pendingSoundId === s.id}
              onSelect={() => setPendingSoundId(s.id)}
            />
          ))
        )}
      </CardSurface>

      <div className="mt-5 flex justify-end gap-2">
        <Button onClick={handleSave} loading={saving}>
          Save
        </Button>
      </div>
    </NotificationsSubScreenLayout>
  );
}

function customSoundId(s: { local_id: number | null; cloud_id: string }) {
  // Android `CustomSoundEntity.soundId()` is `custom_<rowid>`. Web-only
  // uploads have no rowid yet, so fall back to a doc-id-keyed form —
  // cross-device playback of those will need an Android-side resolver
  // update when the upload-from-web path lands.
  if (s.local_id != null) return `custom_${s.local_id}`;
  return `custom_cloud_${s.cloud_id}`;
}

function categoryLabel(key: string): string {
  return SOUND_CATEGORIES.find((c) => c.key === key)?.label ?? key;
}
