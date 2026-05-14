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
  setDoc,
  where,
  writeBatch,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';

/**
 * Web medication slot definitions + daily tier states. As of parity
 * Batch 5 PR-7, slots live at `users/{uid}/medication_slots` — the
 * same collection Android writes to via `MedicationSyncMapper.medicationSlotToMap`.
 * Web's older `medication_slot_defs` collection is read in a
 * dual-read window (see `getSlotDefs`) until web v1.7.0 has 60 days of
 * telemetry, then the old docs can be reaped offline.
 *
 * Schema-merge (D-E2): web now writes `idealTime`, `driftMinutes`, and
 * `isActive` alongside its existing fields. Defaults match Android's
 * `MedicationSlotEntity.kt:46-51`. Older clients reading the new docs
 * see extra fields they ignore; web reading docs without these fields
 * substitutes the defaults.
 *
 * Tier states live at `users/{uid}/medication_tier_states`. Slot-
 * completion state (per-day med_ids + taken_at) still flows through
 * the existing backend `/daily-essentials/slots` path — this file
 * does NOT touch completions.
 */

/**
 * Lowercase 4-value tier enum aligned with Android's canonical
 * `AchievedTier` ladder. Hierarchical bottom-up: `skipped` (deliberate
 * non-applicable) → `essential` (must-have meds taken) → `prescription`
 * (must-have + prescribed-only also taken) → `complete` (all meds taken).
 *
 * Replaced the earlier 3-value uppercase enum (`SKIPPED` / `PARTIAL` /
 * `COMPLETE`) in v1.5.3 so cross-device sync roundtrips without
 * fidelity loss.
 */
export type MedicationTier = 'skipped' | 'essential' | 'prescription' | 'complete';

const VALID_TIERS: ReadonlySet<MedicationTier> = new Set([
  'skipped',
  'essential',
  'prescription',
  'complete',
]);

/**
 * Normalize a tier value read from Firestore. Accepts the canonical
 * lowercase 4-value form and folds legacy uppercase values
 * (`SKIPPED` / `PARTIAL` / `COMPLETE`, last seen pre-v1.5.3) into the
 * closest canonical match: `PARTIAL` → `essential` (conservative —
 * partial implies at least essential meds taken). Logs a console
 * warning whenever a legacy value is encountered so dev cleanup can
 * be tracked. This helper can be removed in v1.6.0+ once no legacy
 * docs remain.
 */
export function normalizeTier(raw: unknown): MedicationTier {
  if (typeof raw === 'string') {
    if (VALID_TIERS.has(raw as MedicationTier)) return raw as MedicationTier;
    if (raw === 'SKIPPED' || raw === 'PARTIAL' || raw === 'COMPLETE') {
      const mapped: MedicationTier =
        raw === 'SKIPPED' ? 'skipped' : raw === 'PARTIAL' ? 'essential' : 'complete';
      console.warn(
        `[medicationSlots] Normalizing legacy tier "${raw}" → "${mapped}". ` +
          'Resave this slot to migrate the doc to canonical lowercase form.',
      );
      return mapped;
    }
  }
  return 'skipped';
}

export type MedicationReminderMode = 'CLOCK' | 'INTERVAL';

export interface MedicationSlotDef {
  id: string;
  slot_key: string;
  display_name: string;
  sort_order: number;
  /**
   * Per-slot reminder mode override. `null` means "inherit the user's
   * global default" (see `users/{uid}/medication_preferences`). Android
   * is the source of truth for actual reminder delivery — web only
   * persists the setting so it round-trips through Firestore.
   */
  reminder_mode: MedicationReminderMode | null;
  /** Minutes between interval-mode reminders. Only meaningful when the
   *  resolved mode is INTERVAL. */
  reminder_interval_minutes: number | null;
  /**
   * Wall-clock "HH:mm" target time for the slot. Android writes this
   * on every push; web defaults to `"09:00"` for legacy slot_def docs
   * that pre-date the schema-merge (parity Batch 5 PR-7).
   */
  ideal_time: string;
  /**
   * Window (in minutes, default 180) around `ideal_time` during which
   * a dose still counts as on-time. Pure Android-side semantic — web
   * round-trips the value through Firestore.
   */
  drift_minutes: number;
  /**
   * Soft-delete flag — `false` slots remain in the collection so
   * historical tier-state rows stay reference-able. Web treats
   * inactive slots as hidden in pickers.
   */
  is_active: boolean;
  created_at: number;
  updated_at: number;
}

