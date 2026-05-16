import {
  runTransaction,
  serverTimestamp,
  setDoc,
  type DocumentData,
  type DocumentReference,
  type FieldValue,
  type Transaction,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Last-write-wins (LWW) precondition guard for Firestore merge / update
 * writes that race with cross-device pushes.
 *
 * Without a guard, `setDoc(ref, patch, { merge: true })` or
 * `updateDoc(ref, patch)` happily overwrites a newer Android-side write
 * when the web client's wall clock is behind, because Firestore's
 * default merge semantics don't compare timestamps. Mirrors Android's
 * `SyncMapper` LWW contract: the push side runs a transactional read
 * of `updatedAt`, and the write aborts when the remote value is
 * strictly newer than the local `updatedAt` the client wants to push.
 *
 * Sync sweep C unifies the older [`lwwUpdate`] helper and the
 * `safeMergeDoc` shape called for by the parity audit into a single
 * entrypoint. The two key shape changes vs the legacy helper:
 *
 *  1. Patches no longer carry their own `updatedAt`. The caller passes
 *     the *expected* remote `updatedAt` they read the doc at (the
 *     `If-Match` style precondition), and the helper stamps a fresh
 *     `serverTimestamp()` on the doc itself on commit. This eliminates
 *     a class of bugs where the caller forgot to bump `updatedAt` and
 *     the "guard" then compared identical timestamps and let an
 *     accidental no-op land. It also aligns web's clock-skew posture
 *     with Android's `FieldValue.serverTimestamp()` pattern in
 *     `SyncPushOrchestrator`.
 *  2. The stale-result returns the *full remote document* so the
 *     caller can hand it straight to the local Zustand reducer without
 *     a second round trip. The legacy helper only returned the remote
 *     `updatedAt` and forced the snapshot listener to fill in the
 *     blanks.
 *
 * Contract:
 *  - `expectedUpdatedAt === null` ⇒ create-new path. Calls
 *    `setDoc(ref, { ...patch, updatedAt: serverTimestamp() })` outside
 *    a transaction. The caller is asserting "I know this doc doesn't
 *    exist yet" — a stale local view that thinks the doc is new will
 *    still merge (Firestore's `setDoc` with no `{ merge: ... }` opt is
 *    a write-or-create, not a strict create), which is the correct
 *    convergence for first-create wins.
 *  - `expectedUpdatedAt === number` ⇒ guarded-update path. Runs a
 *    transaction:
 *      a. Read the doc.
 *      b. If the remote `updatedAt` is `> expectedUpdatedAt`, abort
 *         with `{ ok: false, reason: 'stale', remote }`.
 *      c. Otherwise, write the patch with a fresh
 *         `serverTimestamp()` stamped onto `updatedAt`.
 *  - Equality wins for the local write. `remote.updatedAt ===
 *    expectedUpdatedAt` lets the patch land. A strict `>` keeps the
 *    semantic consistent with Android's `> lastSyncedAt` shape so the
 *    same wall-clock millisecond doesn't ping-pong.
 *  - The helper does NOT toast or update Zustand stores itself. Those
 *    are caller concerns — see `handleStaleWrite` for the canonical
 *    glue.
 */
export type SafeMergeResult<T> =
  | { ok: true }
  | { ok: false; reason: 'stale'; remote: T };

/**
 * Field name used to compare local vs. remote for the LWW precondition.
 * Defaults to `'updatedAt'` (the wire format used by every Firestore
 * doc in `users/{uid}/...` *except* the generic Preference-Sync prefs
 * docs, which use `'__pref_updated_at'`).
 */
export type TimestampField = 'updatedAt' | '__pref_updated_at';

/**
 * Read the remote doc, compare its timestamp against [expectedUpdatedAt],
 * and either merge the patch (with a freshly server-stamped timestamp)
 * or abort.
 *
 * On `{ ok: true }`: the patch was committed transactionally with a
 *   fresh `serverTimestamp()` on the timestamp field. Subsequent
 *   snapshot listeners will fire with the new doc state.
 * On `{ ok: false, reason: 'stale', remote }`: no write was attempted.
 *   The caller should NOT retry — they should rehydrate their local
 *   store from `remote` and let the user re-apply the change.
 *
 * Throws only for non-LWW errors (network, permission denied,
 * transaction-retry-exhausted).
 */
export async function safeMergeDoc<T extends DocumentData = DocumentData>(
  ref: DocumentReference,
  patch: Partial<T> & Record<string, unknown>,
  expectedUpdatedAt: number | null,
  options: { timestampField?: TimestampField } = {},
): Promise<SafeMergeResult<T>> {
  const tsField = options.timestampField ?? 'updatedAt';

  if (expectedUpdatedAt === null) {
    // First-create wins. No remote `updatedAt` to compare against — the
    // snapshot listener will pull whatever the converging Android write
    // looks like.
    const payload = stampTimestamp(patch, tsField);
    await setDoc(ref, payload, { merge: true });
    return { ok: true };
  }

  return runTransaction(firestore, async (tx) =>
    evaluate<T>(tx, ref, patch, expectedUpdatedAt, tsField),
  );
}

function stampTimestamp(
  patch: Record<string, unknown>,
  tsField: TimestampField,
): Record<string, unknown | FieldValue> {
  // We use `serverTimestamp()` on the canonical `updatedAt` field so
  // Firestore stamps the wall clock at commit time — this matches
  // Android's `FieldValue.serverTimestamp()` usage in
  // `SyncPushOrchestrator` and keeps clock-skewed clients honest.
  //
  // For the generic prefs envelope (`__pref_updated_at`) Android stamps
  // a `System.currentTimeMillis()` long, not a Timestamp. We mirror
  // that with `Date.now()` so the field shapes stay byte-compatible
  // and Android's `PreferenceSyncSerialization` decodes the value
  // without a type-coercion path. `serverTimestamp()` on a numeric
  // field would land as a `Timestamp` and Android would drop it.
  if (tsField === '__pref_updated_at') {
    return { ...patch, [tsField]: Date.now() };
  }
  return { ...patch, [tsField]: serverTimestamp() };
}

async function evaluate<T extends DocumentData>(
  tx: Transaction,
  ref: DocumentReference,
  patch: Record<string, unknown>,
  expectedUpdatedAt: number,
  tsField: TimestampField,
): Promise<SafeMergeResult<T>> {
  const snap = await tx.get(ref);
  if (!snap.exists()) {
    // The caller thought a doc existed but the remote disagrees. Land
    // the patch as a create — the snapshot listener will reconcile.
    tx.set(ref, stampTimestamp(patch, tsField), { merge: true });
    return { ok: true };
  }
  const remote = snap.data() as T;
  const remoteUpdatedAt = readTimestamp(remote, tsField);
  if (remoteUpdatedAt > expectedUpdatedAt) {
    // Stale write. Log so we have visibility on cross-device collisions
    // — don't throw, the caller surfaces UX (toast + store refresh).
    console.warn(
      `[safeMergeDoc] aborted stale write to ${ref.path} ` +
        `(remote=${remoteUpdatedAt} expected=${expectedUpdatedAt})`,
    );
    return { ok: false, reason: 'stale', remote };
  }
  tx.update(ref, stampTimestamp(patch, tsField));
  return { ok: true };
}

function readTimestamp(
  remote: DocumentData,
  tsField: TimestampField,
): number {
  const raw = remote?.[tsField];
  if (typeof raw === 'number') return raw;
  // Firestore Timestamp objects expose `.toMillis()`.
  if (
    raw &&
    typeof raw === 'object' &&
    typeof (raw as { toMillis?: () => number }).toMillis === 'function'
  ) {
    try {
      return (raw as { toMillis: () => number }).toMillis();
    } catch {
      return 0;
    }
  }
  return 0;
}
