import {
  collection,
  doc,
  getDoc,
  getDocs,
  addDoc,
  setDoc,
  deleteDoc,
  query,
  where,
  orderBy,
  onSnapshot,
  type Unsubscribe,
  type DocumentData,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';
import type { Habit, HabitCompletion, HabitFrequency } from '@/types/habit';
import { timestampToIso, timestampToDateStr } from './converters';

// ── Collection references ─────────────────────────────────────

function habitsCol(uid: string) {
  return collection(firestore, 'users', uid, 'habits');
}

function habitDoc(uid: string, habitId: string) {
  return doc(firestore, 'users', uid, 'habits', habitId);
}

function completionsCol(uid: string) {
  return collection(firestore, 'users', uid, 'habit_completions');
}

/**
 * Deterministic doc id for the natural-key `(habitCloudId, completedDateLocal)`.
 * Mirrors Android's natural-key dedup contract at
 * `SyncService.kt:2022-2052` (`habit_completions` pull path) but applies
 * it on the write path so two devices completing the same habit on the
 * same logical day collapse into a single Firestore doc rather than
 * relying on a Room-layer absorb on pull. Same shape as
 * `moodEnergyLogs.ts moodLogId` (`${dateIso}__${timeOfDay}`).
 *
 * Keys on `completedDateLocal` (TZ-neutral logical day, `YYYY-MM-DD`),
 * not on the legacy `completedDate` epoch ms — two devices in different
 * timezones agree on the logical-day key but disagree on the epoch
 * derived from `new Date(date+"T00:00:00").getTime()`.
 */
function habitCompletionId(habitCloudId: string, completedDateLocal: string): string {
  return `${habitCloudId}__${completedDateLocal}`;
}

function completionDoc(uid: string, completionId: string) {
  return doc(firestore, 'users', uid, 'habit_completions', completionId);
}

// ── Firestore doc → Web Habit ─────────────────────────────────

function docToHabit(docId: string, data: DocumentData, uid: string): Habit {
  // Built-in identity (parity B.4). Mirror Android's `SyncMapper.mapToHabit`
  // shape: when `isBuiltIn` is missing on the Firestore doc but `templateKey`
  // is present, treat the row as built-in (legacy docs written before the
  // `isBuiltIn` column existed).
  const templateKey: string | null =
    typeof data.templateKey === 'string' ? data.templateKey : null;
  const explicitIsBuiltIn =
    typeof data.isBuiltIn === 'boolean' ? data.isBuiltIn : null;
  const isBuiltIn = explicitIsBuiltIn ?? templateKey !== null;
  return {
    id: docId,
    user_id: uid,
    name: data.name ?? '',
    description: data.description ?? null,
    icon: data.icon ?? '⭐',
    color: data.color ?? '#4A90D9',
    category: data.category ?? null,
    frequency: mapFrequency(data.frequencyPeriod),
    target_count: data.targetFrequency ?? 1,
    active_days_json: data.activeDays ?? null,
    is_active: !data.isArchived,
    // Android-authored habits carry `isBookable` through Firestore. Web
    // doesn't write the flag on create/update (see § "Why omission
    // instead of writing-defaults" below); the field stays undefined for
    // web-only habits. Parity audit § B.3b.
    is_bookable: typeof data.isBookable === 'boolean' ? data.isBookable : undefined,
    created_at: timestampToIso(data.createdAt) ?? new Date().toISOString(),
    updated_at: timestampToIso(data.updatedAt) ?? new Date().toISOString(),
    is_built_in: isBuiltIn,
    template_key: templateKey,
    source_version:
      typeof data.sourceVersion === 'number' ? data.sourceVersion : 0,
    is_user_modified:
      typeof data.isUserModified === 'boolean' ? data.isUserModified : false,
    is_detached_from_template:
      typeof data.isDetachedFromTemplate === 'boolean'
        ? data.isDetachedFromTemplate
        : false,
    // Per-habit Today-skip overrides (parity B.5). Missing → null so the
    // editor can distinguish "never set" from "explicitly -1". `-1` / `0`
    // / `>=1` semantics mirror Android `HabitEntity.today_skip_*` columns.
    today_skip_after_complete_days:
      typeof data.todaySkipAfterCompleteDays === 'number'
        ? data.todaySkipAfterCompleteDays
        : null,
    today_skip_before_schedule_days:
      typeof data.todaySkipBeforeScheduleDays === 'number'
        ? data.todaySkipBeforeScheduleDays
        : null,
    // Per-habit streak-forgiveness overrides. Missing/null on the
    // Firestore doc parses as `undefined` — the TS type contract for
    // "inherit global". Android writes `-1` for inherit too, so legacy
    // Android-authored docs round-trip as `-1` here (still > undefined,
    // but resolved to "inherit" by the streaks util's per-field check).
    streak_max_missed_days:
      typeof data.streakMaxMissedDays === 'number'
        ? data.streakMaxMissedDays
        : undefined,
    forgiveness_enabled:
      typeof data.forgivenessEnabled === 'number'
        ? data.forgivenessEnabled
        : undefined,
    forgiveness_allowed_misses:
      typeof data.forgivenessAllowedMisses === 'number'
        ? data.forgivenessAllowedMisses
        : undefined,
    forgiveness_grace_period_days:
      typeof data.forgivenessGracePeriodDays === 'number'
        ? data.forgivenessGracePeriodDays
        : undefined,
  };
}

/**
 * Map Android's `HabitEntity.frequencyPeriod` string to the web
 * `HabitFrequency` union. Android writes six values (see
 * `HabitEntity.kt` and `AddEditHabitScreen.kt:285-300`); web previously
 * collapsed everything except `weekly` to `daily`, which hid
 * fortnightly/monthly/bimonthly/quarterly habits in the web Habits
 * screen by treating them as daily.
 */
function mapFrequency(period: string | undefined): HabitFrequency {
  switch (period) {
    case 'weekly':
    case 'fortnightly':
    case 'monthly':
    case 'bimonthly':
    case 'quarterly':
      return period;
    default:
      return 'daily';
  }
}

function docToCompletion(docId: string, data: DocumentData): HabitCompletion {
  // Prefer the timezone-neutral `completedDateLocal` field (Android v50,
  // migration 49→50). Fall back to deriving the date from the legacy
  // `completedDate` epoch for completions written by older clients.
  // Mirrors Android's `SyncMapper.mapToHabitCompletion`.
  const localKey =
    typeof data.completedDateLocal === 'string' && data.completedDateLocal.length > 0
      ? data.completedDateLocal
      : null;
  return {
    id: docId,
    habit_id: data.habitCloudId ?? '',
    date: localKey ?? timestampToDateStr(data.completedDate) ?? '',
    count: 1,
    created_at: timestampToIso(data.completedAt) ?? new Date().toISOString(),
  };
}

// ── Web Habit → Firestore doc ─────────────────────────────────

/**
 * Build the Firestore payload for a brand-new web-created habit.
 *
 * Web only owns ~10 of the 35+ fields on `HabitEntity` (see
 * `app/src/main/java/com/averycorp/prismtask/data/local/entity/HabitEntity.kt`).
 * The Android-only fields (booking, built-in identity, today-skip
 * windows, nag-suppression overrides, multi-reminder cadence,
 * source-version reconciliation) are intentionally **omitted** from
 * this payload rather than written as `false` / `null` defaults.
 *
 * Why omission instead of writing-defaults: cross-device sync. If the
 * user creates a habit on web, then on Android toggles `isBookable =
 * true`, then re-edits the habit on web, the next web-side `updateHabit`
 * also goes through this merge-only contract. A subsequent
 * Android-side pull then keeps `isBookable = true` because the web
 * payload never overwrote it. If web wrote `isBookable: false` here on
 * create (or in any later partial update), Android's flag would get
 * silently flipped back to `false` on the next sync round-trip.
 *
 * See parity audit `H-S2` (Surface 2 — habits write path).
 */
function habitCreateToDoc(data: {
  name: string;
  description?: string;
  icon?: string;
  color?: string;
  category?: string;
  frequency?: string;
  target_count?: number;
  active_days_json?: string;
  today_skip_after_complete_days?: number;
  today_skip_before_schedule_days?: number;
  streak_max_missed_days?: number | null;
  forgiveness_enabled?: number | null;
  forgiveness_allowed_misses?: number | null;
  forgiveness_grace_period_days?: number | null;
}): Record<string, unknown> {
  const now = Date.now();
  const doc: Record<string, unknown> = {
    name: data.name,
    description: data.description ?? null,
    icon: data.icon ?? '⭐',
    color: data.color ?? '#4A90D9',
    category: data.category ?? null,
    targetFrequency: data.target_count ?? 1,
    frequencyPeriod: data.frequency ?? 'daily',
    activeDays: data.active_days_json ?? null,
    reminderTime: null,
    sortOrder: 0,
    isArchived: false,
    createDailyTask: false,
    createdAt: now,
    updatedAt: now,
    // NOTE: Android-only fields (`isBookable`, `isBooked`, `bookedDate`,
    // `bookedNote`, `trackBooking`, `trackPreviousPeriod`, `hasLogging`,
    // `showStreak`, `reminderTimesPerDay`, `reminderIntervalMillis`,
    // `nagSuppressionOverrideEnabled`, `nagSuppressionDaysOverride`,
    // `isBuiltIn`, `templateKey`, `sourceVersion`, `isUserModified`,
    // `isDetachedFromTemplate`) are intentionally OMITTED. Android's
    // `SyncMapper.mapToHabit` uses sensible defaults for missing keys.
  };
  // Per-habit Today-skip overrides. Web exposes these in `HabitModal.tsx`
  // (parity audit § B.5) — only write when the caller actually supplied
  // a value so Android-side state stays untouched on creates that don't
  // touch the override switches.
  if (data.today_skip_after_complete_days !== undefined) {
    doc.todaySkipAfterCompleteDays = data.today_skip_after_complete_days;
  }
  if (data.today_skip_before_schedule_days !== undefined) {
    doc.todaySkipBeforeScheduleDays = data.today_skip_before_schedule_days;
  }
  // Per-habit streak-forgiveness overrides. Same conditional-include
  // pattern as the Today-skip overrides above so we never clobber
  // Android-side state on creates that don't touch the override
  // switches.
  if (data.streak_max_missed_days !== undefined) {
    doc.streakMaxMissedDays = data.streak_max_missed_days;
  }
  if (data.forgiveness_enabled !== undefined) {
    doc.forgivenessEnabled = data.forgiveness_enabled;
  }
  if (data.forgiveness_allowed_misses !== undefined) {
    doc.forgivenessAllowedMisses = data.forgiveness_allowed_misses;
  }
  if (data.forgiveness_grace_period_days !== undefined) {
    doc.forgivenessGracePeriodDays = data.forgiveness_grace_period_days;
  }
  return doc;
}

function habitUpdateToDoc(
  data: Record<string, unknown>,
  now: number = Date.now(),
): Record<string, unknown> {
  // Caller threads `now` so the patch's `updatedAt` matches what the
  // LWW guard compared against. See `lww.ts` for the rationale.
  const result: Record<string, unknown> = { updatedAt: now };
  if (data.name !== undefined) result.name = data.name;
  if (data.description !== undefined) result.description = data.description;
  if (data.icon !== undefined) result.icon = data.icon;
  if (data.color !== undefined) result.color = data.color;
  if (data.category !== undefined) result.category = data.category;
  if (data.frequency !== undefined) result.frequencyPeriod = data.frequency;
  if (data.target_count !== undefined) result.targetFrequency = data.target_count;
  if (data.active_days_json !== undefined) result.activeDays = data.active_days_json;
  if (data.is_active !== undefined) result.isArchived = !data.is_active;
  // Built-in identity fields (parity B.4). Only written by the reconciler /
  // version-update apply path — the standard `HabitModal` editor never sets
  // these. Mirror Android's column names from `SyncMapper`.
  if (data.source_version !== undefined) result.sourceVersion = data.source_version;
  if (data.is_user_modified !== undefined) result.isUserModified = data.is_user_modified;
  if (data.is_detached_from_template !== undefined) {
    result.isDetachedFromTemplate = data.is_detached_from_template;
  }
  // Per-habit Today-skip overrides (parity B.5). Conditional-include so we
  // never clobber Android-side values for callers that don't touch the
  // override switches (mirrors PR #839 pattern).
  if (data.today_skip_after_complete_days !== undefined) {
    result.todaySkipAfterCompleteDays = data.today_skip_after_complete_days;
  }
  if (data.today_skip_before_schedule_days !== undefined) {
    result.todaySkipBeforeScheduleDays = data.today_skip_before_schedule_days;
  }
  // Per-habit streak-forgiveness overrides. Same conditional-include
  // pattern: only write when the caller actually supplied a value so the
  // Android-side values stay untouched on patches that don't touch the
  // override switches. `null` is the explicit "clear" signal — write it
  // through so Firestore stores NULL and Android resolves to inherit.
  if (data.streak_max_missed_days !== undefined) {
    result.streakMaxMissedDays = data.streak_max_missed_days;
  }
  if (data.forgiveness_enabled !== undefined) {
    result.forgivenessEnabled = data.forgiveness_enabled;
  }
  if (data.forgiveness_allowed_misses !== undefined) {
    result.forgivenessAllowedMisses = data.forgiveness_allowed_misses;
  }
  if (data.forgiveness_grace_period_days !== undefined) {
    result.forgivenessGracePeriodDays = data.forgiveness_grace_period_days;
  }
  return result;
}

// ── CRUD operations ──────────────────────────────────────────

export async function getHabits(uid: string): Promise<Habit[]> {
  const q = query(habitsCol(uid), orderBy('createdAt', 'desc'));
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToHabit(d.id, d.data(), uid));
}

