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
 * Today-screen "Dashboard" preferences synced cross-device with Android.
 *
 * Mirrors Android's `DashboardPreferences` DataStore (`dashboard_prefs`)
 * which syncs through `GenericPreferenceSyncService` at
 * `users/{uid}/prefs/dashboard_prefs`. Generic `__pref_types`
 * envelope.
 *
 * Fields:
 *   - `section_order`        (string, CSV)         — Android default
 *     "progress,daily_essentials,overdue,today_tasks,plan_more,completed".
 *   - `hidden_sections`      (stringSet)           — Android default `[]`.
 *   - `progress_style`       (string)              — Android default `"ring"`.
 *   - `collapsed_sections`   (stringSet)           — Android default
 *     `{"planned", "completed"}`.
 *
 * Web context: as of parity audit Batch 6, web's Today screen has no
 * section-reorder UI (tracked separately as C.1f in the Wellness
 * batch). This module is a foundational write/read mirror so that:
 *   (1) Android-side changes propagate to web's localStorage cache
 *       immediately, and
 *   (2) when C.1f lands, the data layer is already in place with no
 *       cross-device drift.
 *
 * No web UI consumer this PR — pure Firestore mirror validated by
 * unit test only. Parity audit A.5b (dashboard slice).
 */

const DOC_NAME = 'dashboard_prefs';
const FIELD_SECTION_ORDER = 'section_order';
const FIELD_HIDDEN_SECTIONS = 'hidden_sections';
const FIELD_PROGRESS_STYLE = 'progress_style';
const FIELD_COLLAPSED_SECTIONS = 'collapsed_sections';
const META_TYPES = '__pref_types';
const META_UPDATED_AT = '__pref_updated_at';

export const DEFAULT_SECTION_ORDER: ReadonlyArray<string> = [
  'progress',
  'daily_essentials',
  'overdue',
  'today_tasks',
  'plan_more',
  'completed',
];
export const DEFAULT_HIDDEN_SECTIONS: ReadonlyArray<string> = [];
export const DEFAULT_PROGRESS_STYLE = 'ring';
export const DEFAULT_COLLAPSED_SECTIONS: ReadonlyArray<string> = [
  'planned',
  'completed',
];

export interface DashboardPreferencesSnapshot {
  sectionOrder: string[];
  hiddenSections: string[];
  progressStyle: string;
  collapsedSections: string[];
}

function prefsDoc(uid: string) {
  return doc(firestore, 'users', uid, 'prefs', DOC_NAME);
}

function parseCsv(raw: unknown, fallback: ReadonlyArray<string>): string[] {
  if (typeof raw !== 'string') return [...fallback];
  const parts = raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
  return parts.length > 0 ? parts : [...fallback];
}

function parseStringSet(
  raw: unknown,
  fallback: ReadonlyArray<string>,
): string[] {
  // Android pushes stringSet keys as a JSON array (per
  // PreferenceSyncSerialization.encodeValue). Tolerate either an array
  // or the legacy CSV shape just in case.
  if (Array.isArray(raw)) {
    return raw.filter((x): x is string => typeof x === 'string');
  }
  if (typeof raw === 'string') {
    return parseCsv(raw, fallback);
  }
  return [...fallback];
}

function readSnapshot(
  data: DocumentData | undefined,
): DashboardPreferencesSnapshot {
  if (!data) {
    return {
      sectionOrder: [...DEFAULT_SECTION_ORDER],
      hiddenSections: [...DEFAULT_HIDDEN_SECTIONS],
      progressStyle: DEFAULT_PROGRESS_STYLE,
      collapsedSections: [...DEFAULT_COLLAPSED_SECTIONS],
    };
  }
  return {
    sectionOrder: parseCsv(data[FIELD_SECTION_ORDER], DEFAULT_SECTION_ORDER),
    hiddenSections: parseStringSet(
      data[FIELD_HIDDEN_SECTIONS],
      DEFAULT_HIDDEN_SECTIONS,
    ),
    progressStyle:
      typeof data[FIELD_PROGRESS_STYLE] === 'string'
        ? data[FIELD_PROGRESS_STYLE]
        : DEFAULT_PROGRESS_STYLE,
    collapsedSections: parseStringSet(
      data[FIELD_COLLAPSED_SECTIONS],
      DEFAULT_COLLAPSED_SECTIONS,
    ),
  };
}

export async function getDashboardPreferences(
  uid: string,
): Promise<DashboardPreferencesSnapshot> {
  const snap = await getDoc(prefsDoc(uid));
  return readSnapshot(snap.exists() ? snap.data() : undefined);
}

/**
 * Write the user's section order (e.g. after a drag-reorder on the
 * future C.1f Today UI). Android stores this as a comma-separated
 * `String` Preferences.Key, NOT a stringSet — order is significant.
 */
export async function setSectionOrder(
  uid: string,
  order: ReadonlyArray<string>,
): Promise<void> {
  const csv = order.filter((s) => s.length > 0).join(',');
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_SECTION_ORDER]: csv,
      [META_TYPES]: { [FIELD_SECTION_ORDER]: 'string' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

export async function setHiddenSections(
  uid: string,
  hidden: ReadonlyArray<string>,
): Promise<void> {
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_HIDDEN_SECTIONS]: Array.from(new Set(hidden)),
      [META_TYPES]: { [FIELD_HIDDEN_SECTIONS]: 'stringSet' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

export async function setProgressStyle(
  uid: string,
  style: string,
): Promise<void> {
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_PROGRESS_STYLE]: style,
      [META_TYPES]: { [FIELD_PROGRESS_STYLE]: 'string' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

export async function setCollapsedSections(
  uid: string,
  collapsed: ReadonlyArray<string>,
): Promise<void> {
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_COLLAPSED_SECTIONS]: Array.from(new Set(collapsed)),
      [META_TYPES]: { [FIELD_COLLAPSED_SECTIONS]: 'stringSet' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

export function subscribeToDashboardPreferences(
  uid: string,
  cb: (snapshot: DashboardPreferencesSnapshot) => void,
): Unsubscribe {
  return onSnapshot(prefsDoc(uid), (snap) => {
    cb(readSnapshot(snap.exists() ? snap.data() : undefined));
  });
}
