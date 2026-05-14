import {
  addDoc,
  collection,
  doc,
  getDoc,
  getDocs,
  orderBy,
  query,
  onSnapshot,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';

/**
 * Read + write access to medication documents the Android client syncs at
 * `users/{uid}/medications/{cloudId}`. Web previously only consumed this
 * collection for the batch-ops disambiguation picker; the write path here
 * was added in parity Batch 5 PR-1 so users can manage their medications
 * entirely on web. Field set mirrors `MedicationSyncMapper.medicationToMap`
 * on Android (`app/.../data/remote/mapper/MedicationSyncMapper.kt`).
 *
 * Web only owns the schedule-shaping subset of `MedicationEntity` fields
 * (name, label, schedule, doses, refill, pharmacy, reminder hints). The
 * cloud-sync ferries it back to Android, which then materialises slots,
 * reminders, and the dose-prompt UI. Mirroring habits.ts: we OMIT
 * Android-only fields rather than write defaults so a subsequent Android
 * write doesn't get overwritten on round-trip.
 */

export type MedicationScheduleMode =
  | 'TIMES_OF_DAY'
  | 'SPECIFIC_TIMES'
  | 'INTERVAL'
  | 'AS_NEEDED';

export type MedicationTierKey =
  | 'essential'
  | 'prescription'
  | 'optional'
  | 'as_needed';

export type MedicationReminderModeKey = 'CLOCK' | 'INTERVAL';

export interface MedicationDoc {
  /** Firestore document id (Android calls this the `cloudId`). */
  id: string;
  name: string;
  display_label: string | null;
  notes: string;
  tier: MedicationTierKey | string;
  is_archived: boolean;
  sort_order: number;
  schedule_mode: MedicationScheduleMode;
  /** Comma-separated subset of `"morning,afternoon,evening,night"`. */
  times_of_day: string | null;
  /** Comma-separated `"HH:mm"` strings, e.g. `"08:00,14:30,21:00"`. */
  specific_times: string | null;
  interval_millis: number | null;
  doses_per_day: number;
  pill_count: number | null;
  pills_per_dose: number;
  last_refill_date: number | null;
  pharmacy_name: string | null;
  pharmacy_phone: string | null;
  reminder_days_before: number;
  reminder_mode: MedicationReminderModeKey | null;
  reminder_interval_minutes: number | null;
  prompt_dose_at_log: boolean;
  created_at: number;
  updated_at: number;
}

export interface MedicationCreateInput {
  name: string;
  display_label?: string | null;
  notes?: string;
  tier?: string;
  sort_order?: number;
  schedule_mode?: MedicationScheduleMode;
  times_of_day?: string | null;
  specific_times?: string | null;
  interval_millis?: number | null;
  doses_per_day?: number;
  pill_count?: number | null;
  pills_per_dose?: number;
  last_refill_date?: number | null;
  pharmacy_name?: string | null;
  pharmacy_phone?: string | null;
  reminder_days_before?: number;
  reminder_mode?: MedicationReminderModeKey | null;
  reminder_interval_minutes?: number | null;
  prompt_dose_at_log?: boolean;
}

export type MedicationUpdateInput = Partial<MedicationCreateInput> & {
  is_archived?: boolean;
};

function medicationsCol(uid: string) {
  return collection(firestore, 'users', uid, 'medications');
}

function medicationDoc(uid: string, medicationId: string) {
  return doc(firestore, 'users', uid, 'medications', medicationId);
}

function docToMedication(docId: string, data: DocumentData): MedicationDoc {
  const now = Date.now();
  return {
    id: docId,
    name: typeof data.name === 'string' ? data.name : '',
    display_label:
      typeof data.displayLabel === 'string' && data.displayLabel.length > 0
        ? data.displayLabel
        : null,
    notes: typeof data.notes === 'string' ? data.notes : '',
    tier: typeof data.tier === 'string' ? data.tier : 'essential',
    is_archived: data.isArchived === true,
    sort_order: typeof data.sortOrder === 'number' ? data.sortOrder : 0,
    schedule_mode:
      typeof data.scheduleMode === 'string'
        ? (data.scheduleMode as MedicationScheduleMode)
        : 'TIMES_OF_DAY',
    times_of_day:
      typeof data.timesOfDay === 'string' && data.timesOfDay.length > 0
        ? data.timesOfDay
        : null,
    specific_times:
      typeof data.specificTimes === 'string' && data.specificTimes.length > 0
        ? data.specificTimes
        : null,
    interval_millis:
      typeof data.intervalMillis === 'number' ? data.intervalMillis : null,
    doses_per_day:
      typeof data.dosesPerDay === 'number' ? data.dosesPerDay : 1,
    pill_count: typeof data.pillCount === 'number' ? data.pillCount : null,
    pills_per_dose:
      typeof data.pillsPerDose === 'number' ? data.pillsPerDose : 1,
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
      typeof data.reminderDaysBefore === 'number' ? data.reminderDaysBefore : 3,
    reminder_mode:
      data.reminderMode === 'CLOCK' || data.reminderMode === 'INTERVAL'
        ? data.reminderMode
        : null,
    reminder_interval_minutes:
      typeof data.reminderIntervalMinutes === 'number'
        ? data.reminderIntervalMinutes
        : null,
    prompt_dose_at_log: data.promptDoseAtLog === true,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : now,
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : now,
  };
}

/**
 * Build the Firestore payload for a brand-new web-created medication.
 *
 * Web only writes the schedule-shaping subset of fields. Android-only
 * fields (`slotCloudIds` join list rebuilt by Android pull, doses, slot
 * overrides, refill projections) are intentionally OMITTED so the next
 * Android-side `MedicationSyncMapper.medicationToMap` round-trip is not
 * forced to overwrite them with web defaults. Pattern matches
 * `habits.ts habitCreateToDoc` — see that file for the cross-device
 * round-trip rationale.
 */
function medicationCreateToDoc(
  data: MedicationCreateInput,
  now: number,
): Record<string, unknown> {
  return {
    name: data.name,
    displayLabel: data.display_label ?? null,
    notes: data.notes ?? '',
    tier: data.tier ?? 'essential',
    isArchived: false,
    sortOrder: data.sort_order ?? 0,
    scheduleMode: data.schedule_mode ?? 'TIMES_OF_DAY',
    timesOfDay: data.times_of_day ?? null,
    specificTimes: data.specific_times ?? null,
    intervalMillis: data.interval_millis ?? null,
    dosesPerDay: data.doses_per_day ?? 1,
    pillCount: data.pill_count ?? null,
    pillsPerDose: data.pills_per_dose ?? 1,
    lastRefillDate: data.last_refill_date ?? null,
    pharmacyName: data.pharmacy_name ?? null,
    pharmacyPhone: data.pharmacy_phone ?? null,
    reminderDaysBefore: data.reminder_days_before ?? 3,
    reminderMode: data.reminder_mode ?? null,
    reminderIntervalMinutes: data.reminder_interval_minutes ?? null,
    promptDoseAtLog: data.prompt_dose_at_log ?? false,
    createdAt: now,
    updatedAt: now,
    // NOTE: Android-only fields (`slotCloudIds` — the join list rebuilt
    // by `MedicationSyncMapper.extractSlotCloudIds` on pull) are
    // intentionally OMITTED. Android's `mapToMedication` uses sensible
    // defaults for missing keys; round-tripping a web-only blank list
    // would silently un-link any slots an Android user later assigns.
  };
}

function medicationUpdateToDoc(
  data: MedicationUpdateInput,
  now: number,
): Record<string, unknown> {
  const result: Record<string, unknown> = { updatedAt: now };
  if (data.name !== undefined) result.name = data.name;
  if (data.display_label !== undefined)
    result.displayLabel = data.display_label;
  if (data.notes !== undefined) result.notes = data.notes;
  if (data.tier !== undefined) result.tier = data.tier;
  if (data.is_archived !== undefined) result.isArchived = data.is_archived;
  if (data.sort_order !== undefined) result.sortOrder = data.sort_order;
  if (data.schedule_mode !== undefined)
    result.scheduleMode = data.schedule_mode;
  if (data.times_of_day !== undefined) result.timesOfDay = data.times_of_day;
  if (data.specific_times !== undefined)
    result.specificTimes = data.specific_times;
  if (data.interval_millis !== undefined)
    result.intervalMillis = data.interval_millis;
  if (data.doses_per_day !== undefined) result.dosesPerDay = data.doses_per_day;
  if (data.pill_count !== undefined) result.pillCount = data.pill_count;
  if (data.pills_per_dose !== undefined)
    result.pillsPerDose = data.pills_per_dose;
  if (data.last_refill_date !== undefined)
    result.lastRefillDate = data.last_refill_date;
  if (data.pharmacy_name !== undefined) result.pharmacyName = data.pharmacy_name;
  if (data.pharmacy_phone !== undefined)
    result.pharmacyPhone = data.pharmacy_phone;
  if (data.reminder_days_before !== undefined)
    result.reminderDaysBefore = data.reminder_days_before;
  if (data.reminder_mode !== undefined) result.reminderMode = data.reminder_mode;
  if (data.reminder_interval_minutes !== undefined)
    result.reminderIntervalMinutes = data.reminder_interval_minutes;
  if (data.prompt_dose_at_log !== undefined)
    result.promptDoseAtLog = data.prompt_dose_at_log;
  return result;
}

// ── Reads ────────────────────────────────────────────────────

/**
 * List all non-archived medications. Pre-existing callers (batch-ops
 * disambiguation picker) filter for `is_archived === false`; new CRUD
 * UI calls [getAllMedications] when it needs the archived rows too.
 */
export async function getMedications(uid: string): Promise<MedicationDoc[]> {
  const snap = await getDocs(medicationsCol(uid));
  return snap.docs
    .map((d) => docToMedication(d.id, d.data()))
    .filter((m) => !m.is_archived && m.name.length > 0);
}

/**
 * Full list including archived rows — used by the management screen so
 * users can unarchive a row they previously hid.
 */
export async function getAllMedications(uid: string): Promise<MedicationDoc[]> {
  const q = query(medicationsCol(uid), orderBy('sortOrder', 'asc'));
  const snap = await getDocs(q);
  return snap.docs
    .map((d) => docToMedication(d.id, d.data()))
    .filter((m) => m.name.length > 0);
}

export async function getMedicationsByIds(
  uid: string,
  ids: readonly string[],
): Promise<MedicationDoc[]> {
  if (ids.length === 0) return [];
  const unique = [...new Set(ids)];
  const results: MedicationDoc[] = [];
  for (const id of unique) {
    const snap = await getDoc(doc(firestore, 'users', uid, 'medications', id));
    if (!snap.exists()) continue;
    const med = docToMedication(snap.id, snap.data());
    if (!med.is_archived && med.name.length > 0) results.push(med);
  }
  return results;
}

export async function getMedication(
  uid: string,
  medicationId: string,
): Promise<MedicationDoc | null> {
  const snap = await getDoc(medicationDoc(uid, medicationId));
  if (!snap.exists()) return null;
  return docToMedication(snap.id, snap.data());
}

// ── Writes ───────────────────────────────────────────────────

export async function createMedication(
  uid: string,
  data: MedicationCreateInput,
): Promise<MedicationDoc> {
  const now = Date.now();
  const firestoreData = medicationCreateToDoc(data, now);
  const ref = await addDoc(medicationsCol(uid), firestoreData);
  return docToMedication(ref.id, firestoreData);
}

/**
 * Last-write-wins guard guards the merge so an in-flight Android-side
 * write (slot link, refill projection, archive toggle) isn't silently
 * stomped by a web rename. Mirrors `updateHabit` / `updateTask`. See
 * `lww.ts` for the abort-on-stale contract.
 */
export async function updateMedication(
  uid: string,
  medicationId: string,
  data: MedicationUpdateInput,
): Promise<MedicationDoc> {
  const now = Date.now();
  const firestoreData = medicationUpdateToDoc(data, now);
  await lwwUpdate(
    medicationDoc(uid, medicationId),
    firestoreData as Parameters<typeof lwwUpdate>[1],
  );
  const snap = await getDoc(medicationDoc(uid, medicationId));
  return docToMedication(snap.id, snap.data()!);
}

export async function archiveMedication(
  uid: string,
  medicationId: string,
): Promise<MedicationDoc> {
  return updateMedication(uid, medicationId, { is_archived: true });
}

export async function unarchiveMedication(
  uid: string,
  medicationId: string,
): Promise<MedicationDoc> {
  return updateMedication(uid, medicationId, { is_archived: false });
}

// ── Subscriptions ────────────────────────────────────────────

/**
 * Listen for medication changes (both web-side CRUD and Android-side
 * sync). Snapshot callback fires with the full ordered list every time
 * the underlying collection mutates.
 */
export function subscribeToMedications(
  uid: string,
  callback: (medications: MedicationDoc[]) => void,
): Unsubscribe {
  const q = query(medicationsCol(uid), orderBy('sortOrder', 'asc'));
  return onSnapshot(q, (snap) => {
    const list = snap.docs
      .map((d) => docToMedication(d.id, d.data()))
      .filter((m) => m.name.length > 0);
    callback(list);
  });
}
