import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToMedicationSlotsExt,
  type MedicationSlotExt,
} from '@/api/firestore/medicationSlotsExt';

/**
 * Live cache of the current user's `medication_slots` (Android-canonical
 * shape) rows.
 *
 * The existing `medicationSlotsStore` covers the web-flavoured
 * `MedicationSlotDef` shape with dual-read against the legacy
 * `medication_slot_defs` collection. This store is the lean
 * Android-canonical view of the same collection — Android writes are
 * keyed off `MedicationSlotEntity` directly, so consumers that want the
 * raw Android fields (`name`, `ideal_time`, `drift_minutes`,
 * `is_active`, `reminder_mode`, `reminder_interval_minutes`) read
 * here. The two stores deliberately coexist; eventual unification is
 * tracked as a follow-up once the legacy collection is fully reaped.
 */
interface MedicationSlotsExtState {
  slots: MedicationSlotExt[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToMedicationSlots: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useMedicationSlotsExtStore = create<MedicationSlotsExtState>(
  (set) => ({
    slots: [],

    subscribeToMedicationSlots: (uid) =>
      subscribeToMedicationSlotsExt(uid, (slots) => {
        set({ slots });
      }),

    reset: () => set({ slots: [] }),
  }),
);