export interface MedicationTierState {
  id: string;
  slot_key: string;
  date_iso: string;
  tier: MedicationTier;
  /** Who set it: `auto` (derived from doses) or `user_set` (manual override). */
  source: 'auto' | 'user_set';
  /**
   * User-claimed wall-clock epoch millis for when the dose was actually
   * taken. NULL when the user hasn't backdated — UI treats `logged_at`
   * as the de-facto intended time in that case. Parity with the
   * Android `medication_tier_states.intended_time` column.
   */
  intended_time: number | null;
  /**
   * Database-write epoch millis. Distinct from `intended_time` for
   * backlogged entries. Always populated; falls back to `updated_at`
   * for legacy docs written before this column existed.
   */
  logged_at: number;
  updated_at: number;
}

// ── Slot definitions ────────────────────────────────────────────

/**
 * Canonical Android-side collection name (parity Batch 5 PR-7).
 * Reads union this with [LEGACY_SLOT_DEFS_COLLECTION] during the
 * dual-read window.
 */
const SLOTS_COLLECTION = 'medication_slots';
/** Legacy web-only collection, retained for dual-read. */
const LEGACY_SLOT_DEFS_COLLECTION = 'medication_slot_defs';

function slotsCol(uid: string) {
  return collection(firestore, 'users', uid, SLOTS_COLLECTION);
}

function slotsDoc(uid: string, id: string) {
  return doc(firestore, 'users', uid, SLOTS_COLLECTION, id);
}

function legacySlotsCol(uid: string) {
  return collection(firestore, 'users', uid, LEGACY_SLOT_DEFS_COLLECTION);
}

/**
 * Default values for the schema-merge fields when reading a legacy
 * doc that pre-dates `idealTime` / `driftMinutes` / `isActive`. These
 * match Android `MedicationSlotEntity.kt` defaults so a dual-read
 * collision (same slot in both collections) doesn't change behaviour.
 */
const DEFAULT_IDEAL_TIME = '09:00';
const DEFAULT_DRIFT_MINUTES = 180;

function docToSlotDef(id: string, data: DocumentData): MedicationSlotDef {
  // Android-canonical doc shape uses `name`; legacy web docs use
  // `displayName`. Prefer the Android field when both are present so a
  // cross-device rename on Android wins on the merge.
  const displayName =
    (typeof data.name === 'string' && data.name.length > 0
      ? data.name
      : null) ??
    (typeof data.displayName === 'string' ? data.displayName : null);
  return {
    id,
    slot_key: data.slotKey ?? '',
    display_name: displayName ?? data.slotKey ?? '',
    sort_order: typeof data.sortOrder === 'number' ? data.sortOrder : 0,
    reminder_mode: parseReminderMode(data.reminderMode),
    reminder_interval_minutes:
      typeof data.reminderIntervalMinutes === 'number'
        ? data.reminderIntervalMinutes
        : null,
    ideal_time:
      typeof data.idealTime === 'string' && data.idealTime.length > 0
        ? data.idealTime
        : DEFAULT_IDEAL_TIME,
    drift_minutes:
      typeof data.driftMinutes === 'number'
        ? data.driftMinutes
        : DEFAULT_DRIFT_MINUTES,
    is_active: data.isActive !== false, // default true when absent
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : Date.now(),
  };
}

