import {
  collection,
  onSnapshot,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Real-time mirror of `users/{uid}/medication_slot_overrides` — the
 * per-(medication, slot) time/drift overrides that Android writes via
 * `MedicationSyncMapper.medicationSlotOverrideToMap`. Absence of an
 * override row means the medication uses the slot's defaults as-is.
 *
 * Linkage is via the `medicationCloudId` / `slotCloudId` fields on each
 * doc (Firestore cloud ids, NOT Room local ids). Both are required on
 * every push from Android; web treats missing strings as empty so the
 * UI can decide how to surface orphaned rows.
 */

export interface MedicationSlotOverride {
  /** Firestore doc id (== Android `cloud_id`). */
  id: string;
  /** Cloud id of the medication side of the link. */
  medication_id: string;
  /** Cloud id of the slot side of the link. */
  slot_id: string;
  /** "HH:mm" override of slot's `ideal_time`. Null means "inherit". */
  override_ideal_time: string | null;
  /** Override of slot's `drift_minutes`. Null means "inherit". */
  override_drift_minutes: number | null;
  created_at: number;
  updated_at: number;
}

function overridesCol(uid: string) {
  return collection(firestore, 'users', uid, 'medication_slot_overrides');
}

function docToOverride(
  docId: string,
  data: DocumentData,
): MedicationSlotOverride {
  return {
    id: docId,
    medication_id:
      typeof data.medicationCloudId === 'string' ? data.medicationCloudId : '',
    slot_id: typeof data.slotCloudId === 'string' ? data.slotCloudId : '',
    override_ideal_time:
      typeof data.overrideIdealTime === 'string' &&
      data.overrideIdealTime.length > 0
        ? data.overrideIdealTime
        : null,
    override_drift_minutes:
      typeof data.overrideDriftMinutes === 'number'
        ? data.overrideDriftMinutes
        : null,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : 0,
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

export function subscribeToMedicationSlotOverrides(
  uid: string,
  callback: (overrides: MedicationSlotOverride[]) => void,
): Unsubscribe {
  return onSnapshot(overridesCol(uid), (snap) => {
    callback(snap.docs.map((d) => docToOverride(d.id, d.data())));
  });
}
