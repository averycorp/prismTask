import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToMedicationSlotOverrides,
  type MedicationSlotOverride,
} from '@/api/firestore/medicationSlotOverrides';

/**
 * Live cache of the current user's `medication_slot_overrides` rows.
 *
 * Wired by `useFirestoreSync`. Android writes overrides via
 * `MedicationSyncMapper.medicationSlotOverrideToMap`; absence of a row
 * means the medication uses the slot's default `ideal_time` /
 * `drift_minutes`. Web doesn't render overrides today, but the cache
 * lets cross-device override edits propagate without a manual refresh
 * once a consumer surface exists.
 */
interface MedicationSlotOverridesState {
  overrides: MedicationSlotOverride[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToOverrides: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useMedicationSlotOverridesStore = create<
  MedicationSlotOverridesState
>((set) => ({
  overrides: [],

  subscribeToOverrides: (uid) =>
    subscribeToMedicationSlotOverrides(uid, (overrides) => {
      set({ overrides });
    }),

  reset: () => set({ overrides: [] }),
}));