export async function getHabit(uid: string, habitId: string): Promise<Habit | null> {
  const snap = await getDoc(habitDoc(uid, habitId));
  if (!snap.exists()) return null;
  return docToHabit(snap.id, snap.data()!, uid);
}

export async function createHabit(
  uid: string,
  data: {
    name: string;
    description?: string;
    icon?: string;
    color?: string;
    category?: string;
    frequency?: string;
    target_count?: number;
    active_days_json?: string;
    today_skip_after_complete_days?: number;
    today_skip_before_schedule_days?: number;
    streak_max_missed_days?: number | null;
    forgiveness_enabled?: number | null;
    forgiveness_allowed_misses?: number | null;
    forgiveness_grace_period_days?: number | null;
  },
): Promise<Habit> {
  const firestoreData = habitCreateToDoc(data);
  const ref = await addDoc(habitsCol(uid), firestoreData);
  return docToHabit(ref.id, firestoreData, uid);
}

export async function updateHabit(
  uid: string,
  habitId: string,
  data: Record<string, unknown>,
): Promise<Habit> {
  // LWW guard: an in-flight Android booking toggle / streak update
  // would otherwise silently overwrite the web edit (or vice versa).
  // First-create wins on missing docs so habit creation flows are
  // unaffected. Parity audit A.2.
  const now = Date.now();
  const firestoreData = habitUpdateToDoc(data, now);
  await lwwUpdate(habitDoc(uid, habitId), firestoreData as Parameters<typeof lwwUpdate>[1]);
  const snap = await getDoc(habitDoc(uid, habitId));
  return docToHabit(snap.id, snap.data()!, uid);
}