function parseReminderMode(raw: unknown): MedicationReminderMode | null {
  if (raw === 'CLOCK' || raw === 'INTERVAL') return raw;
  return null;
}

/**
 * Dedup a union of slots read from both the canonical and legacy
 * collections. Prefer the canonical-collection row when a duplicate
 * exists, then fall back to the row with the newest `updated_at`.
 */
function dedupSlots(rows: readonly MedicationSlotDef[]): MedicationSlotDef[] {
  const byKey = new Map<string, MedicationSlotDef>();
  for (const row of rows) {
    const key = row.slot_key.length > 0 ? row.slot_key : `__id__${row.id}`;
    const existing = byKey.get(key);
    if (existing === undefined) {
      byKey.set(key, row);
      continue;
    }
    // Canonical wins. We can't tell the two apart at this layer, so
    // newest updated_at wins — covers the case where Android wrote a
    // newer canonical row after the legacy row was written.
    if (row.updated_at > existing.updated_at) byKey.set(key, row);
  }
  return [...byKey.values()];
}

export async function getSlotDefs(uid: string): Promise<MedicationSlotDef[]> {
  // Dual-read union: canonical `medication_slots` ∪ legacy
  // `medication_slot_defs`. Canonical wins on collision via
  // `dedupSlots`. Permission errors on the legacy collection are
  // ignored — older firestore.rules may still scope it tightly.
  const [canonicalSnap, legacySnap] = await Promise.all([
    getDocs(query(slotsCol(uid), orderBy('sortOrder', 'asc'))),
    getDocs(legacySlotsCol(uid)).catch(() => null),
  ]);
  const canonical = canonicalSnap.docs.map((d) =>
    docToSlotDef(d.id, d.data()),
  );
  const legacy =
    legacySnap !== null
      ? legacySnap.docs.map((d) => docToSlotDef(d.id, d.data()))
      : [];
  return dedupSlots([...canonical, ...legacy]).sort(
    (a, b) => a.sort_order - b.sort_order,
  );
}

/**
 * Slot create now writes to the canonical `medication_slots`
 * collection and includes the Android-shape `name` + schema-merge
 * fields. Legacy `displayName` is also written for one release so
 * dual-read consumers (Android `MedicationSyncMapper.mapToMedicationSlot`
 * tolerates extra keys) see a familiar shape.
 */
export async function createSlotDef(
  uid: string,
  data: {
    slot_key: string;
    display_name: string;
    sort_order?: number;
    reminder_mode?: MedicationReminderMode | null;
    reminder_interval_minutes?: number | null;
    ideal_time?: string;
    drift_minutes?: number;
    is_active?: boolean;
  },
): Promise<MedicationSlotDef> {
  const now = Date.now();
  const payload = {
    slotKey: data.slot_key,
    name: data.display_name,
    displayName: data.display_name, // legacy alias for backwards compat
    sortOrder: data.sort_order ?? 0,
    reminderMode: data.reminder_mode ?? null,
    reminderIntervalMinutes: data.reminder_interval_minutes ?? null,
    idealTime: data.ideal_time ?? DEFAULT_IDEAL_TIME,
    driftMinutes: data.drift_minutes ?? DEFAULT_DRIFT_MINUTES,
    isActive: data.is_active ?? true,
    createdAt: now,
    updatedAt: now,
  };
  const ref = await addDoc(slotsCol(uid), payload);
  return docToSlotDef(ref.id, payload);
}

