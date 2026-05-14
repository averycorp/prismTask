import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import { subscribeToAllPhases } from '@/api/firestore/projectPhases';
import type { ProjectPhase } from '@/types/projectPhase';

/**
 * Live cache of every project phase across the current user's projects.
 *
 * Populated by the Firestore real-time listener wired from
 * `useFirestoreSync`. Today `ProjectRoadmapScreen` still does per-project
 * imperative loads via `getPhasesByProject` — this store is additive so
 * cross-device phase edits land in the user-wide cache and downstream
 * surfaces (roadmap, future phase pickers) can read the latest set
 * without their own fetch.
 */
interface ProjectPhaseState {
  phases: ProjectPhase[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToPhases: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useProjectPhaseStore = create<ProjectPhaseState>((set) => ({
  phases: [],

  subscribeToPhases: (uid) => {
    return subscribeToAllPhases(uid, (phases) => {
      set({ phases });
    });
  },

  reset: () => set({ phases: [] }),
}));
