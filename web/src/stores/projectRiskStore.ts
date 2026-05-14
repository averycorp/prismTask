import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import { subscribeToAllRisks } from '@/api/firestore/projectRisks';
import type { ProjectRisk } from '@/types/projectRisk';

/**
 * Live cache of every project risk across the current user's projects.
 *
 * Populated by the Firestore real-time listener wired from
 * `useFirestoreSync`. Today `ProjectRoadmapScreen` still does per-project
 * imperative loads via `getRisksByProject` — this store is additive so
 * cross-device risk edits land in the user-wide cache without a page
 * refresh.
 */
interface ProjectRiskState {
  risks: ProjectRisk[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToRisks: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useProjectRiskStore = create<ProjectRiskState>((set) => ({
  risks: [],

  subscribeToRisks: (uid) => {
    return subscribeToAllRisks(uid, (risks) => {
      set({ risks });
    });
  },

  reset: () => set({ risks: [] }),
}));
