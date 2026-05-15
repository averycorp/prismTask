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
 * Advanced Tuning preferences synced cross-device with Android.
 *
 * Phase 2 item #4 from `docs/audits/WEB_PILLARS_PHILOSOPHY_AUDIT.md` —
 * mirrors the subset of Android's `AdvancedTuningPreferences` DataStore
 * that the three composable philosophy pillars depend on:
 *
 *   - **Forgiveness-first streaks** — `gracePeriodDays` + `allowedMisses`
 *     (see `docs/FORGIVENESS_FIRST.md` § Configuration).
 *   - **Task Mode keywords** — extra Work / Play / Relax keywords appended
 *     to the built-in `TaskModeClassifier` list (see
 *     `docs/WORK_PLAY_RELAX.md` § Inference rules).
 *   - **Cognitive Load keywords** — extra Easy / Medium / Hard keywords
 *     appended to the built-in `CognitiveLoadClassifier` list (see
 *     `docs/COGNITIVE_LOAD.md` § Inference rules).
 *
 * Stored at `users/{uid}/prefs/advanced_tuning_prefs` to match Android's
 * `PreferenceSyncSpec("advanced_tuning_prefs", advancedTuningDataStore)`
 * registration in `PreferenceSyncModule.kt`. Field names are the raw
 * DataStore key names from `AdvancedTuningPreferences.kt:244-247, 346-353`
 * so Android's `GenericPreferenceSyncService` round-trips them unchanged.
 *
 * Other Android `advanced_tuning_prefs` keys (urgency bands, productivity
 * weights, etc.) are out of scope for this PR; they can layer onto this
 * module as additional fields without schema drift. `getAdvancedTuningPreferences`
 * tolerates and preserves unknown sibling fields via `merge: true` writes.
 *
 * The keyword fields are **persisted only** in this PR — the classifier
 * ports that consume them are tracked separately (Phase 2 items #2, #3).
 */

const DOC_NAME = 'advanced_tuning_prefs';

// Forgiveness streak knobs — keys mirror Android's
// `UserPreferencesDataStore.KEY_FORGIVENESS_*` companion-object constants.
// Android keeps these in `user_prefs` rather than `advanced_tuning_prefs`,
// but for web we centralize them under Advanced Tuning so all three
// pillar knobs live in one Settings card and one Firestore doc.
//
// The `user_prefs` keys remain untouched — we don't write or read them
// from this module. Android's source of truth is its own `user_prefs`
// DataStore; web's source of truth is `advanced_tuning_prefs`. The two
// values can diverge by platform on purpose: per-device tuning is fine
// here (a user might want a tighter grace window on phone than web).
// If a future PR wants cross-device unification it can add a read of
// `user_prefs.forgiveness_*` on web with last-write-wins; this module
// is the local source until then.
const FIELD_FORGIVENESS_ENABLED = 'forgiveness_enabled';
const FIELD_GRACE_PERIOD_DAYS = 'forgiveness_grace_days';
const FIELD_ALLOWED_MISSES = 'forgiveness_allowed_misses';

// Task Mode custom keywords (CSV strings; one row per mode).
// Mirrors `AdvancedTuningPreferences.kt:346-348`.
const FIELD_MODE_CK_WORK = 'mode_custom_keywords_work';
const FIELD_MODE_CK_PLAY = 'mode_custom_keywords_play';
const FIELD_MODE_CK_RELAX = 'mode_custom_keywords_relax';

// Cognitive Load custom keywords (CSV strings; one row per tier).
// Mirrors `AdvancedTuningPreferences.kt:351-353`.
const FIELD_LOAD_CK_EASY = 'load_custom_keywords_easy';
const FIELD_LOAD_CK_MEDIUM = 'load_custom_keywords_medium';
const FIELD_LOAD_CK_HARD = 'load_custom_keywords_hard';

const META_TYPES = '__pref_types';
const META_UPDATED_AT = '__pref_updated_at';

export interface TaskModeCustomKeywords {
  work: string;
  play: string;
  relax: string;
}

export interface CognitiveLoadCustomKeywords {
  easy: string;
  medium: string;
  hard: string;
}

export interface AdvancedTuningPreferences {
  /** Whether forgiveness-first streak math is enabled. Default `true`. */
  forgivenessEnabled: boolean;
  /** Rolling window length in days. Slider range 1..30. Default `7`. */
  gracePeriodDays: number;
  /** Misses tolerated inside the window. Slider range 0..5. Default `1`. */
  allowedMisses: number;
  /** Custom keyword CSVs per Task Mode tier. Default empty strings. */
  taskModeKeywords: TaskModeCustomKeywords;
  /** Custom keyword CSVs per Cognitive Load tier. Default empty strings. */
  cognitiveLoadKeywords: CognitiveLoadCustomKeywords;
}

/**
 * Patch shape — allows touching individual sub-keyword fields without
 * having to pass the full triple. The store merges nested groups
 * field-by-field so a per-tier edit doesn't blow away its siblings.
 */
export interface AdvancedTuningPatch {
  forgivenessEnabled?: boolean;
  gracePeriodDays?: number;
  allowedMisses?: number;
  taskModeKeywords?: Partial<TaskModeCustomKeywords>;
  cognitiveLoadKeywords?: Partial<CognitiveLoadCustomKeywords>;
}

export const DEFAULT_ADVANCED_TUNING_PREFERENCES: AdvancedTuningPreferences = {
  forgivenessEnabled: true,
  gracePeriodDays: 7,
  allowedMisses: 1,
  taskModeKeywords: { work: '', play: '', relax: '' },
  cognitiveLoadKeywords: { easy: '', medium: '', hard: '' },
};

function prefsDoc(uid: string) {
  return doc(firestore, 'users', uid, 'prefs', DOC_NAME);
}

function clampInt(v: unknown, lo: number, hi: number, fallback: number): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) return fallback;
  const i = Math.trunc(v);
  return Math.max(lo, Math.min(hi, i));
}

