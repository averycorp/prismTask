import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToRefills,
  type MedicationRefillDoc,
} from '@/api/firestore/medicationRefills';

/**
 * Live cache of the current user's `medication_refills` rows.
 *
 * Wired by `useFirestoreSync`. The `subscribeToRefills` helper already
 * existed in `medicationRefills.ts`; consumers
 * (`MedicationRefillScreen`, `ClinicalReportPanel`,
 * `clinicalReport.ts`) fetch imperatively today via `getRefills(uid)`,
 * so refill metadata edited on Android won't surface on web until the
 * page reloads. This store flips them to a live cache reading from one
 * Firestore listener.
 *
 * Write path stays on the direct firestore helpers; the listener
 * reconciles the local cache from the resulting snapshot.
 */
interface MedicationRefillsState {
  refills: MedicationRefillDoc[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToRefills: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useMedicationRefillsStore = create<MedicationRefillsState>(
  (set) => ({
    refills: [],

    subscribeToRefills: (uid) =>
      subscribeToRefills(uid, (refills) => {
        set({ refills });
      }),

    reset: () => set({ refills: [] }),
  }),
);
