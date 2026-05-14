import {
  doc,
  getDoc,
  onSnapshot,
  setDoc,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { DEFAULT_THEME_KEY, type ThemeKey } from '@/theme/themes';

/**
 * Theme preferences synced cross-device with Android.
 *
 * Mirrors Android's `ThemePreferencesSyncService` (NOT the generic
 * `PreferenceSyncSerialization` envelope used by `aiPreferences.ts` and
 * `taskBehaviorPreferences.ts`). The Android side intentionally
 * excludes `theme_prefs` from `PreferenceSyncModule` because the
 * bespoke service:
 *  - writes to `users/{uid}/settings/theme_preferences` (NOT
 *    `users/{uid}/prefs/theme_prefs`).
 *  - uses a flat field shape: `prism_theme`, `theme_mode`,
 *    `accent_color`, `background_color`, `surface_color`,
 *    `error_color`, `font_scale`, `priority_color_*`,
 *    `recent_custom_colors`, plus `updated_at`.
 *  - guards via `remote.updated_at > local.updated_at` LWW at the
 *    pull side (`ThemePreferencesSyncService.applyRemoteData`).
 *
 * Parity audit A.5b. Web only writes the two fields it owns:
 * `prism_theme` (the four-theme palette enum) and `font_scale` (the
 * web a11y store value). Android-side priority/accent overrides and
 * `theme_mode` are intentionally NOT touched by web writes so they
 * survive the cross-device roundtrip. Web pull side reads
 * `prism_theme` + `font_scale` only.
 *
 * Default values match `DEFAULT_THEME_KEY` and Android's
 * `getFontScale()` default (1.0).
 */

const DOC_NAME = 'theme_preferences';
const FIELD_PRISM_THEME = 'prism_theme';
const FIELD_FONT_SCALE = 'font_scale';
const FIELD_UPDATED_AT = 'updated_at';

const VALID_THEMES: ReadonlySet<string> = new Set([
  'CYBERPUNK',
  'SYNTHWAVE',
  'MATRIX',
  'VOID',
]);

function prefsDoc(uid: string) {
  return doc(firestore, 'users', uid, 'settings', DOC_NAME);
}

function readThemeKey(data: DocumentData | undefined): ThemeKey {
  if (!data) return DEFAULT_THEME_KEY;
  const v = data[FIELD_PRISM_THEME];
  if (typeof v === 'string' && VALID_THEMES.has(v)) return v as ThemeKey;
  return DEFAULT_THEME_KEY;
}

function readFontScale(data: DocumentData | undefined): number {
  if (!data) return 1.0;
  const v = data[FIELD_FONT_SCALE];
  if (typeof v === 'number' && Number.isFinite(v)) {
    // Android's setter accepts arbitrary floats; web's `FontScale`
    // type only emits 0.9 / 1.0 / 1.1 / 1.25 from the picker but we
    // still let the float pass through here so cross-device reads
    // pick up an Android-side custom value rather than snapping to
    // the nearest web tier on every read.
    return v;
  }
  return 1.0;
}

export interface ThemePreferencesSnapshot {
  themeKey: ThemeKey;
  fontScale: number;
}

export async function getThemePreferences(
  uid: string,
): Promise<ThemePreferencesSnapshot> {
  const snap = await getDoc(prefsDoc(uid));
  const data = snap.exists() ? snap.data() : undefined;
  return {
    themeKey: readThemeKey(data),
    fontScale: readFontScale(data),
  };
}

/**
 * Write the user's `themeKey` to Firestore at
 * `users/{uid}/settings/theme_preferences.prism_theme`. Merge=true so
 * Android-side priority/accent overrides survive. Stamps
 * `updated_at` so Android's LWW guard (`remote > local`) lets the
 * pull side apply the new value.
 */
export async function setThemeKey(uid: string, themeKey: ThemeKey): Promise<void> {
  if (!VALID_THEMES.has(themeKey)) return;
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_PRISM_THEME]: themeKey,
      [FIELD_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

/**
 * Write the user's `fontScale` to Firestore. Same merge + LWW shape
 * as [setThemeKey].
 */
export async function setFontScale(uid: string, fontScale: number): Promise<void> {
  if (!Number.isFinite(fontScale)) return;
  await setDoc(
    prefsDoc(uid),
    {
      [FIELD_FONT_SCALE]: fontScale,
      [FIELD_UPDATED_AT]: Date.now(),
    },
    { merge: true },
  );
}

/** Live Firestore subscriber. Calls back with the latest snapshot
 *  whenever the doc changes (either device's edit). */
export function subscribeToThemePreferences(
  uid: string,
  cb: (snapshot: ThemePreferencesSnapshot) => void,
): Unsubscribe {
  return onSnapshot(prefsDoc(uid), (snap) => {
    const data = snap.exists() ? snap.data() : undefined;
    cb({
      themeKey: readThemeKey(data),
      fontScale: readFontScale(data),
    });
  });
}
