import { useState } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import {
  VIBRATION_INTENSITIES,
  VIBRATION_PRESETS,
} from '@/lib/notifications/escalationChain';
import {
  CardSurface,
  NotificationsSubScreenLayout,
  SubSectionHeading,
} from './SubScreenLayout';
import { RadioRow, SliderRow, ToggleRow } from './primitives';
import { useActiveProfile } from './useActiveProfile';

/**
 * Per-profile vibration / haptic configuration.
 *
 * Web cannot trigger arbitrary vibration patterns reliably (the
 * Navigator vibrate API is gated behind a user gesture and only fires
 * on mobile browsers). This screen is the authoring surface — the
 * values flow through Firestore to the Android client which plays them
 * via `VibrationAdapter`.
 */
export function VibrationScreen() {
  const activeProfile = useActiveProfile();
  const updateProfile = useNotificationProfilesStore((s) => s.updateProfile);

  const [preset, setPreset] = useState<string>(
    activeProfile?.vibration_preset_key ?? 'single',
  );
  const [intensity, setIntensity] = useState<string>(
    activeProfile?.vibration_intensity_key ?? 'medium',
  );
  const [repeatCount, setRepeatCount] = useState<number>(
    activeProfile?.vibration_repeat_count ?? 1,
  );
  const [continuous, setContinuous] = useState<boolean>(
    activeProfile?.vibration_continuous ?? false,
  );
  const [saving, setSaving] = useState(false);

  if (!activeProfile) {
    return (
      <NotificationsSubScreenLayout title="Vibration">
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
      toast.error('Sign in to save vibration preferences.');
      return;
    }
    setSaving(true);
    try {
      await updateProfile(uid, activeProfile.cloud_id, {
        vibrationPresetKey: preset,
        vibrationIntensityKey: intensity,
        vibrationRepeatCount: Math.round(repeatCount),
        vibrationContinuous: continuous,
      });
      toast.success('Vibration preferences saved.');
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
      title="Vibration"
      subtitle={`Active profile: ${activeProfile.name}. Vibration plays on Android — web settings are authored here and sync down.`}
    >
      <SubSectionHeading>Pattern</SubSectionHeading>
      <CardSurface>
        {VIBRATION_PRESETS.map((p) => (
          <RadioRow
            key={p.key}
            label={p.label}
            selected={preset === p.key}
            onSelect={() => setPreset(p.key)}
          />
        ))}
        {preset === 'custom' && (
          <p className="mt-2 text-xs text-[var(--color-text-secondary)]">
            Custom patterns are recorded tap-by-tap on Android. The
            recorded pattern stays on the profile until you switch
            presets.
          </p>
        )}
      </CardSurface>

      <SubSectionHeading>Intensity</SubSectionHeading>
      <CardSurface>
        {VIBRATION_INTENSITIES.map((i) => (
          <RadioRow
            key={i.key}
            label={i.label}
            selected={intensity === i.key}
            onSelect={() => setIntensity(i.key)}
          />
        ))}
      </CardSurface>

      <SubSectionHeading>Repetition</SubSectionHeading>
      <CardSurface>
        <ToggleRow
          label="Continuous Until Dismissed"
          description="Keep buzzing until the notification is tapped or swiped."
          checked={continuous}
          onChange={setContinuous}
        />
        {!continuous && (
          <SliderRow
            label="Repeat Count"
            value={repeatCount}
            min={1}
            max={10}
            step={1}
            format={(v) => `${Math.round(v)}x`}
            onChange={setRepeatCount}
          />
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