function readString(v: unknown, fallback: string): string {
  return typeof v === 'string' ? v : fallback;
}

function readBool(v: unknown, fallback: boolean): boolean {
  return typeof v === 'boolean' ? v : fallback;
}

function read(data: DocumentData | undefined): AdvancedTuningPreferences {
  if (!data) return DEFAULT_ADVANCED_TUNING_PREFERENCES;
  return {
    forgivenessEnabled: readBool(
      data[FIELD_FORGIVENESS_ENABLED],
      DEFAULT_ADVANCED_TUNING_PREFERENCES.forgivenessEnabled,
    ),
    gracePeriodDays: clampInt(
      data[FIELD_GRACE_PERIOD_DAYS],
      1,
      30,
      DEFAULT_ADVANCED_TUNING_PREFERENCES.gracePeriodDays,
    ),
    allowedMisses: clampInt(
      data[FIELD_ALLOWED_MISSES],
      0,
      5,
      DEFAULT_ADVANCED_TUNING_PREFERENCES.allowedMisses,
    ),
    taskModeKeywords: {
      work: readString(data[FIELD_MODE_CK_WORK], ''),
      play: readString(data[FIELD_MODE_CK_PLAY], ''),
      relax: readString(data[FIELD_MODE_CK_RELAX], ''),
    },
    cognitiveLoadKeywords: {
      easy: readString(data[FIELD_LOAD_CK_EASY], ''),
      medium: readString(data[FIELD_LOAD_CK_MEDIUM], ''),
      hard: readString(data[FIELD_LOAD_CK_HARD], ''),
    },
  };
}

export async function getAdvancedTuningPreferences(
  uid: string,
): Promise<AdvancedTuningPreferences> {
  const snap = await getDoc(prefsDoc(uid));
  return read(snap.exists() ? snap.data() : undefined);
}

