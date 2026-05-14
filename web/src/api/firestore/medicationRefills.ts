import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';

/**
 * Per-medication refill metadata (`users/{uid}/medication_refills`).
 * Field set mirrors Android `MedicationRefillEntity` + the
 * `medicationRefillToMap` serializer in `SyncMapper.kt`. Web is the
 * second writer here — Android still creates rows from its
 * MedicationRefillScreen and the new top-level MedicationEntity
 * inline-refill path. Both writers use Firestore-generated doc ids;
 * `medicationName` is the natural-key index, so a duplicate name on
 * web is rejected by the upsert helper below.
 *
 * Note: `MedicationRefillEntity` predates the top-level
 * MedicationEntity refactor and was kept as a separate table because
 * older clients (pre-v54) still rely on it. New medications written
 * via PR-1's `medications.ts` should *also* set `pill_count` /
 * `last_refill_date` on the medication doc — this collection is
 * additive metadata for refill reminders.
 */

export interface MedicationRefillDoc {
  /** Firestore doc id. */
  id: string;
  medication_name: string;
  pill_count: number;
  pills_per_dose: number;
  doses_per_day: number;
  last_refill_date: number | null;
  pharmacy_name: string | null;
  pharmacy_phone: string | null;
  reminder_days_before: number;
  created_at: number;
  updated_at: number;
}

export interface MedicationRefillCreateInput {
  medication_name: string;
  pill_count: number;
  pills_per_dose?: number;
  doses_per_day?: number;
  last_refill_date?: number | null;
  pharmacy_name?: string | null;
  pharmacy_phone?: string | null;
  reminder_days_before?: number;
}

export type MedicationRefillUpdateInput = Partial<MedicationRefillCreateInput>;

function refillsCol(uid: string) {
  return collection(firestore, 'users', uid, 'medication_refills');
}

function refillDocRef(uid: string, refillId: string) {
  return doc(firestore, 'users', uid, 'medication_refills', refillId);
}

function docToRefill(docId: string, data: DocumentData): MedicationRefillDoc {
  const now = Date.now();
  return {
    id: docId,
    medication_name:
      typeof data.medicationName === 'string' ? data.medicationName : '',
    pill_count: typeof data.pillCount === 'number' ? data.pillCount : 0,
    pills_per_dose:
      typeof data.pillsPerDose === 'number' ? data.pillsPerDose : 1,
    doses_per_day:
      typeof data.dosesPerDay === 'number' ? data.dosesPerDay : 1,
    last_refill_date:
      typeof data.lastRefillDate === 'number' ? data.lastRefillDate : null,
    pharmacy_name:
      typeof data.pharmacyName === 'string' && data.pharmacyName.length > 0
        ? data.pharmacyName
        : null,
    pharmacy_phone:
      typeof data.pharmacyPhone === 'string' && data.pharmacyPhone.length > 0
        ? data.pharmacyPhone
        : null,
    reminder_days_before:
      typeof data.reminderDaysBefore === 'number'
        ? data.reminderDaysBefore
        : 3,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : now,
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : now,
  };
}

function refillCreateToDoc(
  data: MedicationRefillCreateInput,
  now: number,
): Record<string, unknown> {
  return {
    medicationName: data.medication_name,
    pillCount: data.pill_count,
    pillsPerDose: data.pills_per_dose ?? 1,
    dosesPerDay: data.doses_per_day ?? 1,
    lastRefillDate: data.last_refill_date ?? null,
    pharmacyName: data.pharmacy_name ?? null,
    pharmacyPhone: data.pharmacy_phone ?? null,
    reminderDaysBefore: data.reminder_days_before ?? 3,
    createdAt: now,
    updatedAt: now,
  };
}

