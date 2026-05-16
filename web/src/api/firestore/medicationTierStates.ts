import {
  collection,
  onSnapshot,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Real-time mirror of `users/{uid}/medication_tier_states` — the
 * per-day `(medication, slot)` achieved-tier rows that Android writes
 * via `MedicationSyncMapper.medicationTierStateToMap`. Replaces the
 * legacy `self_care_logs.tiers_by_time` JSON column as the canonical
 * tier history.
 *
 * The existing `medicationSlots.ts` module exposes day-bounded /
 * single-row read helpers and a `setTierState` write path keyed by
 * `(dateIso, slotKey)`. This module is the user-wide listener — same
 * collection, but pulls every row so the Zustand cache can serve the
 * Medication History screen without N day-bounded fetches. The two
 * modules deliberately coexist:
 *
 *  - `medicationSlots.ts` is the canonical write path + day-bounded
 *    reads for the dose-prompt UI.
 *  - `medicationTierStates.ts` is the canonical user-wide live read
 *    feeding the Zustand cache.
 *
 * Android writes carry `medicationCloudId` / `slotCloudId` FKs and a
 * `tier` token (`skipped` / `essential` / `prescription` / `complete`).
 * Legacy uppercase values are folded down to canonical form via the
 * same `normalizeTier` rules as `medicationSlots.ts`, so cross-version
 * docs round-trip without loss.
 */

export type MedicationTierToken =
  | 'skipped'
  | 'essential'
  | 'prescription'
  | 'complete';

const VALID_TIERS: ReadonlySet<MedicationTierToken> = new Set([
  'skipped',
  'essential',
  'prescription',
  'complete',
]);

function normalizeTier(raw: unknown): MedicationTierToken {
  if (typeof raw === 'string') {
    if (VALID_TIERS.has(raw as MedicationTierToken))
      return raw as MedicationTierToken;
    // Legacy uppercase values: SKIPPED → skipped, PARTIAL → essential,
    // COMPLETE → complete. Matches `medicationSlots.ts:normalizeTier`.
    if (raw === 'SKIPPED') return 'skipped';
    if (raw === 'PARTIAL') return 'essential';
    if (raw === 'COMPLETE') return 'complete';
  }
  return 'skipped';
}

export interface MedicationTierStateExt {
  /** Firestore doc id (== Android `cloud_id`). */
  id: string;
  /** Cloud id of the medication. Empty when missing on the doc. */
  medication_id: string;
  /** Cloud id of the slot. Empty when missing on the doc. */
  slot_id: string;
  /** ISO LocalDate (YYYY-MM-DD) in device tz — mirrors Android `log_date`. */
  log_date: string;
  tier: MedicationTierToken;
  /** "computed" | "user_set". */
  tier_source: 'computed' | 'user_set';
  /** User-claimed wall-clock epoch ms. Null when never backdated. */
  intended_time: number | null;
  /** Database-write epoch ms. Distinct from `intended_time`. */
  logged_at: number;
  created_at: number;
  updated_at: number;
}

function tierStatesCol(uid: string) {
  return collection(firestore, 'users', uid, 'medication_tier_states');
}

function docToTierState(
  docId: string,
  data: DocumentData,
): MedicationTierStateExt {
  const updatedAt = typeof data.updatedAt === 'number' ? data.updatedAt : 0;
  const tierSource =
    data.tierSource === 'user_set' ? 'user_set' : 'computed';
  return {
    id: docId,
    medication_id:
      typeof data.medicationCloudId === 'string' ? data.medicationCloudId : '',
    slot_id: typeof data.slotCloudId === 'string' ? data.slotCloudId : '',
    log_date: typeof data.logDate === 'string' ? data.logDate : '',
    tier: normalizeTier(data.tier),
    tier_source: tierSource,
    intended_time:
      typeof data.intendedTime === 'number' ? data.intendedTime : null,
    // Mirror Android `mapToMedicationTierState`: fall back to updatedAt
    // when `loggedAt` is absent so every row has a non-zero stamp.
    logged_at: typeof data.loggedAt === 'number' ? data.loggedAt : updatedAt,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : 0,
    updated_at: updatedAt,
  };
}

export function subscribeToMedicationTierStates(
  uid: string,
  callback: (states: MedicationTierStateExt[]) => void,
): Unsubscribe {
  return onSnapshot(tierStatesCol(uid), (snap) => {
    callback(snap.docs.map((d) => docToTierState(d.id, d.data())));
  });
}