export async function deleteHabit(uid: string, habitId: string): Promise<void> {
  await deleteDoc(habitDoc(uid, habitId));
}

/**
 * Reassign every `habit_completions` doc pointing at `oldHabitId` to
 * `newHabitId`, used by the built-in habit reconciler when collapsing
 * duplicate built-in habits onto a single keeper. Mirrors Android's
 * `HabitCompletionDao.reassignHabitId` (see `BuiltInHabitReconciler.kt:99-102`).
 *
 * Implementation: we can't rename a Firestore doc, and the canonical doc
 * id is `${habit_id}__${date}` (see `habitCompletionId`), so each
 * completion needs to be rewritten at the new id. We skip rewriting when
 * the keeper already has a completion on that date — Android's
 * `reassignHabitId` is a bulk UPDATE that respects the PK constraint;
 * the equivalent on Firestore is "keep the keeper's existing doc, drop
 * the loser's".
 *
 * Best-effort: any per-doc failure is swallowed so the rest of the
 * reassignment proceeds. The next reconciler pass (skipped under the
 * one-shot flag in `BuiltInSyncPreferences`) would re-try the failure
 * if it ever runs again.
 */
export async function reassignCompletions(
  uid: string,
  oldHabitId: string,
  newHabitId: string,
): Promise<void> {
  const loserCompletions = await getCompletions(uid, oldHabitId);
  if (loserCompletions.length === 0) return;
  const keeperCompletions = await getCompletions(uid, newHabitId);
  const keeperDates = new Set(keeperCompletions.map((c) => c.date));

  for (const completion of loserCompletions) {
    const oldRef = completionDoc(
      uid,
      habitCompletionId(oldHabitId, completion.date),
    );
    if (keeperDates.has(completion.date)) {
      // Keeper already has a completion for this date — drop the loser's
      // dupe. Mirrors Android's PK-constraint behaviour (one row per
      // (habit_id, date)).
      try {
        await deleteDoc(oldRef);
      } catch {
        // ignore
      }
      continue;
    }

    const newRef = completionDoc(
      uid,
      habitCompletionId(newHabitId, completion.date),
    );
    const newData = {
      habitCloudId: newHabitId,
      completedDate: new Date(completion.date + 'T00:00:00').getTime(),
      completedDateLocal: completion.date,
      completedAt: new Date(completion.created_at).getTime(),
      notes: null,
    };
    try {
      await setDoc(newRef, newData, { merge: true });
      await deleteDoc(oldRef);
      keeperDates.add(completion.date);
    } catch {
      // Per-doc failure is non-fatal; the reconciler is allowed to drop
      // a completion on the floor rather than abort the whole sweep.
    }
  }
}

