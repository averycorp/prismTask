import type {
  UrgencyTierKey,
  VibrationIntensityKey,
  VibrationPresetKey,
  EscalationStepAction,
} from '@/api/firestore/notificationProfiles';

/**
 * Web-side mirror of Android's notification-domain enums, trimmed to
 * the (key, label) pairs the UI actually renders. Source of truth is
 * `app/src/main/java/com/averycorp/prismtask/domain/model/notifications/NotificationEnums.kt`.
 *
 * We intentionally keep this duplicated rather than depending on the
 * Android module — the wire format (the key strings) is the contract,
 * and labels are user-facing strings that web is free to localize
 * later. Backing parity unit 20 of 23.
 */

export const URGENCY_TIERS: ReadonlyArray<{
  key: UrgencyTierKey;
  label: string;
}> = [
  { key: 'low', label: 'Low' },
  { key: 'medium', label: 'Medium' },
  { key: 'high', label: 'High' },
  { key: 'critical', label: 'Critical' },
];

export const VIBRATION_PRESETS: ReadonlyArray<{
  key: VibrationPresetKey;
  label: string;
}> = [
  { key: 'none', label: 'No Vibration' },
  { key: 'single', label: 'Single Pulse' },
  { key: 'double', label: 'Double Pulse' },
  { key: 'triple', label: 'Triple' },
  { key: 'long', label: 'Long Buzz' },
  { key: 'sos', label: 'SOS' },
  { key: 'heartbeat', label: 'Heartbeat' },
  { key: 'wave', label: 'Wave' },
  { key: 'custom', label: 'Custom Pattern' },
];

export const VIBRATION_INTENSITIES: ReadonlyArray<{
  key: VibrationIntensityKey;
  label: string;
}> = [
  { key: 'light', label: 'Light' },
  { key: 'medium', label: 'Medium' },
  { key: 'strong', label: 'Strong' },
];

export const ESCALATION_ACTIONS: ReadonlyArray<{
  key: EscalationStepAction;
  label: string;
}> = [
  { key: 'gentle', label: 'Gentle Ping' },
  { key: 'standard', label: 'Standard Alert' },
  { key: 'loud', label: 'Louder + Vibrate' },
  { key: 'full_screen', label: 'Full-Screen Takeover' },
];

export type SoundCategoryKey =
  | 'chimes'
  | 'bells'
  | 'nature'
  | 'sci_fi'
  | 'minimal'
  | 'percussive'
  | 'voice';

export interface BuiltInSound {
  id: string;
  label: string;
  category: SoundCategoryKey | 'system' | 'silent';
  categoryLabel: string;
}

export const SYSTEM_DEFAULT_SOUND_ID = '__system_default__';
export const SILENT_SOUND_ID = '__silent__';

/**
 * Built-in sound catalog mirror. Keys match
 * `BuiltInSound.kt` exactly so a `notification_profiles[i].soundId`
 * round-trip from Android renders the right label here.
 */
export const BUILT_IN_SOUNDS: ReadonlyArray<BuiltInSound> = [
  {
    id: SYSTEM_DEFAULT_SOUND_ID,
    label: 'System Default',
    category: 'system',
    categoryLabel: 'System',
  },
  {
    id: SILENT_SOUND_ID,
    label: 'Silent',
    category: 'silent',
    categoryLabel: 'Silent',
  },
  { id: 'chime_gentle', label: 'Gentle Chime', category: 'chimes', categoryLabel: 'Chimes' },
  { id: 'chime_morning', label: 'Morning Chime', category: 'chimes', categoryLabel: 'Chimes' },
  { id: 'chime_wind', label: 'Wind Chime', category: 'chimes', categoryLabel: 'Chimes' },
  { id: 'bell_classic', label: 'Classic Bell', category: 'bells', categoryLabel: 'Bells' },
  { id: 'bell_tibetan', label: 'Tibetan Bell', category: 'bells', categoryLabel: 'Bells' },
  { id: 'bell_desk', label: 'Desk Bell', category: 'bells', categoryLabel: 'Bells' },
  { id: 'nature_birds', label: 'Morning Birds', category: 'nature', categoryLabel: 'Nature' },
  { id: 'nature_water', label: 'Water Drop', category: 'nature', categoryLabel: 'Nature' },
  { id: 'nature_wind', label: 'Forest Wind', category: 'nature', categoryLabel: 'Nature' },
  { id: 'scifi_beep', label: 'Console Beep', category: 'sci_fi', categoryLabel: 'Sci-Fi' },
  { id: 'scifi_pulse', label: 'Energy Pulse', category: 'sci_fi', categoryLabel: 'Sci-Fi' },
  { id: 'scifi_alert', label: 'Bridge Alert', category: 'sci_fi', categoryLabel: 'Sci-Fi' },
  { id: 'minimal_pop', label: 'Pop', category: 'minimal', categoryLabel: 'Minimal' },
  { id: 'minimal_tick', label: 'Tick', category: 'minimal', categoryLabel: 'Minimal' },
  { id: 'minimal_whisper', label: 'Whisper', category: 'minimal', categoryLabel: 'Minimal' },
  { id: 'perc_wood', label: 'Wood Block', category: 'percussive', categoryLabel: 'Percussive' },
  { id: 'perc_clap', label: 'Clap', category: 'percussive', categoryLabel: 'Percussive' },
  { id: 'perc_tap', label: 'Soft Tap', category: 'percussive', categoryLabel: 'Percussive' },
  { id: 'voice_chimebell', label: 'Voice: Ding', category: 'voice', categoryLabel: 'Voice' },
  { id: 'voice_heyyou', label: 'Voice: Hey There', category: 'voice', categoryLabel: 'Voice' },
  { id: 'voice_reminder', label: 'Voice: Reminder', category: 'voice', categoryLabel: 'Voice' },
];

/**
 * Find a built-in sound by id. Returns null for custom sound ids
 * (those start with `custom:`).
 */
export function findBuiltInSound(id: string): BuiltInSound | null {
  return BUILT_IN_SOUNDS.find((s) => s.id === id) ?? null;
}

/** Returns true for sound ids minted by `customSoundId()`. */
export function isCustomSoundId(id: string): boolean {
  return id.startsWith('custom:');
}

export const DAY_OF_WEEK_OPTIONS: ReadonlyArray<{ key: string; label: string }> = [
  { key: 'MONDAY', label: 'Mon' },
  { key: 'TUESDAY', label: 'Tue' },
  { key: 'WEDNESDAY', label: 'Wed' },
  { key: 'THURSDAY', label: 'Thu' },
  { key: 'FRIDAY', label: 'Fri' },
  { key: 'SATURDAY', label: 'Sat' },
  { key: 'SUNDAY', label: 'Sun' },
];
