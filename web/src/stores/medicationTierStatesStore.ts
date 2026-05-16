import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToMedicationTierStates,
  type MedicationTierStateExt,
} from '@/api/firestore/medicationTierStates';

/**
 * Live cache of the current user's `medication_tier_states` rows.
 *
 * Wired by `useFirestoreSync`. Android writes tier-state rows via
 * `MedicationSyncMapper.medicationTierStateToMap`; each row records the
 * achieved tier for a `(medication, slot)` pair on a given local date.
 *
 * `medicationSlots.ts` already exposes day-bounded reads and a
 * `setTierState` write path keyed on `(dateIso, slotKey)` — that's the
 * canonical write path. This store is the user-wide read companion so
 * the Medication History screen can render every day's tier history
 * from one Firestore listener instead of N day-bounded fetches.
 */
interface MedicationTierStatesState {
  states: MedicationTierStateExt[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToTierStates: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useMedicationTierStatesStore = create<MedicationTierStatesState>(
  (set) => ({
    states: [],

    subscribeToTierStates: (uid) =>
      subscribeToMedicationTierStates(uid, (states) => {
        set({ states });
      }),

    reset: () => set({ states: [] }),
  }),
);
