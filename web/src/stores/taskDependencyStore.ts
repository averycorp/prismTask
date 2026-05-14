import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import { subscribeToDependencies } from '@/api/firestore/taskDependencies';
import type { TaskDependency } from '@/types/taskDependency';

/**
 * Live cache of the current user's task-dependency edges.
 *
 * Populated by the Firestore real-time listener wired from
 * `useFirestoreSync`. Today only `ProjectRoadmapScreen` reads
 * dependencies, and it fetches imperatively via `getAllDependencies` —
 * this store is additive so future surfaces (DependencyEditor, B.12)
 * can read the latest edge set without their own one-shot fetch and
 * stays consistent across devices without a page refresh.
 */
interface TaskDependencyState {
  dependencies: TaskDependency[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToDependencies: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useTaskDependencyStore = create<TaskDependencyState>((set) => ({
  dependencies: [],

  subscribeToDependencies: (uid) => {
    return subscribeToDependencies(uid, (dependencies) => {
      set({ dependencies });
    });
  },

  reset: () => set({ dependencies: [] }),
}));