export async function updateSlotDef(
  uid: string,
  id: string,
  updates: {
    slot_key?: string;
    display_name?: string;
    sort_order?: number;
    reminder_mode?: MedicationReminderMode | null;
    reminder_interval_minutes?: number | null;
    ideal_time?: string;
    drift_minutes?: number;
    is_active?: boolean;
  },
): Promise<void> {
  // LWW guard — Android-side reminder-mode flips shouldn't be
  // overwritten by a web slot rename. Parity audit A.2.
  const now = Date.now();
  const payload: Record<string, unknown> = { updatedAt: now };
  if (updates.slot_key !== undefined) payload.slotKey = updates.slot_key;
  if (updates.display_name !== undefined) {
    payload.name = updates.display_name;
    payload.displayName = updates.display_name;
  }
  if (updates.sort_order !== undefined) payload.sortOrder = updates.sort_order;
  if (updates.reminder_mode !== undefined) payload.reminderMode = updates.reminder_mode;
  if (updates.reminder_interval_minutes !== undefined) {
    payload.reminderIntervalMinutes = updates.reminder_interval_minutes;
  }
  if (updates.ideal_time !== undefined) payload.idealTime = updates.ideal_time;
  if (updates.drift_minutes !== undefined)
    payload.driftMinutes = updates.drift_minutes;
  if (updates.is_active !== undefined) payload.isActive = updates.is_active;
  await lwwUpdate(slotsDoc(uid, id), payload as Parameters<typeof lwwUpdate>[1]);
}

export async function deleteSlotDef(uid: string, id: string): Promise<void> {
  // Delete the canonical doc. Legacy doc (if any) is reaped by the
  // backfill helper; an explicit delete here would race with the
  // dual-read window.
  await deleteDoc(slotsDoc(uid, id));
}

export function subscribeToSlotDefs(
  uid: string,
  cb: (defs: MedicationSlotDef[]) => void,
): Unsubscribe {
  // Subscribe to the canonical collection; the dual-read concern is
  // a one-time backfill rather than an ongoing merge. Once
  // `backfillLegacySlotDefs` runs on sign-in, both collections show
  // the same data and the subscription on canonical is sufficient.
  return onSnapshot(
    query(slotsCol(uid), orderBy('sortOrder', 'asc')),
    (snap) => cb(snap.docs.map((d) => docToSlotDef(d.id, d.data()))),
  );
}

// ── One-time legacy → canonical backfill (parity Batch 5 PR-7) ───

const BACKFILL_FLAG_PREFIX = 'prismtask.med_slots_backfill_v1.';

function backfillFlagKey(uid: string): string {
  return `${BACKFILL_FLAG_PREFIX}${uid}`;
}

function isBackfilled(uid: string): boolean {
  try {
    return localStorage.getItem(backfillFlagKey(uid)) === '1';
  } catch {
    // Private browsing / SSR fallthrough — pretend we already ran so
    // we don't infinite-loop the migration. The caller's UI will fall
    // back to dual-read anyway.
    return true;
  }
}

function markBackfilled(uid: string): void {
  try {
    localStorage.setItem(backfillFlagKey(uid), '1');
  } catch {
    // Swallow — see `isBackfilled` rationale.
  }
}

/**
 * One-time copy of every doc in `medication_slot_defs/*` into
 * `medication_slots/*` with the schema-merge fields filled in from
 * `MedicationSlotEntity.kt` defaults. Idempotent via `setDoc(merge)`
 * so a partial-failure retry on the next call only fills in the gaps;
 * the localStorage flag prevents the no-op re-runs after the first
 * success.
 *
 * Old `medication_slot_defs` docs are intentionally NOT deleted —
 * older clients still write there, and the dual-read window in
 * `getSlotDefs` lets new clients see those writes for one release.
 *
 * Called from `useFirestoreSync` on sign-in. Safe to call repeatedly
 * across page loads — the flag short-circuits.
 */
