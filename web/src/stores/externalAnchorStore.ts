import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import { subscribeToAllAnchors } from '@/api/firestore/externalAnchors';
import type { ExternalAnchorRecord } from '@/types/externalAnchor';

/**
 * Live cache of every external anchor (calendar / link / file ref) across
 * the current user's projects.
 *
 * Populated by the Firestore real-time listener wired from
 * `useFirestoreSync`. Today `ProjectRoadmapScreen` still does per-project
 * imperative loads via `getAnchorsByProject` — this store is additive so
 * cross-device anchor edits land in the user-wide cache without a page
 * refresh. Malformed anchor JSON is already filtered out by
 * `subscribeToAllAnchors`.
 */
interface ExternalAnchorState {
  anchors: ExternalAnchorRecord[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToAnchors: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useExternalAnchorStore = create<ExternalAnchorState>((set) => ({
  anchors: [],

  subscribeToAnchors: (uid) => {
    return subscribeToAllAnchors(uid, (anchors) => {
      set({ anchors });
    });
  },

  reset: () => set({ anchors: [] }),
}));
