import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToMedications,
  type MedicationDoc,
} from '@/api/firestore/medications';

/**
 * Live cache of the current user's medications.
 *
 * Populated by the Firestore real-time listener wired from
 * `useFirestoreSync`. Existing consumers (`MedicationSlotList`,
 * `clinicalReport`, `virtualSlots`, `batchStore`) fetch imperatively
 * via `getMedications(uid)` today, so a medication added or archived
 * on Android won't surface on the web until the page reloads. Closes
 * parity audit § A.1b residual for `medications` — `subscribeToMedications`
 * already existed in `medications.ts`; it just wasn't wired.
 *
 * Mutating writes (createMedication / updateMedication / archive /
 * unarchive) still go through the direct firestore helpers; the
 * listener reconciles the local cache from the resulting snapshot.
 */
interface MedicationsState {
  medications: MedicationDoc[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToMedications: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useMedicationsStore = create<MedicationsState>((set) => ({
  medications: [],

  subscribeToMedications: (uid) => {
    return subscribeToMedications(uid, (medications) => {
      set({ medications });
    });
  },

  reset: () => set({ medications: [] }),
}));