export async function backfillLegacySlotDefs(uid: string): Promise<void> {
  if (isBackfilled(uid)) return;
  try {
    const snap = await getDocs(legacySlotsCol(uid));
    if (snap.empty) {
      markBackfilled(uid);
      return;
    }
    // Sequential writes keep the operation predictable; the legacy
    // collection rarely has more than a handful of slot rows.
    for (const d of snap.docs) {
      const data = d.data();
      const merged = {
        slotKey: data.slotKey ?? '',
        name:
          typeof data.name === 'string'
            ? data.name
            : (typeof data.displayName === 'string'
                ? data.displayName
                : data.slotKey ?? ''),
        displayName: data.displayName ?? data.name ?? data.slotKey ?? '',
        sortOrder: typeof data.sortOrder === 'number' ? data.sortOrder : 0,
        reminderMode: data.reminderMode ?? null,
        reminderIntervalMinutes: data.reminderIntervalMinutes ?? null,
        idealTime:
          typeof data.idealTime === 'string'
            ? data.idealTime
            : DEFAULT_IDEAL_TIME,
        driftMinutes:
          typeof data.driftMinutes === 'number'
            ? data.driftMinutes
            : DEFAULT_DRIFT_MINUTES,
        isActive: data.isActive !== false,
        createdAt: data.createdAt ?? Date.now(),
        updatedAt: data.updatedAt ?? Date.now(),
      };
      await setDoc(doc(firestore, 'users', uid, SLOTS_COLLECTION, d.id), merged, {
        merge: true,
      });
    }
    markBackfilled(uid);
  } catch (e) {
    // Don't mark the flag on failure — next sign-in retries. Surface
    // a console warning so dev cleanup can be tracked.
    console.warn('[medicationSlots] slot-defs backfill failed', e);
  }
}

// ── Tier states ─────────────────────────────────────────────────

function tierStatesCol(uid: string) {
  return collection(firestore, 'users', uid, 'medication_tier_states');
}

/** Deterministic doc id: `${dateIso}__${slotKey}` — lets us setDoc()
 *  without hitting Firestore to discover an existing row. */
function tierStateId(dateIso: string, slotKey: string): string {
  return `${dateIso}__${slotKey}`;
}

function tierStateDoc(uid: string, dateIso: string, slotKey: string) {
  return doc(
    firestore,
    'users',
    uid,
    'medication_tier_states',
    tierStateId(dateIso, slotKey),
  );
}

function docToTierState(id: string, data: DocumentData): MedicationTierState {
  const updatedAt =
    typeof data.updatedAt === 'number' ? data.updatedAt : Date.now();
  // intended_time stays null for legacy docs (honest "we don't know").
  // logged_at falls back to updated_at so every row has a non-zero
  // stamp — matches the Android MIGRATION_60_61 backfill convention.
  const intendedTime =
    typeof data.intendedTime === 'number' ? data.intendedTime : null;
  const loggedAt =
    typeof data.loggedAt === 'number' ? data.loggedAt : updatedAt;
  return {
    id,
    slot_key: data.slotKey ?? '',
    date_iso: data.dateIso ?? '',
    tier: normalizeTier(data.tier),
    source: (data.source as 'auto' | 'user_set') ?? 'user_set',
    intended_time: intendedTime,
    logged_at: loggedAt,
    updated_at: updatedAt,
  };
}

export async function getTierStatesForDate(
  uid: string,
  dateIso: string,
): Promise<MedicationTierState[]> {
  const snap = await getDocs(
    query(tierStatesCol(uid), where('dateIso', '==', dateIso)),
  );
  return snap.docs.map((d) => docToTierState(d.id, d.data()));
}

/**
 * Range read used by `MedicationHistoryScreen` (parity Batch 5 PR-5).
 * Filters by `dateIso` inclusive on both ends. Single Firestore range
 * query rather than per-day fetches.
 */
export async function getTierStatesInRange(
  uid: string,
  startDateIso: string,
  endDateIso: string,
): Promise<MedicationTierState[]> {
  const snap = await getDocs(
    query(
      tierStatesCol(uid),
      where('dateIso', '>=', startDateIso),
      where('dateIso', '<=', endDateIso),
    ),
  );
  return snap.docs.map((d) => docToTierState(d.id, d.data()));
}

export async function getTierState(
  uid: string,
  dateIso: string,
  slotKey: string,
): Promise<MedicationTierState | null> {
  const snap = await getDoc(tierStateDoc(uid, dateIso, slotKey));
  if (!snap.exists()) return null;
  return docToTierState(snap.id, snap.data()!);
}

