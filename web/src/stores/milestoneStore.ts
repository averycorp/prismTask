import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToMilestones,
  type Milestone,
} from '@/api/firestore/milestones';

/**
 * Live cache of every project milestone across the current user's
 * projects.
 *
 * Wired by `useFirestoreSync`. Android writes milestones via
 * `SyncMapper.milestoneToMap` and CASCADE-FKs them to the parent
 * project on the Room side; on Firestore the linkage is the
 * `projectCloudId` field. Web's project surfaces don't render
 * milestones yet — this cache primes the data for upcoming UI work and
 * lets cross-device milestone edits propagate without a refresh.
 */
interface MilestoneState {
  milestones: Milestone[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToMilestones: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useMilestoneStore = create<MilestoneState>((set) => ({
  milestones: [],

  subscribeToMilestones: (uid) =>
    subscribeToMilestones(uid, (milestones) => {
      set({ milestones });
    }),

  reset: () => set({ milestones: [] }),
}));
