import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  DEFAULT_ADVANCED_TUNING_PREFERENCES,
  getAdvancedTuningPreferences,
  patchAdvancedTuningPreferences,
  subscribeToAdvancedTuningPreferences,
  type AdvancedTuningPatch,
  type AdvancedTuningPreferences,
} from '@/api/firestore/advancedTuningPreferences';
import type { ForgivenessConfig } from '@/utils/streaks';

/**
 * Zustand store mirroring the Firestore-backed Advanced Tuning preferences.
 *
 * Same shape convention as `ndPreferencesStore` / `dashboardStore`:
 * optimistic local apply + fire-and-forget Firestore push, with a real-time
 * listener mounted by `useFirestoreSync`.
 *
 * Consumers read the streak knobs via `selectForgivenessConfig(state)`
 * which returns a `ForgivenessConfig` shaped exactly like the existing
 * `DEFAULT_FORGIVENESS` constant in `streaks.ts`. Callers that need the
 * raw prefs (e.g. the Settings section, the future classifier ports) read
 * the `prefs` field directly.
 *
 * The keyword fields here are persisted only; the classifier port worker
 * (Phase 2 items #2 + #3) will wire them into `nlp.ts` and the future
 * `taskModeClassifier.ts` / `cognitiveLoadClassifier.ts`. Until then they
 * are user-editable but inert — the Settings UI surfaces them with helper
 * copy that names the doc-side intent so the user isn't confused.
 */

interface AdvancedTuningState {
  prefs: AdvancedTuningPreferences;
  loaded: boolean;
  /** Pull from Firestore on auth bootstrap. Idempotent. Best-effort. */
  load: (uid: string) => Promise<void>;
  /** Optimistic local apply + Firestore push. */
  update: (
    uid: string | null,
    patch: AdvancedTuningPatch,
  ) => Promise<void>;
  /** Subscribe to remote updates. Caller manages the unsubscribe. */
  subscribeToPrefs: (uid: string) => Unsubscribe;
  /** Reset to defaults — used on sign-out. */
  reset: () => void;
}

export const useAdvancedTuningStore = create<AdvancedTuningState>((set, get) => ({
  prefs: DEFAULT_ADVANCED_TUNING_PREFERENCES,
  loaded: false,

  load: async (uid: string) => {
    try {
      const remote = await getAdvancedTuningPreferences(uid);
      set({ prefs: remote, loaded: true });
    } catch (e) {
      console.warn('[advancedTuningStore] load failed', e);
      set({ loaded: true });
    }
  },

  update: async (uid, patch) => {
    // Merge nested keyword groups field-by-field so a partial keyword
    // patch (e.g. just `taskModeKeywords.work`) doesn't blow away the
    // other tier strings in local state. The same field-by-field merge
    // applies to per-mode forgiveness overrides so a slider tweak on
    // Play.grace doesn't reset Relax.misses or Work.grace.
    const prev = get().prefs;
    const next: AdvancedTuningPreferences = {
      ...prev,
      forgivenessEnabled: patch.forgivenessEnabled ?? prev.forgivenessEnabled,
      gracePeriodDays: patch.gracePeriodDays ?? prev.gracePeriodDays,
      allowedMisses: patch.allowedMisses ?? prev.allowedMisses,
      forgivenessByMode: {
        work: {
          ...prev.forgivenessByMode.work,
          ...(patch.forgivenessByMode?.work ?? {}),
        },
        play: {
          ...prev.forgivenessByMode.play,
          ...(patch.forgivenessByMode?.play ?? {}),
        },
        relax: {
          ...prev.forgivenessByMode.relax,
          ...(patch.forgivenessByMode?.relax ?? {}),
        },
      },
      taskModeKeywords: {
        ...prev.taskModeKeywords,
        ...(patch.taskModeKeywords ?? {}),
      },
      cognitiveLoadKeywords: {
        ...prev.cognitiveLoadKeywords,
        ...(patch.cognitiveLoadKeywords ?? {}),
      },
      morningCheckInCutoffHour:
        patch.morningCheckInCutoffHour ?? prev.morningCheckInCutoffHour,
    };
    set({ prefs: next });
    if (!uid) return; // signed-out: local-only edit
    try {
      await patchAdvancedTuningPreferences(uid, patch);
    } catch (e) {
      console.warn('[advancedTuningStore] firestore push failed', e);
    }
  },

  subscribeToPrefs: (uid: string) =>
    subscribeToAdvancedTuningPreferences(uid, (remote) =>
      set({ prefs: remote, loaded: true }),
    ),

  reset: () => set({ prefs: DEFAULT_ADVANCED_TUNING_PREFERENCES, loaded: false }),
}));

/**
 * Build a `ForgivenessConfig` (the shape `streaks.ts` consumes) from the
 * current Advanced Tuning prefs. Callers should fall back to
 * `DEFAULT_FORGIVENESS` from `streaks.ts` if `loaded === false` (the user
 * is mid-bootstrap and remote state hasn't arrived yet) — though the
 * defaults here are identical, so the fallback is a belt-and-suspenders
 * guard against a future divergence rather than a behavioural concern
 * today.
 *
 * The returned config carries the user's per-`TaskMode` overrides in
 * `byMode` (`docs/WORK_PLAY_RELAX.md` § *Streak strictness*). Callers
 * who don't know about modes (habit + check-in surfaces) pass no
 * `taskMode` to `calculateStreaks` and `byMode` is simply ignored.
 * Callers who do thread mode (future task-completion streak surfaces)
 * get the wider Play / Relax window automatically.
 */
export function selectForgivenessConfig(
  prefs: AdvancedTuningPreferences,
): ForgivenessConfig {
  return {
    enabled: prefs.forgivenessEnabled,
    gracePeriodDays: prefs.gracePeriodDays,
    allowedMisses: prefs.allowedMisses,
    byMode: {
      work: { ...prefs.forgivenessByMode.work },
      play: { ...prefs.forgivenessByMode.play },
      relax: { ...prefs.forgivenessByMode.relax },
    },
  };
}