export async function setTierState(
  uid: string,
  dateIso: string,
  slotKey: string,
  tier: MedicationTier,
  source: 'auto' | 'user_set' = 'user_set',
): Promise<MedicationTierState> {
  const ref = tierStateDoc(uid, dateIso, slotKey);
  const now = Date.now();
  // intended_time is intentionally omitted from the merge payload —
  // setTierStateIntendedTime is the dedicated path. logged_at always
  // stamps the moment of write (matches Android's loggedAt = now()
  // default in MedicationTierStateEntity).
  const payload = {
    slotKey,
    dateIso,
    tier,
    source,
    loggedAt: now,
    updatedAt: now,
  };
  await setDoc(ref, payload, { merge: true });
  // Re-fetch so we surface the merged state (existing intended_time
  // stays put rather than getting nulled out by docToTierState).
  const snap = await getDoc(ref);
  return docToTierState(ref.id, snap.data() ?? payload);
}

/**
 * Stamp a user-claimed `intended_time` on the existing tier-state doc.
 * Distinct from {@link setTierState} because intended_time is a user
 * intention about wall-clock — it must not be clobbered by every
 * tier-change write. Parity with the Android
 * `MedicationSlotRepository.setTierStateIntendedTime` write path.
 *
 * Caps `intendedTime` to `Date.now()` — no forward-dating supported.
 */
export async function setTierStateIntendedTime(
  uid: string,
  dateIso: string,
  slotKey: string,
  intendedTime: number,
): Promise<MedicationTierState> {
  const ref = tierStateDoc(uid, dateIso, slotKey);
  const capped = Math.min(intendedTime, Date.now());
  const payload = {
    slotKey,
    dateIso,
    intendedTime: capped,
    updatedAt: Date.now(),
  };
  await setDoc(ref, payload, { merge: true });
  const snap = await getDoc(ref);
  return docToTierState(ref.id, snap.data() ?? payload);
}

export async function clearTierState(
  uid: string,
  dateIso: string,
  slotKey: string,
): Promise<void> {
  await deleteDoc(tierStateDoc(uid, dateIso, slotKey));
}

/**
 * Atomic multi-doc tier-state write for the bulk-mark feature.
 *
 * The bulk-mark UI fans out N tier-state writes for one user action
 * ("mark all 4 morning meds complete"). Without atomicity, a network
 * blip mid-write would leave a torn state — some slots updated,
 * others stale — and the user sees a half-applied bulk action until
 * LWW eventually converges on retry.
 *
 * Firestore's `writeBatch` commits up to 500 docs as a single atomic
 * unit (per the Firestore SDK docs). For our slot count (≤24 active
 * slots/user is generous), one batch is always enough.
 *
 * Each entry uses `set(..., { merge: true })` so existing
 * `intendedTime` survives the bulk write (matches the
 * single-target [setTierState] semantics).
 *
 * Returns the doc ids written. Callers can re-fetch via
 * [getTierStatesForDate] if they need the merged-back state — the
 * batch path doesn't round-trip per doc.
 */
export async function setTierStatesAtomic(
  uid: string,
  updates: ReadonlyArray<{
    dateIso: string;
    slotKey: string;
    tier: MedicationTier;
    source?: 'auto' | 'user_set';
  }>,
): Promise<string[]> {
  if (updates.length === 0) return [];
  const now = Date.now();
  const batch = writeBatch(firestore);
  const ids: string[] = [];
  for (const update of updates) {
    const ref = tierStateDoc(uid, update.dateIso, update.slotKey);
    batch.set(
      ref,
      {
        slotKey: update.slotKey,
        dateIso: update.dateIso,
        tier: update.tier,
        source: update.source ?? 'user_set',
        loggedAt: now,
        updatedAt: now,
      },
      { merge: true },
    );
    ids.push(ref.id);
  }
  await batch.commit();
  return ids;
}
