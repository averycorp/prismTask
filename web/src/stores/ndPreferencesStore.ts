import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  DEFAULT_ND_PREFERENCES,
  getNdPreferences,
  patchNdPreferences,
  subscribeToNdPreferences,
  type NdPreferences,
} from '@/api/firestore/ndPreferences';

/**
 * Thin Zustand store mirroring Firestore-backed ND preferences.
 *
 * Follows the same pattern as `medicationPreferencesStore.ts` — optimistic
 * local apply + fire-and-forget Firestore push, with a Firestore listener
 * applied via `useFirestoreSync` (added in a follow-up wiring PR).
 *
 * The store does NOT itself wire up the listener; `useFirestoreSync.ts`
 * imports `subscribeToPrefs` and mounts it. Same convention as the rest
 * of the store layer.
 */

interface NdPreferencesState {
  prefs: NdPreferences;
  loaded: boolean;
  /** Pull from Firestore on auth bootstrap. Idempotent. */
  load: (uid: string) => Promise<void>;
  /** Apply a partial patch locally + push to Firestore. */
  update: (uid: string, patch: Partial<NdPreferences>) => Promise<void>;
  /** Subscribe to remote updates. Caller manages the unsubscribe. */
  subscribeToPrefs: (uid: string) => Unsubscribe;
  /** Reset to defaults — used on sign-out. */
  reset: () => void;
}

export const useNdPreferencesStore = create<NdPreferencesState>((set, get) => ({
  prefs: DEFAULT_ND_PREFERENCES,
  loaded: false,

  load: async (uid: string) => {
    try {
      const remote = await getNdPreferences(uid);
      set({ prefs: remote, loaded: true });
    } catch (e) {
      console.warn('Failed to load ND preferences from Firestore', e);
      set({ loaded: true });
    }
  },

  update: async (uid: string, patch: Partial<NdPreferences>) => {
    const merged = applyModeCascades({ ...get().prefs, ...patch }, patch);
    set({ prefs: merged });
    try {
      // Push the full effective patch (post-cascade) so Android sees the
      // mode-and-sub-settings transition in one write rather than two.
      const cascadePatch = effectivePatch(get().prefs, merged, patch);
      await patchNdPreferences(uid, cascadePatch);
    } catch (e) {
      console.warn('Failed to push ND preferences to Firestore', e);
    }
  },

  subscribeToPrefs: (uid: string) =>
    subscribeToNdPreferences(uid, (remote) => set({ prefs: remote, loaded: true })),

  reset: () => set({ prefs: DEFAULT_ND_PREFERENCES, loaded: false }),
}));

/**
 * Mode-activation cascades. When a parent mode toggles from off → on,
 * flip all of its sub-settings on. When it toggles from on → off, flip
 * them all off. Mirrors Android's `NdPreferencesDataStore` behavior so
 * cross-device toggles produce identical results.
 *
 * Note: individual sub-setting changes never auto-disable a parent mode.
 */
function applyModeCascades(
  next: NdPreferences,
  patch: Partial<NdPreferences>,
): NdPreferences {
  if (patch.calmModeEnabled !== undefined) {
    const on = patch.calmModeEnabled;
    return {
      ...next,
      reduceAnimations: on,
      mutedColorPalette: on,
      quietMode: on,
      reduceHaptics: on,
      softContrast: on,
    };
  }
  if (patch.adhdModeEnabled !== undefined) {
    const on = patch.adhdModeEnabled;
    return {
      ...next,
      completionAnimations: on,
      streakCelebrations: on,
      showProgressBars: on,
      forgivenessStreaks: on,
    };
  }
  if (patch.focusReleaseModeEnabled !== undefined) {
    const on = patch.focusReleaseModeEnabled;
    return {
      ...next,
      goodEnoughTimersEnabled: on,
      antiReworkEnabled: on,
      softWarningEnabled: on,
      shipItCelebrationsEnabled: on,
    };
  }
  return next;
}

/**
 * Diff `prev → applied` and include only fields that actually changed in
 * the Firestore patch. Avoids re-writing untouched fields and keeps the
 * doc small.
 */
function effectivePatch(
  prev: NdPreferences,
  applied: NdPreferences,
  rawPatch: Partial<NdPreferences>,
): Partial<NdPreferences> {
  const out: Partial<NdPreferences> = { ...rawPatch };
  (Object.keys(applied) as (keyof NdPreferences)[]).forEach((k) => {
    if (applied[k] !== prev[k] && out[k] === undefined) {
      (out as Record<string, unknown>)[k] = applied[k];
    }
  });
  return out;
}