// ── Completions ──────────────────────────────────────────────

/**
 * Defense-in-depth read coalesce.
 *
 * Pre-fix random-id docs and the post-fix canonical-id doc can both
 * exist in Firestore for the same `(habit_id, date)` natural key
 * during the cleanup window. Collapse them at read time so the UI
 * never sees doubles. Prefer the canonical-id row (deterministic
 * `${habit_id}__${date}`) when present; otherwise keep the row with
 * the newest `created_at`. Legacy duplicates eventually disappear
 * because `toggleCompletion`'s sweep deletes them on the next toggle.
 */
function coalesceCompletions(rows: HabitCompletion[]): HabitCompletion[] {
  const byKey = new Map<string, HabitCompletion>();
  for (const row of rows) {
    const key = habitCompletionId(row.habit_id, row.date);
    const existing = byKey.get(key);
    if (existing == null) {
      byKey.set(key, row);
      continue;
    }
    const existingIsCanonical = existing.id === key;
    const rowIsCanonical = row.id === key;
    if (rowIsCanonical && !existingIsCanonical) {
      byKey.set(key, row);
    } else if (!rowIsCanonical && existingIsCanonical) {
      // keep existing (canonical wins)
    } else if (row.created_at > existing.created_at) {
      byKey.set(key, row);
    }
  }
  return Array.from(byKey.values());
}

