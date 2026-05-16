/**
 * Web parity port of Android's
 * `data/preferences/BuiltInSyncPreferences.kt`.
 *
 * One-shot repair flags for built-in habit reconciliation, persisted in
 * IndexedDB (`kv_flags` store, v2 schema). Each flag is scoped per-user
 * so multiple accounts on the same browser stay isolated — matches the
 * IDB-cleared-on-logout contract documented in `useFirestoreSync.ts`.
 *
 * Flag semantics mirror Android (`BuiltInSyncPreferences.kt:69-95`):
 * once a successful reconciliation pass has run for a user we skip the
 * heavy initial sweep on subsequent app loads. The flag is intentionally
 * NOT synced cross-device — each device runs its own one-shot dedup,
 * because the duplicates we're cleaning up are per-device-local
 * artifacts of pulling the cloud copy of a habit that was already
 * seeded locally (Android's same rationale, see
 * `BuiltInHabitReconciler.kt:14-30`).
 *
 * Web's `unitOfWork` is `(uid, flagKey)`. The flag never persists for
 * `uid === ''` (signed-out / anonymous) so a logged-out user never
 * accidentally records a "done" state that bleeds into the next signed
 * -in account.
 */

import { openIdb } from '@/lib/idb/database';

const STORE = 'kv_flags';

/**
 * Per-user one-shot repair flag for the built-in habit reconciler.
 * Mirrors Android's `BUILT_INS_RECONCILED` DataStore key — once true,
 * the reconciler skips its post-sync sweep until the next app reset.
 */
export const RECONCILER_FLAG_V1 = 'builtInReconcilerRanV1';

interface StoredFlag {
  uid: string;
  key: string;
  value: boolean;
  updatedAt: number;
}

/**
 * Returns `true` when the flag has been set for the given user, `false`
 * otherwise. IDB read failures silently degrade to `false` so the
 * reconciler errs on the side of re-running (no-op if there are no
 * duplicates, expensive only on the first observed duplicate).
 */
export async function isFlagSet(uid: string, key: string): Promise<boolean> {
  if (!uid) return false;
  try {
    const db = await openIdb();
    const record = (await db.get(STORE, [uid, key])) as StoredFlag | undefined;
    return record?.value === true;
  } catch {
    return false;
  }
}

/**
 * Mark the flag as set for the user. No-op when `uid` is empty so a
 * signed-out caller doesn't record state that bleeds into the next
 * signed-in account. IDB write failures are swallowed — the in-memory
 * reconciler still completed; the worst case is one redundant sweep on
 * the next load.
 */
export async function setFlag(uid: string, key: string): Promise<void> {
  if (!uid) return;
  try {
    const db = await openIdb();
    const stored: StoredFlag = {
      uid,
      key,
      value: true,
      updatedAt: Date.now(),
    };
    await db.put(STORE, stored);
  } catch {
    // Best-effort — see fn comment.
  }
}

/**
 * Test-only helper: clears every flag for the given user. Production
 * callers use `IndexedDB.deleteDatabase` on logout (see the IDB-cleared
 * -on-logout note in `useFirestoreSync.ts`).
 */
export async function clearFlagsForUser(uid: string): Promise<void> {
  if (!uid) return;
  try {
    const db = await openIdb();
    const tx = db.transaction(STORE, 'readwrite');
    const store = tx.objectStore(STORE);
    const keys = (await store.index('by_uid').getAllKeys(uid)) as Array<
      [string, string]
    >;
    for (const k of keys) {
      await store.delete(k);
    }
    await tx.done;
  } catch {
    // best-effort
  }
}