function refillUpdateToDoc(
  data: MedicationRefillUpdateInput,
  now: number,
): Record<string, unknown> {
  const result: Record<string, unknown> = { updatedAt: now };
  if (data.medication_name !== undefined)
    result.medicationName = data.medication_name;
  if (data.pill_count !== undefined) result.pillCount = data.pill_count;
  if (data.pills_per_dose !== undefined)
    result.pillsPerDose = data.pills_per_dose;
  if (data.doses_per_day !== undefined) result.dosesPerDay = data.doses_per_day;
  if (data.last_refill_date !== undefined)
    result.lastRefillDate = data.last_refill_date;
  if (data.pharmacy_name !== undefined) result.pharmacyName = data.pharmacy_name;
  if (data.pharmacy_phone !== undefined)
    result.pharmacyPhone = data.pharmacy_phone;
  if (data.reminder_days_before !== undefined)
    result.reminderDaysBefore = data.reminder_days_before;
  return result;
}

export async function getRefills(
  uid: string,
): Promise<MedicationRefillDoc[]> {
  const q = query(refillsCol(uid), orderBy('medicationName', 'asc'));
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToRefill(d.id, d.data()));
}

export async function getRefill(
  uid: string,
  refillId: string,
): Promise<MedicationRefillDoc | null> {
  const snap = await getDoc(refillDocRef(uid, refillId));
  if (!snap.exists()) return null;
  return docToRefill(snap.id, snap.data());
}

export async function createRefill(
  uid: string,
  data: MedicationRefillCreateInput,
): Promise<MedicationRefillDoc> {
  const now = Date.now();
  const firestoreData = refillCreateToDoc(data, now);
  const ref = await addDoc(refillsCol(uid), firestoreData);
  return docToRefill(ref.id, firestoreData);
}

export async function updateRefill(
  uid: string,
  refillId: string,
  data: MedicationRefillUpdateInput,
): Promise<MedicationRefillDoc> {
  const now = Date.now();
  const firestoreData = refillUpdateToDoc(data, now);
  await lwwUpdate(
    refillDocRef(uid, refillId),
    firestoreData as Parameters<typeof lwwUpdate>[1],
  );
  const snap = await getDoc(refillDocRef(uid, refillId));
  return docToRefill(snap.id, snap.data()!);
}

export async function deleteRefill(
  uid: string,
  refillId: string,
): Promise<void> {
  await deleteDoc(refillDocRef(uid, refillId));
}

export function subscribeToRefills(
  uid: string,
  callback: (refills: MedicationRefillDoc[]) => void,
): Unsubscribe {
  const q = query(refillsCol(uid), orderBy('medicationName', 'asc'));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToRefill(d.id, d.data())));
  });
}

// ── Forecast (port of Android `RefillCalculator`) ────────────

export type RefillUrgency =
  | 'HEALTHY'
  | 'UPCOMING'
  | 'URGENT'
  | 'OUT_OF_STOCK';

export interface RefillForecast {
  daysRemaining: number;
  refillDateMillis: number;
  reminderDateMillis: number;
  urgency: RefillUrgency;
}

const MILLIS_PER_DAY = 24 * 60 * 60 * 1000;

const DEFAULT_URGENCY_CONFIG = {
  urgentDays: 3,
  upcomingDays: 7,
};

/**
 * Port of Android `RefillCalculator.forecast`. Pure function so it can
 * live alongside the Firestore helpers without a separate util file.
 */
export function forecastRefill(
  refill: MedicationRefillDoc,
  now: number = Date.now(),
  config = DEFAULT_URGENCY_CONFIG,
): RefillForecast {
  const dailyUsage = Math.max(1, refill.pills_per_dose * refill.doses_per_day);
  const daysRemaining = Math.max(0, Math.floor(refill.pill_count / dailyUsage));
  const anchor = refill.last_refill_date ?? now;
  const refillDate = anchor + daysRemaining * MILLIS_PER_DAY;
  const reminderDate =
    refillDate - refill.reminder_days_before * MILLIS_PER_DAY;
  let urgency: RefillUrgency;
  if (refill.pill_count <= 0) urgency = 'OUT_OF_STOCK';
  else if (daysRemaining < config.urgentDays) urgency = 'URGENT';
  else if (daysRemaining <= config.upcomingDays) urgency = 'UPCOMING';
  else urgency = 'HEALTHY';
  return {
    daysRemaining,
    refillDateMillis: refillDate,
    reminderDateMillis: reminderDate,
    urgency,
  };
}