export async function getCompletions(
  uid: string,
  habitId: string,
  startDate?: string,
  endDate?: string,
): Promise<HabitCompletion[]> {
  const q = query(
    completionsCol(uid),
    where('habitCloudId', '==', habitId),
  );

  // Firestore doesn't allow inequality on different fields easily, so
  // we fetch all completions for the habit and filter client-side for date range
  const snap = await getDocs(q);
  let completions = coalesceCompletions(
    snap.docs.map((d) => docToCompletion(d.id, d.data())),
  );

  if (startDate) {
    completions = completions.filter((c) => c.date >= startDate);
  }
  if (endDate) {
    completions = completions.filter((c) => c.date <= endDate);
  }

  return completions;
}

export async function getAllCompletions(uid: string): Promise<HabitCompletion[]> {
  const snap = await getDocs(completionsCol(uid));
  return coalesceCompletions(
    snap.docs.map((d) => docToCompletion(d.id, d.data())),
  );
}

export async function toggleCompletion(
  uid: string,
  habitId: string,
  date: string,
): Promise<{ action: 'added' | 'removed'; completion?: HabitCompletion }> {
  // Natural-key write: doc id = `${habitCloudId}__${completedDateLocal}`.
  // Two devices completing the same habit on the same logical day
  // resolve to the same Firestore doc path, so parallel `setDoc(...,
  // {merge: true})` calls converge to one doc rather than racing into
  // two siblings (the prior `getDocs` pre-query → `addDoc` shape had a
  // TOCTOU window).
  const completionId = habitCompletionId(habitId, date);
  const canonicalRef = completionDoc(uid, completionId);
  const canonicalSnap = await getDoc(canonicalRef);

  if (canonicalSnap.exists()) {
    // Toggle off — also sweep up legacy duplicate docs (different IDs,
    // same natural key) left behind by the pre-fix `addDoc` path.
    await deleteDoc(canonicalRef);
    await deleteLegacyDuplicateCompletions(uid, habitId, date);
    return { action: 'removed' };
  }

  // The `date` argument is the SoD-relative logical day key
  // (`YYYY-MM-DD`) callers in `HabitListScreen` / `TodayScreen`
  // already computed via `useLogicalToday(...)`. It matches Android's
  // `DayBoundary` shape byte-for-byte so cross-device DST comparisons
  // line up. The legacy `completedDate` epoch is still written for
  // back-compat with older Android clients reading via
  // `SyncMapper.mapToHabitCompletion`'s fallback path.
  const dateMs = new Date(date + 'T00:00:00').getTime();
  const now = Date.now();
  const firestoreData = {
    habitCloudId: habitId,
    completedDate: dateMs,
    completedDateLocal: date,
    completedAt: now,
    notes: null,
  };

  // Sweep legacy duplicates first: if a pre-fix random-id doc exists
  // for this natural key, treat the toggle as "remove" (consistent
  // with prior semantics where any matching doc made the toggle a
  // delete).
  const legacy = await findLegacyDuplicateCompletions(uid, habitId, date);
  if (legacy.length > 0) {
    for (const ref of legacy) {
      await deleteDoc(ref);
    }
    return { action: 'removed' };
  }

  await setDoc(canonicalRef, firestoreData, { merge: true });
  const completion = docToCompletion(completionId, firestoreData);
  return { action: 'added', completion };
}

