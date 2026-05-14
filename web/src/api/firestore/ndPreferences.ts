import {
  doc,
  getDoc,
  onSnapshot,
  setDoc,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Neurodivergent Mode preferences, synced cross-device with Android.
 *
 * Mirrors Android's `NdPreferences` shape in `data/preferences/NdPreferences.kt`
 * with the raw DataStore key names (`nd_adhd_mode_enabled`, etc.) so the
 * Android `GenericPreferenceSyncService` can round-trip this doc unchanged.
 *
 * Stored at `users/{uid}/prefs/nd_prefs` to match Android's
 * `PreferenceSyncSpec("nd_prefs", ndPrefsDataStore)` registration in
 * `PreferenceSyncModule.kt`.
 *
 * Three independent mode toggles (ADHD / Calm / Focus & Release) that can
 * each be on or off simultaneously. Each parent toggle flips ALL of its
 * sub-settings on the first activation (matching Android's behavior in
 * `NdPreferencesDataStore.setAdhdMode` / `setCalmMode` / `setFocusReleaseMode`).
 */

const DOC_NAME = 'nd_prefs';
const META_TYPES = '__pref_types';
const META_UPDATED_AT = '__pref_updated_at';

export type GoodEnoughEscalation = 'NUDGE' | 'STRICT' | 'NONE';
export type CelebrationIntensity = 'LOW' | 'MEDIUM' | 'HIGH';

export interface NdPreferences {
  // Top-level mode toggles
  adhdModeEnabled: boolean;
  calmModeEnabled: boolean;
  focusReleaseModeEnabled: boolean;
  // Calm Mode sub-settings
  reduceAnimations: boolean;
  mutedColorPalette: boolean;
  quietMode: boolean;
  reduceHaptics: boolean;
  softContrast: boolean;
  // ADHD Mode sub-settings
  checkInIntervalMinutes: number;
  completionAnimations: boolean;
  streakCelebrations: boolean;
  showProgressBars: boolean;
  forgivenessStreaks: boolean;
  // Focus & Release Mode sub-settings — Good Enough Timers
  goodEnoughTimersEnabled: boolean;
  defaultGoodEnoughMinutes: number;
  goodEnoughEscalation: GoodEnoughEscalation;
  // Anti-Rework Guards
  antiReworkEnabled: boolean;
  softWarningEnabled: boolean;
  coolingOffEnabled: boolean;
  coolingOffMinutes: number;
  revisionCounterEnabled: boolean;
  maxRevisions: number;
  // Ship-It Celebrations
  shipItCelebrationsEnabled: boolean;
  celebrationIntensity: CelebrationIntensity;
}

export const DEFAULT_ND_PREFERENCES: NdPreferences = {
  adhdModeEnabled: false,
  calmModeEnabled: false,
  focusReleaseModeEnabled: false,
  reduceAnimations: false,
  mutedColorPalette: false,
  quietMode: false,
  reduceHaptics: false,
  softContrast: false,
  checkInIntervalMinutes: 25,
  completionAnimations: false,
  streakCelebrations: false,
  showProgressBars: false,
  forgivenessStreaks: false,
  goodEnoughTimersEnabled: true,
  defaultGoodEnoughMinutes: 30,
  goodEnoughEscalation: 'NUDGE',
  antiReworkEnabled: true,
  softWarningEnabled: true,
  coolingOffEnabled: false,
  coolingOffMinutes: 30,
  revisionCounterEnabled: false,
  maxRevisions: 3,
  shipItCelebrationsEnabled: true,
  celebrationIntensity: 'MEDIUM',
};

const FIELD_MAP: Record<keyof NdPreferences, string> = {
  adhdModeEnabled: 'nd_adhd_mode_enabled',
  calmModeEnabled: 'nd_calm_mode_enabled',
  focusReleaseModeEnabled: 'nd_focus_release_mode_enabled',
  reduceAnimations: 'nd_reduce_animations',
  mutedColorPalette: 'nd_muted_color_palette',
  quietMode: 'nd_quiet_mode',
  reduceHaptics: 'nd_reduce_haptics',
  softContrast: 'nd_soft_contrast',
  checkInIntervalMinutes: 'nd_check_in_interval_minutes',
  completionAnimations: 'nd_completion_animations',
  streakCelebrations: 'nd_streak_celebrations',
  showProgressBars: 'nd_show_progress_bars',
  forgivenessStreaks: 'nd_forgiveness_streaks',
  goodEnoughTimersEnabled: 'nd_good_enough_timers_enabled',
  defaultGoodEnoughMinutes: 'nd_default_good_enough_minutes',
  goodEnoughEscalation: 'nd_good_enough_escalation',
  antiReworkEnabled: 'nd_anti_rework_enabled',
  softWarningEnabled: 'nd_soft_warning_enabled',
  coolingOffEnabled: 'nd_cooling_off_enabled',
  coolingOffMinutes: 'nd_cooling_off_minutes',
  revisionCounterEnabled: 'nd_revision_counter_enabled',
  maxRevisions: 'nd_max_revisions',
  shipItCelebrationsEnabled: 'nd_ship_it_celebrations_enabled',
  celebrationIntensity: 'nd_celebration_intensity',
};

const BOOL_KEYS: (keyof NdPreferences)[] = [
  'adhdModeEnabled',
  'calmModeEnabled',
  'focusReleaseModeEnabled',
  'reduceAnimations',
  'mutedColorPalette',
  'quietMode',
  'reduceHaptics',
  'softContrast',
  'completionAnimations',
  'streakCelebrations',
  'showProgressBars',
  'forgivenessStreaks',
  'goodEnoughTimersEnabled',
  'antiReworkEnabled',
  'softWarningEnabled',
  'coolingOffEnabled',
  'revisionCounterEnabled',
  'shipItCelebrationsEnabled',
];

const INT_KEYS: (keyof NdPreferences)[] = [
  'checkInIntervalMinutes',
  'defaultGoodEnoughMinutes',
  'coolingOffMinutes',
  'maxRevisions',
];

const ENUM_KEYS: { key: keyof NdPreferences; valid: readonly string[] }[] = [
  { key: 'goodEnoughEscalation', valid: ['NUDGE', 'STRICT', 'NONE'] },
  { key: 'celebrationIntensity', valid: ['LOW', 'MEDIUM', 'HIGH'] },
];

function prefsDoc(uid: string) {
  return doc(firestore, 'users', uid, 'prefs', DOC_NAME);
}

function read(data: DocumentData | undefined): NdPreferences {
  if (!data) return DEFAULT_ND_PREFERENCES;
  const out: NdPreferences = { ...DEFAULT_ND_PREFERENCES };
  const writable = out as unknown as Record<string, unknown>;
  for (const k of BOOL_KEYS) {
    const v = data[FIELD_MAP[k]];
    if (typeof v === 'boolean') writable[k] = v;
  }
  for (const k of INT_KEYS) {
    const v = data[FIELD_MAP[k]];
    if (typeof v === 'number' && Number.isFinite(v)) {
      writable[k] = Math.round(v);
    }
  }
  for (const { key, valid } of ENUM_KEYS) {
    const v = data[FIELD_MAP[key]];
    if (typeof v === 'string' && valid.includes(v)) {
      writable[key] = v;
    }
  }
  return out;
}

export async function getNdPreferences(uid: string): Promise<NdPreferences> {
  const snap = await getDoc(prefsDoc(uid));
  return read(snap.exists() ? snap.data() : undefined);
}

export async function patchNdPreferences(
  uid: string,
  patch: Partial<NdPreferences>,
): Promise<void> {
  const payload: Record<string, unknown> = {};
  const typeTags: Record<string, string> = {};
  for (const k of Object.keys(patch) as (keyof NdPreferences)[]) {
    const field = FIELD_MAP[k];
    if (!field) continue;
    const v = patch[k];
    if (v === undefined) continue;
    payload[field] = v;
    if (typeof v === 'boolean') typeTags[field] = 'bool';
    else if (typeof v === 'number') typeTags[field] = 'int';
    else if (typeof v === 'string') typeTags[field] = 'string';
  }
  if (Object.keys(payload).length === 0) return;
  payload[META_TYPES] = typeTags;
  payload[META_UPDATED_AT] = Date.now();
  await setDoc(prefsDoc(uid), payload, { merge: true });
}

export function subscribeToNdPreferences(
  uid: string,
  cb: (prefs: NdPreferences) => void,
): Unsubscribe {
  return onSnapshot(prefsDoc(uid), (snap) =>
    cb(read(snap.exists() ? snap.data() : undefined)),
  );
}

/** Returns true if any of the three ND modes is active. */
export function isAnyNdModeActive(prefs: NdPreferences): boolean {
  return (
    prefs.adhdModeEnabled || prefs.calmModeEnabled || prefs.focusReleaseModeEnabled
  );
}

/**
 * Returns the effective Ship-It celebration intensity. Calm Mode forces
 * intensity to LOW regardless of the user's stored preference (matches
 * Android `effectiveCelebrationIntensity`).
 */
export function effectiveCelebrationIntensity(
  prefs: NdPreferences,
): CelebrationIntensity {
  return prefs.calmModeEnabled ? 'LOW' : prefs.celebrationIntensity;
}

/**
 * Resolves which celebration system fires when a task is completed.
 * Ship-It celebrations take priority over ADHD completion celebrations
 * when Focus & Release Mode is active, to avoid double-firing.
 */
export function shouldFireShipItCelebration(prefs: NdPreferences): boolean {
  return prefs.focusReleaseModeEnabled && prefs.shipItCelebrationsEnabled;
}
