import {
  collection,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Read-only Firestore listener for `users/{uid}/notification_profiles`.
 *
 * Android writes via `SyncService.uploadRoomConfigFamily` →
 * `SyncMapper.notificationProfileToMap` (`NotificationProfileEntity`).
 * Parity Sync Sweep B unit (2 of 23): web pulls the doc shape into a
 * cached, snake_case'd TS view so downstream UI units can read live
 * profiles without standing up the write path here. Write surface is
 * deferred to the notification-profiles UI unit.
 *
 * Field mapping mirrors Android's `SyncMapper`:
 *   - `localId` → number echo of the Android Room rowid (informational
 *     only on web; the Firestore doc id is the authoritative identifier)
 *   - camelCase Firestore fields → snake_case TS shape, matching the
 *     surrounding store conventions (`task_templates`, `boundary_rules`)
 */
export interface NotificationProfile {
  /** Firestore doc id — authoritative identity on web. */
  cloud_id: string;
  /** Android Room rowid echo. Null when the doc was authored web-side. */
  local_id: number | null;
  name: string;
  /** Comma-separated list of offset millis. */
  offsets_csv: string;
  escalation: boolean;
  escalation_interval_minutes: number | null;
  is_built_in: boolean;
  urgency_tier_key: string;
  sound_id: string;
  sound_volume_percent: number;
  sound_fade_in_ms: number;
  sound_fade_out_ms: number;
  silent: boolean;
  vibration_preset_key: string;
  vibration_intensity_key: string;
  vibration_repeat_count: number;
  vibration_continuous: boolean;
  custom_vibration_pattern_csv: string | null;
  display_mode_key: string;
  lock_screen_visibility_key: string;
  accent_color_hex: string | null;
  badge_mode_key: string;
  toast_position_key: string;
  escalation_chain_json: string | null;
  quiet_hours_json: string | null;
  snooze_durations_csv: string;
  re_alert_interval_minutes: number;
  re_alert_max_attempts: number;
  watch_sync_mode_key: string;
  watch_haptic_preset_key: string;
  auto_switch_rules_json: string | null;
  volume_override: boolean;
  created_at: number;
  updated_at: number;
}

function profilesCol(uid: string) {
  return collection(firestore, 'users', uid, 'notification_profiles');
}

function docToProfile(id: string, data: DocumentData): NotificationProfile {
  return {
    cloud_id: id,
    local_id: typeof data.localId === 'number' ? data.localId : null,
    name: typeof data.name === 'string' ? data.name : '',
    offsets_csv: typeof data.offsetsCsv === 'string' ? data.offsetsCsv : '',
    escalation: data.escalation === true,
    escalation_interval_minutes:
      typeof data.escalationIntervalMinutes === 'number'
        ? data.escalationIntervalMinutes
        : null,
    is_built_in: data.isBuiltIn === true,
    urgency_tier_key:
      typeof data.urgencyTierKey === 'string' ? data.urgencyTierKey : 'medium',
    sound_id: typeof data.soundId === 'string' ? data.soundId : 'system_default',
    sound_volume_percent:
      typeof data.soundVolumePercent === 'number' ? data.soundVolumePercent : 70,
    sound_fade_in_ms:
      typeof data.soundFadeInMs === 'number' ? data.soundFadeInMs : 0,
    sound_fade_out_ms:
      typeof data.soundFadeOutMs === 'number' ? data.soundFadeOutMs : 0,
    silent: data.silent === true,
    vibration_preset_key:
      typeof data.vibrationPresetKey === 'string'
        ? data.vibrationPresetKey
        : 'single',
    vibration_intensity_key:
      typeof data.vibrationIntensityKey === 'string'
        ? data.vibrationIntensityKey
        : 'medium',
    vibration_repeat_count:
      typeof data.vibrationRepeatCount === 'number'
        ? data.vibrationRepeatCount
        : 1,
    vibration_continuous: data.vibrationContinuous === true,
    custom_vibration_pattern_csv:
      typeof data.customVibrationPatternCsv === 'string'
        ? data.customVibrationPatternCsv
        : null,
    display_mode_key:
      typeof data.displayModeKey === 'string'
        ? data.displayModeKey
        : 'standard',
    lock_screen_visibility_key:
      typeof data.lockScreenVisibilityKey === 'string'
        ? data.lockScreenVisibilityKey
        : 'app_name',
    accent_color_hex:
      typeof data.accentColorHex === 'string' ? data.accentColorHex : null,
    badge_mode_key:
      typeof data.badgeModeKey === 'string' ? data.badgeModeKey : 'total',
    toast_position_key:
      typeof data.toastPositionKey === 'string'
        ? data.toastPositionKey
        : 'top_right',
    escalation_chain_json:
      typeof data.escalationChainJson === 'string'
        ? data.escalationChainJson
        : null,
    quiet_hours_json:
      typeof data.quietHoursJson === 'string' ? data.quietHoursJson : null,
    snooze_durations_csv:
      typeof data.snoozeDurationsCsv === 'string'
        ? data.snoozeDurationsCsv
        : '5,15,30,60',
    re_alert_interval_minutes:
      typeof data.reAlertIntervalMinutes === 'number'
        ? data.reAlertIntervalMinutes
        : 5,
    re_alert_max_attempts:
      typeof data.reAlertMaxAttempts === 'number'
        ? data.reAlertMaxAttempts
        : 3,
    watch_sync_mode_key:
      typeof data.watchSyncModeKey === 'string'
        ? data.watchSyncModeKey
        : 'mirror',
    watch_haptic_preset_key:
      typeof data.watchHapticPresetKey === 'string'
        ? data.watchHapticPresetKey
        : 'single',
    auto_switch_rules_json:
      typeof data.autoSwitchRulesJson === 'string'
        ? data.autoSwitchRulesJson
        : null,
    volume_override: data.volumeOverride === true,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

/** One-shot fetch of every notification profile. */
export async function getNotificationProfiles(
  uid: string,
): Promise<NotificationProfile[]> {
  const snap = await getDocs(
    query(profilesCol(uid), orderBy('createdAt', 'asc')),
  );
  return snap.docs.map((d) => docToProfile(d.id, d.data()));
}

/**
 * Subscribe to the user's notification-profile collection. Wired from
 * `useFirestoreSync` so cross-device edits (Android creates / renames a
 * profile, web reflects it immediately without a refresh). Read-only —
 * the write path lands in the notification-profiles UI unit.
 */
export function subscribeToNotificationProfiles(
  uid: string,
  callback: (profiles: NotificationProfile[]) => void,
): Unsubscribe {
  const q = query(profilesCol(uid), orderBy('createdAt', 'asc'));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToProfile(d.id, d.data())));
  });
}