async function findLegacyDuplicateCompletions(
  uid: string,
  habitId: string,
  dateLocal: string,
): Promise<Array<ReturnType<typeof completionDoc>>> {
  // Legacy docs were keyed only on `(habitCloudId, completedDate)`, so
  // we have to query both the TZ-neutral `completedDateLocal` (post-fix
  // canonical key) and the legacy epoch — but we exclude the canonical
  // doc id, since that's handled by the explicit `getDoc` path.
  const dateMs = new Date(dateLocal + 'T00:00:00').getTime();
  const canonicalId = habitCompletionId(habitId, dateLocal);
  const refs: Array<ReturnType<typeof completionDoc>> = [];

  const byLocal = await getDocs(
    query(
      completionsCol(uid),
      where('habitCloudId', '==', habitId),
      where('completedDateLocal', '==', dateLocal),
    ),
  );
  for (const d of byLocal.docs) {
    if (d.id !== canonicalId) refs.push(d.ref);
  }

  const byEpoch = await getDocs(
    query(
      completionsCol(uid),
      where('habitCloudId', '==', habitId),
      where('completedDate', '==', dateMs),
    ),
  );
  for (const d of byEpoch.docs) {
    if (d.id !== canonicalId && !refs.some((r) => r.id === d.id)) {
      refs.push(d.ref);
    }
  }

  return refs;
}

async function deleteLegacyDuplicateCompletions(
  uid: string,
  habitId: string,
  dateLocal: string,
): Promise<void> {
  const refs = await findLegacyDuplicateCompletions(uid, habitId, dateLocal);
  for (const ref of refs) {
    await deleteDoc(ref);
  }
}

// ── Real-time listeners ──────────────────────────────────────

export function subscribeToHabits(
  uid: string,
  callback: (habits: Habit[]) => void,
): Unsubscribe {
  const q = query(habitsCol(uid), orderBy('createdAt', 'desc'));
  return onSnapshot(q, (snap) => {
    const habits = snap.docs.map((d) => docToHabit(d.id, d.data(), uid));
    callback(habits);
  });
}

export function subscribeToCompletions(
  uid: string,
  callback: (completions: HabitCompletion[]) => void,
): Unsubscribe {
  return onSnapshot(completionsCol(uid), (snap) => {
    const completions = coalesceCompletions(
      snap.docs.map((d) => docToCompletion(d.id, d.data())),
    );
    callback(completions);
  });
}
