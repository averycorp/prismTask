/**
 * Client-side medication slot shape used by the Today UI and the
 * dedicated Medication screen. Slots are derived from the user's
 * medication library (Firestore `users/{uid}/medications`) via
 * `features/medication/virtualSlots.ts`; `takenAt` is computed from the
 * matching `medication_doses` for the day, so phone + web see the same
 * source of truth.
 */
export interface MedicationSlot {
  slotKey: string;
  displayTime: string;
  medLabels: string[];
  medIds: string[];
  /** Epoch millis of the latest dose for this slot, or null if pending. */
  takenAt: number | null;
}
