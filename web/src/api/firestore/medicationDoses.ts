import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDocs,
  onSnapshot,
  query,
  setDoc,
  where,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Per-(medication, slot, logical-day) dose toggle helpers (parity Batch
 * 5 PR-2). Reads + writes `users/{uid}/medication_doses` mirroring
 * Android's `MedicationSyncMapper.medicationDoseToMap` field set.
 *
 * Dedup contract: the natural key on a real (non-synthetic) dose is
 * `(medicationCloudId, slotKey, takenDateLocal)`. Web uses a
 * deterministic doc id `${medCloudId}__${slotKey}__${dateIso}` so two
 * devices toggling the same dose collapse into one doc — matches the
 * `habit_completions` pattern in `habits.ts`. Synthetic-skip rows
 * (`isSyntheticSkip=true`) written by Android's tier-state engine are
 * filtered out of UI reads here. See `MedicationDoseEntity.kt` for the
 * synthetic-skip rationale.
 */

export interface MedicationDoseDoc {
  id: string;
  medication_cloud_id: string | null;
  custom_medication_name: string | null;
  slot_key: string;
  taken_at: number;
  /** ISO `YYYY-MM-DD` in the device's logical day (DayBoundary-aware). */
  taken_date_local: string;
  note: string;
  is_synthetic_skip: boolean;
  dose_amount: string | null;
  created_at: number;
  updated_at: number;
}

function dosesCol(uid: string) {
  return collection(firestore, 'users', uid, 'medication_doses');
}

function doseDoc(uid: string, doseId: string) {
  return doc(firestore, 'users', uid, 'medication_doses', doseId);
}

/**
 * Deterministic id for the `(medicationCloudId, slotKey, dateIso)` triple.
 * Slot keys are lowercase ASCII (`morning`, `08:00`, `anytime`), date is
 * ISO `YYYY-MM-DD`, medication is the Firestore docId — all safe for use
 * in a Firestore doc id without further escaping.
 */
export function medicationDoseId(
  medicationCloudId: string,
  slotKey: string,
  dateIso: string,
): string {
  return `${medicationCloudId}__${slotKey}__${dateIso}`;
}

function docToDose(docId: string, data: DocumentData): MedicationDoseDoc {
  const now = Date.now();
  return {
    id: docId,
    medication_cloud_id:
      typeof data.medicationCloudId === 'string'
        ? data.medicationCloudId
        : null,
    custom_medication_name:
      typeof data.customMedicationName === 'string'
        ? data.customMedicationName
        : null,
    slot_key: typeof data.slotKey === 'string' ? data.slotKey : 'anytime',
    taken_at: typeof data.takenAt === 'number' ? data.takenAt : now,
    taken_date_local:
      typeof data.takenDateLocal === 'string' ? data.takenDateLocal : '',
    note: typeof data.note === 'string' ? data.note : '',
    is_synthetic_skip: data.isSyntheticSkip === true,
    dose_amount:
      typeof data.doseAmount === 'string' ? data.doseAmount : null,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : now,
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : now,
  };
}

/**
 * List all real (non-synthetic) doses for one medication on one logical
 * day. Synthetic-skip rows are filtered here — see
 * `MedicationDoseEntity.kt` for the contract.
 */
export async function getDosesForDay(
  uid: string,
  medicationCloudId: string,
  dateIso: string,
): Promise<MedicationDoseDoc[]> {
  const q = query(
    dosesCol(uid),
    where('medicationCloudId', '==', medicationCloudId),
    where('takenDateLocal', '==', dateIso),
  );
  const snap = await getDocs(q);
  return snap.docs
    .map((d) => docToDose(d.id, d.data()))
    .filter((d) => !d.is_synthetic_skip);
}

/**
 * Range read for the history view (PR-5). Filters synthetic skips.
 */
export async function getDosesInRange(
  uid: string,
  medicationCloudId: string,
  startDateIso: string,
  endDateIso: string,
): Promise<MedicationDoseDoc[]> {
  const q = query(
    dosesCol(uid),
    where('medicationCloudId', '==', medicationCloudId),
    where('takenDateLocal', '>=', startDateIso),
    where('takenDateLocal', '<=', endDateIso),
  );
  const snap = await getDocs(q);
  return snap.docs
    .map((d) => docToDose(d.id, d.data()))
    .filter((d) => !d.is_synthetic_skip);
}

/**
 * Mark a dose as taken for `(medication, slot, day)`. Uses `setDoc(merge)`
 * with the deterministic id so a re-tap is idempotent.
 *
 * `noteOrDoseAmount` lets the caller record either a free-form note or
 * the dose amount captured by `promptDoseAtLog`. Pass `null` for both to
 * record a bare-toggle dose.
 */
export async function logDose(
  uid: string,
  params: {
    medicationCloudId: string;
    slotKey: string;
    dateIso: string;
    takenAt?: number;
    note?: string | null;
    doseAmount?: string | null;
  },
): Promise<MedicationDoseDoc> {
  const now = Date.now();
  const takenAt = params.takenAt ?? now;
  const id = medicationDoseId(
    params.medicationCloudId,
    params.slotKey,
    params.dateIso,
  );
  const payload: Record<string, unknown> = {
    medicationCloudId: params.medicationCloudId,
    customMedicationName: null,
    slotKey: params.slotKey,
    takenAt,
    takenDateLocal: params.dateIso,
    note: params.note ?? '',
    isSyntheticSkip: false,
    doseAmount: params.doseAmount ?? null,
    createdAt: now,
    updatedAt: now,
  };
  await setDoc(doseDoc(uid, id), payload, { merge: true });
  return docToDose(id, payload);
}

/**
 * One-time custom dose ("I took something not in my list"). Mirrors
 * Android's `customMedicationName` path on `MedicationDoseEntity`.
 * Always uses Firestore-generated ids — there's no natural key to
 * deterministically id by.
 */
export async function logCustomDose(
  uid: string,
  params: {
    customMedicationName: string;
    slotKey?: string;
    dateIso: string;
    takenAt?: number;
    note?: string | null;
    doseAmount?: string | null;
  },
): Promise<MedicationDoseDoc> {
  const now = Date.now();
  const takenAt = params.takenAt ?? now;
  const payload: Record<string, unknown> = {
    medicationCloudId: null,
    customMedicationName: params.customMedicationName,
    slotKey: params.slotKey ?? 'anytime',
    takenAt,
    takenDateLocal: params.dateIso,
    note: params.note ?? '',
    isSyntheticSkip: false,
    doseAmount: params.doseAmount ?? null,
    createdAt: now,
    updatedAt: now,
  };
  const ref = await addDoc(dosesCol(uid), payload);
  return docToDose(ref.id, payload);
}

export async function deleteDose(uid: string, doseId: string): Promise<void> {
  await deleteDoc(doseDoc(uid, doseId));
}

/**
 * Listen for dose changes for one medication. Snapshot fires with the
 * full set (synthetic-skip rows still included so callers can decide
 * whether to filter — most UI callers should, but `MedicationHistoryScreen`
 * in PR-5 may surface the skip anchors).
 */
export function subscribeToDoses(
  uid: string,
  medicationCloudId: string,
  callback: (doses: MedicationDoseDoc[]) => void,
): Unsubscribe {
  const q = query(
    dosesCol(uid),
    where('medicationCloudId', '==', medicationCloudId),
  );
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToDose(d.id, d.data())));
  });
}
