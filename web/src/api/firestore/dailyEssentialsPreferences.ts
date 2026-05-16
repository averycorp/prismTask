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
 * Daily Essentials preferences synced cross-device with Android.
 *
 * Mirrors Android's `DailyEssentialsPreferences` DataStore at
 * `users/{uid}/prefs/daily_essentials_prefs` (registered in
 * `PreferenceSyncModule.kt`).
 *
 * Fields on Android (long-typed habit IDs become strings on web because
 * Firestore habits are cloud-id-keyed):
 *   - `housework_habit_id`: nullable long → string|null on web
 *   - `schoolwork_habit_id`: nullable long → reserved (not yet wired
 *     into web UI — schoolwork card already uses the schoolwork store)
 *   - `has_seen_daily_essentials_hint`: boolean
 *
 * Web-only extensions (per-card visibility toggles, parity unit 6):
 *   - `show_morning_routine` / `show_bedtime_routine` /
 *     `show_housework_habit` / `show_housework_routine` /
 *     `show_schoolwork`
 *
 * Web's source-of-truth for show_* flags is local Firestore until
 * Android grows matching toggles. Android-side reads tolerate extra
 * fields via `__pref_types` envelope; we just round-trip them.
 */

const DOC_NAME = 'daily_essentials_prefs';

const FIELD_HOUSEWORK_HABIT_ID = 'housework_habit_id';
const FIELD_SCHOOLWORK_HABIT_ID = 'schoolwork_habit_id';
const FIELD_HAS_SEEN_HINT = 'has_seen_daily_essentials_hint';
const FIELD_SHOW_MORNING = 'show_morning_routine';
const FIELD_SHOW_BEDTIME = 'show_bedtime_routine';
const FIELD_SHOW_HOUSEWORK_HABIT = 'show_housework_habit';
const FIELD_SHOW_HOUSEWORK_ROUTINE = 'show_housework_routine';
const META_TYPES = '__pref_types';
const META_UPDATED_AT = '__pref_updated_at';

export interface DailyEssentialsSnapshot {
  houseworkHabitId: string | null;
  schoolworkHabitId: string | null;
  hasSeenHint: boolean;
  showMorningRoutine: boolean;
  showBedtimeRoutine: boolean;
  showHouseworkHabit: boolean;
  showHouseworkRoutine: boolean;
}

export const DEFAULT_DAILY_ESSENTIALS: DailyEssentialsSnapshot = {
  houseworkHabitId: null,
  schoolworkHabitId: null,
  hasSeenHint: false,
  showMorningRoutine: true,
  showBedtimeRoutine: true,
  showHouseworkHabit: true,
  showHouseworkRoutine: true,
};

function prefsDoc(uid: string) {
  return doc(firestore, 'users', uid, 'prefs', DOC_NAME);
}

function readSnapshot(data: DocumentData | undefined): DailyEssentialsSnapshot {
  if (!data) return { ...DEFAULT_DAILY_ESSENTIALS };
  return {
    houseworkHabitId: stringOrNull(data[FIELD_HOUSEWORK_HABIT_ID]),
    schoolworkHabitId: stringOrNull(data[FIELD_SCHOOLWORK_HABIT_ID]),
    hasSeenHint: boolOr(data[FIELD_HAS_SEEN_HINT], false),
    showMorningRoutine: boolOr(data[FIELD_SHOW_MORNING], true),
    showBedtimeRoutine: boolOr(data[FIELD_SHOW_BEDTIME], true),
    showHouseworkHabit: boolOr(data[FIELD_SHOW_HOUSEWORK_HABIT], true),
    showHouseworkRoutine: boolOr(data[FIELD_SHOW_HOUSEWORK_ROUTINE], true),
  };
}

function stringOrNull(v: unknown): string | null {
  if (typeof v === 'string' && v.length > 0) return v;
  if (typeof v === 'number') return String(v);
  return null;
}

function boolOr(v: unknown, fallback: boolean): boolean {
  return typeof v === 'boolean' ? v : fallback;
}

/** Exported solely for the unit test. Keeps the readSnapshot logic in one place. */
export const __testing = { readSnapshot };

export async function getDailyEssentialsPreferences(
  uid: string,
): Promise<DailyEssentialsSnapshot> {
  const snap = await getDoc(prefsDoc(uid));
  return readSnapshot(snap.exists() ? snap.data() : undefined);
}

export function subscribeToDailyEssentialsPreferences(
  uid: string,
  cb: (snapshot: DailyEssentialsSnapshot) => void,
): Unsubscribe {
  return onSnapshot(prefsDoc(uid), (snap) => {
    cb(readSnapshot(snap.exists() ? snap.data() : undefined));
  });
}

export async function setHouseworkHabitId(
  uid: string,
  id: string | null,
): Promise<void> {
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_HOUSEWORK_HABIT_ID]: id,
      [META_TYPES]: { [FIELD_HOUSEWORK_HABIT_ID]: 'string' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

export async function setHasSeenHint(uid: string, seen: boolean): Promise<void> {
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_HAS_SEEN_HINT]: seen,
      [META_TYPES]: { [FIELD_HAS_SEEN_HINT]: 'boolean' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

const SHOW_CARD_FIELD: Record<
  'morning' | 'bedtime' | 'housework_habit' | 'housework_routine',
  string
> = {
  morning: FIELD_SHOW_MORNING,
  bedtime: FIELD_SHOW_BEDTIME,
  housework_habit: FIELD_SHOW_HOUSEWORK_HABIT,
  housework_routine: FIELD_SHOW_HOUSEWORK_ROUTINE,
};

export async function setShowCard(
  uid: string,
  card: keyof typeof SHOW_CARD_FIELD,
  show: boolean,
): Promise<void> {
  const field = SHOW_CARD_FIELD[card];
  await setDoc(
    prefsDoc(uid),
    {
      [field]: show,
      [META_TYPES]: { [field]: 'boolean' },
      [META_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}
