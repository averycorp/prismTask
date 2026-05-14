import { create } from 'zustand';
import {
  applyThemeToDocument,
  DEFAULT_THEME_KEY,
  migrateLegacyAccentToThemeKey,
  THEMES,
  type ThemeKey,
} from '@/theme/themes';
import {
  getThemePreferences,
  setThemeKey as setThemeKeyOnFirestore,
  subscribeToThemePreferences,
} from '@/api/firestore/themePreferences';
import { getFirebaseUid } from '@/stores/firebaseUid';
import type { Unsubscribe } from 'firebase/firestore';

const THEME_STORAGE_KEY = 'prismtask_theme_key';
const LEGACY_ACCENT_KEY = 'prismtask_accent_color';
const LEGACY_MODE_KEY = 'prismtask_theme';

function loadStoredTheme(): ThemeKey {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    if (stored && stored in THEMES) return stored as ThemeKey;
  } catch {
    // private mode / quota — fall through to migration
  }

  // No explicit pick yet → migrate from the pre-parity light/dark + accent
  // picker. We run the migration once and then persist the result so the
  // user doesn't see a different theme on every reload.
  let migratedKey: ThemeKey = DEFAULT_THEME_KEY;
  try {
    const legacyAccent = localStorage.getItem(LEGACY_ACCENT_KEY);
    migratedKey = migrateLegacyAccentToThemeKey(legacyAccent);
    localStorage.setItem(THEME_STORAGE_KEY, migratedKey);
    // Drop the now-obsolete keys so the migration doesn't keep re-running.
    localStorage.removeItem(LEGACY_ACCENT_KEY);
    localStorage.removeItem(LEGACY_MODE_KEY);
  } catch {
    // Ignore; state still carries DEFAULT_THEME_KEY.
  }
  return migratedKey;
}

interface ThemeState {
  themeKey: ThemeKey;
  setThemeKey: (key: ThemeKey) => void;
  applyTheme: () => void;
  /** Pull the cross-device themeKey from Firestore at sign-in. Replaces
   *  the locally-stored migrated key when Firestore has an explicit
   *  Android-side pick (the user already configured the theme on
   *  another device). Parity audit A.5b. */
  loadFromFirestore: (uid: string) => Promise<void>;
  /** Subscribe to live theme-pref changes on Firestore so cross-device
   *  edits propagate without a manual refresh. Returns the unsubscribe
   *  function. Caller is responsible for invoking it on sign-out. */
  subscribeToFirestore: (uid: string) => Unsubscribe;
}

/**
 * Post-parity theme store. Carries a single `themeKey` picked from the
 * four shipped themes (see `web/src/theme/themes.ts`). The legacy
 * `mode` (light/dark/system) + 12-accent picker was replaced because
 * Android treats each named theme as a self-contained visual system
 * — it has no light variants. Existing web users are auto-migrated on
 * first load via `migrateLegacyAccentToThemeKey`.
 *
 * Signed-in users sync `themeKey` cross-device via
 * `web/src/api/firestore/themePreferences.ts` (parity audit A.5b).
 * The contract:
 *  - Local migrate-on-first-load still runs synchronously so first
 *    paint never sees `DEFAULT_THEME_KEY` flicker.
 *  - On sign-in `loadFromFirestore` pulls the Firestore-owned value
 *    and overwrites the locally-stored key (Firestore is the source
 *    of truth across devices). Migration ran before sign-in so we
 *    don't clobber a user's migrated pick with an empty Firestore
 *    doc — the empty-doc case lands on `DEFAULT_THEME_KEY`, which
 *    equals the migrated default for a fresh user.
 *  - `subscribeToFirestore` keeps the store live across the lifetime
 *    of the session.
 */
export const useThemeStore = create<ThemeState>((set, get) => {
  // Track in-flight writes so the subscriber doesn't immediately
  // bounce our own update back through `set({ themeKey: ... })`
  // (which would clobber any state change we made between the
  // `setThemeKey` call and the snapshot landing). Self-echoes are
  // benign — they just re-apply the same value — but tracking them
  // makes the behaviour deterministic in tests.
  let pendingWrite: ThemeKey | null = null;

  return {
    themeKey: loadStoredTheme(),

    setThemeKey: (key) => {
      try {
        localStorage.setItem(THEME_STORAGE_KEY, key);
      } catch {
        // non-fatal
      }
      set({ themeKey: key });
      get().applyTheme();
      pendingWrite = key;
      // Fire-and-forget Firestore write — failures are non-fatal
      // (offline / signed-out). The next `loadFromFirestore` /
      // subscriber update will reconcile.
      const uid = currentUid();
      if (uid) {
        void setThemeKeyOnFirestore(uid, key).catch((err) => {
          console.warn('[themeStore] Firestore setThemeKey failed', err);
        });
      }
    },

    applyTheme: () => {
      applyThemeToDocument(get().themeKey);
    },

    loadFromFirestore: async (uid) => {
      try {
        const snapshot = await getThemePreferences(uid);
        const remoteKey = snapshot.themeKey;
        if (remoteKey === get().themeKey) return;
        try {
          localStorage.setItem(THEME_STORAGE_KEY, remoteKey);
        } catch {
          /* non-fatal */
        }
        set({ themeKey: remoteKey });
        get().applyTheme();
      } catch (err) {
        console.warn('[themeStore] loadFromFirestore failed', err);
      }
    },

    subscribeToFirestore: (uid) => {
      return subscribeToThemePreferences(uid, (snapshot) => {
        const remoteKey = snapshot.themeKey;
        // Suppress self-echo so our own write doesn't ping-pong.
        if (pendingWrite === remoteKey) {
          pendingWrite = null;
          return;
        }
        if (remoteKey === get().themeKey) return;
        try {
          localStorage.setItem(THEME_STORAGE_KEY, remoteKey);
        } catch {
          /* non-fatal */
        }
        set({ themeKey: remoteKey });
        get().applyTheme();
      });
    },
  };
});

/** Read the current Firebase uid without importing `authStore` (avoids
 *  a circular dependency). Returns null when signed out OR when the
 *  auth store hasn't yet hydrated — `getFirebaseUid` throws in those
 *  cases. */
function currentUid(): string | null {
  try {
    return getFirebaseUid();
  } catch {
    return null;
  }
}
