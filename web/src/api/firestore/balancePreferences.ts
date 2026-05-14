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
 * Work-Life Balance preferences, synced cross-device with Android.
 *
 * Mirrors Android's `WorkLifeBalancePreferences` shape in
 * `app/.../data/preferences/UserPreferencesDataStore.kt:80-100` with the
 * raw DataStore key names (`wlb_work_target`, etc.) so Android's
 * GenericPreferenceSyncService can round-trip the doc unchanged.
 *
 * Stored at `users/{uid}/prefs/user_prefs` (shared doc — same as
 * aiPreferences). Targets are 0..100 ints; the float ratios consumed by
 * the BalanceTracker engine are computed at read time.
 */

const DOC_NAME = 'user_prefs';
const FIELD_WORK = 'wlb_work_target';
const FIELD_PERSONAL = 'wlb_personal_target';
const FIELD_SELFCARE = 'wlb_selfcare_target';
const FIELD_HEALTH = 'wlb_health_target';
const FIELD_SHOW_BAR = 'wlb_show_bar';
const FIELD_OVERLOAD = 'wlb_overload_threshold';
const META_TYPES = '__pref_types';
const META_UPDATED_AT = '__pref_updated_at';

export interface BalancePreferences {
  /** 0..100 ints. Must sum to ~100. */
  workTarget: number;
  personalTarget: number;
  selfCareTarget: number;
  healthTarget: number;
  /** Whether to show the balance bar on Today. */
  showBalanceBar: boolean;
  /** 5..25, additive buffer over workTarget before "overloaded" fires. */
  overloadThresholdPct: number;
}

export const DEFAULT_BALANCE_PREFERENCES: BalancePreferences = {
  workTarget: 40,
  personalTarget: 25,
  selfCareTarget: 20,
  healthTarget: 15,
  showBalanceBar: true,
  overloadThresholdPct: 10,
};

function prefsDoc(uid: string) {
  return doc(firestore, 'users', uid, 'prefs', DOC_NAME);
}

function clampInt(v: unknown, lo: number, hi: number, fallback: number): number {
  const n = typeof v === 'number' ? Math.round(v) : Number.NaN;
  if (Number.isNaN(n)) return fallback;
  return Math.max(lo, Math.min(hi, n));
}

function read(data: DocumentData | undefined): BalancePreferences {
  if (!data) return DEFAULT_BALANCE_PREFERENCES;
  const showBar = typeof data[FIELD_SHOW_BAR] === 'boolean'
    ? (data[FIELD_SHOW_BAR] as boolean)
    : DEFAULT_BALANCE_PREFERENCES.showBalanceBar;
  return {
    workTarget: clampInt(data[FIELD_WORK], 0, 100, DEFAULT_BALANCE_PREFERENCES.workTarget),
    personalTarget: clampInt(
      data[FIELD_PERSONAL],
      0,
      100,
      DEFAULT_BALANCE_PREFERENCES.personalTarget,
    ),
    selfCareTarget: clampInt(
      data[FIELD_SELFCARE],
      0,
      100,
      DEFAULT_BALANCE_PREFERENCES.selfCareTarget,
    ),
    healthTarget: clampInt(
      data[FIELD_HEALTH],
      0,
      100,
      DEFAULT_BALANCE_PREFERENCES.healthTarget,
    ),
    showBalanceBar: showBar,
    overloadThresholdPct: clampInt(
      data[FIELD_OVERLOAD],
      5,
      25,
      DEFAULT_BALANCE_PREFERENCES.overloadThresholdPct,
    ),
  };
}

export async function getBalancePreferences(uid: string): Promise<BalancePreferences> {
  const snap = await getDoc(prefsDoc(uid));
  return read(snap.exists() ? snap.data() : undefined);
}

export async function setBalancePreferences(
  uid: string,
  prefs: Partial<BalancePreferences>,
): Promise<void> {
  const payload: Record<string, unknown> = {};
  const typeTags: Record<string, string> = {};
  if (prefs.workTarget !== undefined) {
    payload[FIELD_WORK] = clampInt(prefs.workTarget, 0, 100, prefs.workTarget);
    typeTags[FIELD_WORK] = 'int';
  }
  if (prefs.personalTarget !== undefined) {
    payload[FIELD_PERSONAL] = clampInt(prefs.personalTarget, 0, 100, prefs.personalTarget);
    typeTags[FIELD_PERSONAL] = 'int';
  }
  if (prefs.selfCareTarget !== undefined) {
    payload[FIELD_SELFCARE] = clampInt(prefs.selfCareTarget, 0, 100, prefs.selfCareTarget);
    typeTags[FIELD_SELFCARE] = 'int';
  }
  if (prefs.healthTarget !== undefined) {
    payload[FIELD_HEALTH] = clampInt(prefs.healthTarget, 0, 100, prefs.healthTarget);
    typeTags[FIELD_HEALTH] = 'int';
  }
  if (prefs.showBalanceBar !== undefined) {
    payload[FIELD_SHOW_BAR] = prefs.showBalanceBar;
    typeTags[FIELD_SHOW_BAR] = 'bool';
  }
  if (prefs.overloadThresholdPct !== undefined) {
    payload[FIELD_OVERLOAD] = clampInt(
      prefs.overloadThresholdPct,
      5,
      25,
      prefs.overloadThresholdPct,
    );
    typeTags[FIELD_OVERLOAD] = 'int';
  }
  if (Object.keys(payload).length === 0) return;
  payload[META_TYPES] = typeTags;
  payload[META_UPDATED_AT] = Date.now();
  await setDoc(prefsDoc(uid), payload, { merge: true });
}

export function subscribeToBalancePreferences(
  uid: string,
  cb: (prefs: BalancePreferences) => void,
): Unsubscribe {
  return onSnapshot(prefsDoc(uid), (snap) =>
    cb(read(snap.exists() ? snap.data() : undefined)),
  );
}
