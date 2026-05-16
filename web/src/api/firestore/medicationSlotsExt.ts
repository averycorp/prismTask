import {
  collection,
  onSnapshot,
  orderBy,
  query,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Canonical Android-shape read of `users/{uid}/medication_slots` —
 * mirrors `MedicationSlotEntity` (Kotlin) field-for-field.
 *
 * The existing `medicationSlots.ts` module exposes `MedicationSlotDef`,
 * a dual-read web-flavoured shape that folds the legacy
 * `medication_slot_defs` collection into the same surface. This module
 * is the lean Android-canonical version: same Firestore path, no
 * dual-read, no `slot_key` (Android uses doc id as the identifier), and
 * exposes `name` rather than `display_name`. Both can coexist because
 * each writes/reads its own type — consumers pick whichever shape fits
 * better. Future work may unify them once the legacy collection is
 * fully reaped.
 */

export interface MedicationSlotExt {
  /** Firestore doc id (Android `cloud_id`). */
  id: string;
  name: string;
  /** Wall-clock "HH:mm" string (e.g. "09:00"). */
  ideal_time: string;
  /** ± acceptable window in minutes. Defaults to 180 (matches Android). */
  drift_minutes: number;
  sort_order: number;
  is_active: boolean;
  /** "CLOCK" | "INTERVAL" | null (inherit global default). */
  reminder_mode: 'CLOCK' | 'INTERVAL' | null;
  reminder_interval_minutes: number | null;
  created_at: number;
  updated_at: number;
}

function slotsCol(uid: string) {
  return collection(firestore, 'users', uid, 'medication_slots');
}

function parseReminderMode(raw: unknown): 'CLOCK' | 'INTERVAL' | null {
  if (raw === 'CLOCK' || raw === 'INTERVAL') return raw;
  return null;
}

function docToSlot(docId: string, data: DocumentData): MedicationSlotExt {
  // Prefer Android-canonical `name`; legacy web docs may carry
  // `displayName`. Mirrors the same fallback used in
  // `medicationSlots.ts:docToSlotDef`.
  const name =
    (typeof data.name === 'string' && data.name.length > 0 ? data.name : null) ??
    (typeof data.displayName === 'string' ? data.displayName : '');
  return {
    id: docId,
    name,
    ideal_time:
      typeof data.idealTime === 'string' && data.idealTime.length > 0
        ? data.idealTime
        : '09:00',
    drift_minutes:
      typeof data.driftMinutes === 'number' ? data.driftMinutes : 180,
    sort_order: typeof data.sortOrder === 'number' ? data.sortOrder : 0,
    is_active: data.isActive !== false,
    reminder_mode: parseReminderMode(data.reminderMode),
    reminder_interval_minutes:
      typeof data.reminderIntervalMinutes === 'number'
        ? data.reminderIntervalMinutes
        : null,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : 0,
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

export function subscribeToMedicationSlotsExt(
  uid: string,
  callback: (slots: MedicationSlotExt[]) => void,
): Unsubscribe {
  const q = query(slotsCol(uid), orderBy('sortOrder', 'asc'));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToSlot(d.id, d.data())));
  });
}
