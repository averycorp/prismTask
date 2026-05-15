import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToRules,
  type BoundaryRule,
} from '@/api/firestore/boundaryRules';

/**
 * Live cache of the current user's boundary rules.
 *
 * Populated by the Firestore real-time listener wired from
 * `useFirestoreSync`. `BoundaryTodayBanner.tsx` and
 * `BoundariesSection.tsx` previously imperatively re-fetched via
 * `getRules(uid)` on each mount, which left them stale across devices
 * — adding a rule on Android wouldn't surface in the desktop banner
 * until the page reloaded. Closes parity audit § A.1b for
 * `boundaryRules`.
 *
 * Mutating writes (create / update / delete) still go through the
 * direct firestore helpers; the listener will reconcile the local
 * cache from the resulting snapshot. Consumers that need the freshest
 * rule set without waiting for the snapshot round-trip can keep
 * calling `getRules` directly — this store is additive.
 */
interface BoundaryRulesState {
  rules: BoundaryRule[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToRules: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useBoundaryRulesStore = create<BoundaryRulesState>((set) => ({
  rules: [],

  subscribeToRules: (uid) => {
    return subscribeToRules(uid, (rules) => {
      set({ rules });
    });
  },

  reset: () => set({ rules: [] }),
}));