/**
 * Patch a subset of the Advanced Tuning preferences. Only fields present
 * on `patch` are written; other Android-only keys living in the same
 * `advanced_tuning_prefs` doc are preserved by the `merge: true` write.
 *
 * The `__pref_types` envelope tags each touched key so Android's pull
 * side reconstructs the correct `Preferences.Key<T>`. Tag names match
 * the `PreferenceSyncSerialization.encodeValue` contract (`int` for
 * sliders, `bool` for the enabled flag, `string` for keyword CSVs).
 */
export async function patchAdvancedTuningPreferences(
  uid: string,
  patch: AdvancedTuningPatch,
): Promise<void> {
  const payload: Record<string, unknown> = {};
  const typeTags: Record<string, string> = {};

  if (patch.forgivenessEnabled !== undefined) {
    payload[FIELD_FORGIVENESS_ENABLED] = patch.forgivenessEnabled;
    typeTags[FIELD_FORGIVENESS_ENABLED] = 'bool';
  }
  if (patch.gracePeriodDays !== undefined) {
    payload[FIELD_GRACE_PERIOD_DAYS] = clampInt(
      patch.gracePeriodDays,
      1,
      30,
      DEFAULT_ADVANCED_TUNING_PREFERENCES.gracePeriodDays,
    );
    typeTags[FIELD_GRACE_PERIOD_DAYS] = 'int';
  }
  if (patch.allowedMisses !== undefined) {
    payload[FIELD_ALLOWED_MISSES] = clampInt(
      patch.allowedMisses,
      0,
      5,
      DEFAULT_ADVANCED_TUNING_PREFERENCES.allowedMisses,
    );
    typeTags[FIELD_ALLOWED_MISSES] = 'int';
  }

  if (patch.taskModeKeywords) {
    const k = patch.taskModeKeywords;
    if (k.work !== undefined) {
      payload[FIELD_MODE_CK_WORK] = k.work;
      typeTags[FIELD_MODE_CK_WORK] = 'string';
    }
    if (k.play !== undefined) {
      payload[FIELD_MODE_CK_PLAY] = k.play;
      typeTags[FIELD_MODE_CK_PLAY] = 'string';
    }
    if (k.relax !== undefined) {
      payload[FIELD_MODE_CK_RELAX] = k.relax;
      typeTags[FIELD_MODE_CK_RELAX] = 'string';
    }
  }
  if (patch.cognitiveLoadKeywords) {
    const k = patch.cognitiveLoadKeywords;
    if (k.easy !== undefined) {
      payload[FIELD_LOAD_CK_EASY] = k.easy;
      typeTags[FIELD_LOAD_CK_EASY] = 'string';
    }
    if (k.medium !== undefined) {
      payload[FIELD_LOAD_CK_MEDIUM] = k.medium;
      typeTags[FIELD_LOAD_CK_MEDIUM] = 'string';
    }
    if (k.hard !== undefined) {
      payload[FIELD_LOAD_CK_HARD] = k.hard;
      typeTags[FIELD_LOAD_CK_HARD] = 'string';
    }
  }

  if (Object.keys(payload).length === 0) return;
  payload[META_TYPES] = typeTags;
  payload[META_UPDATED_AT] = Date.now();
  await setDoc(prefsDoc(uid), payload, { merge: true });
}

export function subscribeToAdvancedTuningPreferences(
  uid: string,
  cb: (prefs: AdvancedTuningPreferences) => void,
): Unsubscribe {
  return onSnapshot(prefsDoc(uid), (snap) =>
    cb(read(snap.exists() ? snap.data() : undefined)),
  );
}

/**
 * Helper: parse a CSV keyword string into a normalized list. Trims
 * whitespace, lowercases, dedupes, drops empties. Matches the shape
 * Android's `LifeCategoryClassifier.applyCustomKeywords` expects.
 *
 * Exported so the future classifier-port worker can consume it.
 */
export function parseKeywordCsv(raw: string): string[] {
  if (!raw) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  for (const part of raw.split(',')) {
    const norm = part.trim().toLowerCase();
    if (norm.length === 0) continue;
    if (seen.has(norm)) continue;
    seen.add(norm);
    out.push(norm);
  }
  return out;
}
