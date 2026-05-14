import { create } from 'zustand';
import {
  getA11yPreferences,
  setHighContrast as setHighContrastOnFirestore,
  setReduceMotion as setReduceMotionOnFirestore,
  subscribeToA11yPreferences,
} from '@/api/firestore/a11yPreferences';
import { getFirebaseUid } from '@/stores/firebaseUid';
import type { Unsubscribe } from 'firebase/firestore';

export type FontScale = 0.9 | 1.0 | 1.1 | 1.25;

interface A11yState {
  fontScale: FontScale;
  highContrast: boolean;
  reducedMotion: boolean;
  setFontScale: (v: FontScale) => void;
  setHighContrast: (v: boolean) => void;
  setReducedMotion: (v: boolean) => void;
  /** Pull cross-device synced a11y prefs at sign-in. Best-effort —
   *  failures fall back to the localStorage-stored values. Parity
   *  audit A.5b. */
  loadFromFirestore: (uid: string) => Promise<void>;
  /** Subscribe to live a11y-pref changes. Returns the unsubscribe
   *  function. */
  subscribeToFirestore: (uid: string) => Unsubscribe;
}

const STORAGE_KEY = 'prismtask_a11y';

function load(): Partial<A11yState> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    return JSON.parse(raw) as Partial<A11yState>;
  } catch {
    return {};
  }
}

function persist(state: Partial<A11yState>) {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        fontScale: state.fontScale,
        highContrast: state.highContrast,
        reducedMotion: state.reducedMotion,
      }),
    );
  } catch {
    // non-fatal
  }
}

const stored = load();

function currentUid(): string | null {
  try {
    return getFirebaseUid();
  } catch {
    return null;
  }
}

/**
 * Client-only accessibility store driving three root-level overrides:
 *   - CSS custom property `--font-scale` (0.9 / 1.0 / 1.1 / 1.25)
 *   - `.a11y-high-contrast` class on <html>
 *   - `.a11y-reduced-motion` class on <html>
 *
 * Applied via `applyA11yToDocument` (below), called on mount + on any
 * state change from `AccessibilitySection`.
 *
 * Signed-in users sync `highContrast` + `reducedMotion` cross-device
 * via `web/src/api/firestore/a11yPreferences.ts` (parity audit A.5b).
 * `fontScale` is NOT synced through this store — Android stores it
 * in `theme_prefs.font_scale` (via `ThemePreferences`), not
 * `a11y_prefs`, so it falls under PR-7's theme sync instead.
 */
export const useA11yStore = create<A11yState>((set, get) => {
  // Self-echo suppression for live subscribers, same shape as
  // `themeStore.ts`.
  let pendingReduce: boolean | null = null;
  let pendingContrast: boolean | null = null;

  return {
    fontScale: (stored.fontScale ?? 1.0) as FontScale,
    highContrast: stored.highContrast ?? false,
    reducedMotion: stored.reducedMotion ?? false,

    setFontScale: (v) => {
      set({ fontScale: v });
      persist({ ...get(), fontScale: v });
      // fontScale is theme_prefs territory; PR-7 (theme sync) owns
      // the Firestore write. Leaving this here as a stub so a future
      // theme-store hookup has a clean point to call into without
      // having to refactor the a11y picker UI.
    },
    setHighContrast: (v) => {
      set({ highContrast: v });
      persist({ ...get(), highContrast: v });
      pendingContrast = v;
      const uid = currentUid();
      if (uid) {
        void setHighContrastOnFirestore(uid, v).catch((err) => {
          console.warn('[a11yStore] Firestore setHighContrast failed', err);
        });
      }
    },
    setReducedMotion: (v) => {
      set({ reducedMotion: v });
      persist({ ...get(), reducedMotion: v });
      pendingReduce = v;
      const uid = currentUid();
      if (uid) {
        void setReduceMotionOnFirestore(uid, v).catch((err) => {
          console.warn('[a11yStore] Firestore setReduceMotion failed', err);
        });
      }
    },

    loadFromFirestore: async (uid) => {
      try {
        const snapshot = await getA11yPreferences(uid);
        const next: Partial<A11yState> = {};
        if (snapshot.reduceMotion !== get().reducedMotion) {
          next.reducedMotion = snapshot.reduceMotion;
        }
        if (snapshot.highContrast !== get().highContrast) {
          next.highContrast = snapshot.highContrast;
        }
        if (Object.keys(next).length === 0) return;
        set(next as A11yState);
        persist({ ...get() });
      } catch (err) {
        console.warn('[a11yStore] loadFromFirestore failed', err);
      }
    },

    subscribeToFirestore: (uid) => {
      return subscribeToA11yPreferences(uid, (snapshot) => {
        // Suppress self-echo per field. Either field may carry the
        // pending value from a recent local edit; the other field is
        // the remote-only update.
        if (pendingReduce === snapshot.reduceMotion) {
          pendingReduce = null;
        }
        if (pendingContrast === snapshot.highContrast) {
          pendingContrast = null;
        }
        const next: Partial<A11yState> = {};
        if (snapshot.reduceMotion !== get().reducedMotion) {
          next.reducedMotion = snapshot.reduceMotion;
        }
        if (snapshot.highContrast !== get().highContrast) {
          next.highContrast = snapshot.highContrast;
        }
        if (Object.keys(next).length === 0) return;
        set(next as A11yState);
        persist({ ...get() });
      });
    },
  };
});

export function applyA11yToDocument(
  state: Pick<A11yState, 'fontScale' | 'highContrast' | 'reducedMotion'>,
): void {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  root.style.setProperty('--font-scale', String(state.fontScale));
  root.classList.toggle('a11y-high-contrast', state.highContrast);
  root.classList.toggle('a11y-reduced-motion', state.reducedMotion);
}
