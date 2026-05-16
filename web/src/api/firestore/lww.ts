import {
  runTransaction,
  type DocumentReference,
  type Transaction,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Last-write-wins (LWW) timestamp guard for Firestore merge / update
 * writes that race with cross-device pushes.
 *
 * Without a guard, `updateDoc({ ..., updatedAt: Date.now() })` happily
 * overwrites a newer Android-side write when the web client's wall
 * clock is behind, because Firestore's default merge semantics don't
 * compare timestamps. Mirrors Android's `SyncMapper` LWW contract: the
 * push side runs a transactional read of `updatedAt`, and the write
 * aborts when the remote value is strictly newer than the local
 * `updatedAt` the client wants to push.
 *
 * Contract:
 *  - First-create wins. If the remote doc doesn't exist yet,
 *    [lwwUpdate] writes the patch unconditionally. This matches the
 *    behaviour of Android's pull side, which never reads-then-writes
 *    on a missing row — it just inserts.
 *  - Equality wins for the local write. `remote.updatedAt ===
 *    patch.updatedAt` lets the patch land. A strict `>` keeps the
 *    semantic consistent with `SyncService`'s `> lastSyncedAt` shape
 *    so the same wall-clock millisecond doesn't ping-pong.
 *  - The patch MUST carry its own `updatedAt`. Callers compute
 *    `Date.now()` once and pass it in so the timestamp the guard
 *    compares is the same timestamp the doc ends up with after the
 *    transaction commits. (Generating a fresh `Date.now()` inside
 *    the transaction would open a race window where the guard sees
 *    `T0` but writes `T0 + ε`.)
 *  - On abort, the helper returns `{ applied: false, reason:
 *    'stale' }` rather than throwing — UI shouldn't surface a banner
 *    for an out-of-order push that the snapshot listener will
 *    immediately reconcile.
 */
export interface LwwPatch {
  /** Caller-supplied wall-clock ms; the guard compares this against
   *  `remote.updatedAt`. Always include it in the patch — the helper
   *  doesn't synthesise one. */
  updatedAt: number;
  /** All other fields to merge into the doc. */
  [key: string]: unknown;
}

export type LwwResult =
  | { applied: true }
  | { applied: false; reason: 'stale'; remoteUpdatedAt: number };

/**
 * Read the remote `updatedAt`, compare against [patch.updatedAt], and
 * either `update` the doc with the patch or abort. The whole thing
 * runs inside a Firestore `runTransaction` so the read and the
 * write commit atomically — no other writer can sneak a newer doc
 * between our `tx.get` and `tx.update`.
 *
 * Returns `{ applied: true }` on success, `{ applied: false, reason:
 * 'stale', remoteUpdatedAt }` when the guard aborts. Throws only for
 * non-LWW errors (network, permission, transaction-retry-exhausted).
 */
export async function lwwUpdate(
  ref: DocumentReference,
  patch: LwwPatch,
): Promise<LwwResult> {
  return runTransaction(firestore, async (tx) => evaluate(tx, ref, patch));
}

/**
 * Batched variant: runs N LWW guards in one transaction. Useful when
 * the caller is writing two refs that should commit atomically (e.g.
 * a task + its `task_completions` row). All-or-nothing — if any
 * single doc is stale, the whole transaction aborts and none of the
 * patches land.
 *
 * Currently unused; exposed for future callers (B.6 task-completion
 * write path on toggleComplete could batch the task + the completion
 * row through this helper). Keeping it next to the single-doc shape
 * so the contract stays consistent.
 */
export async function lwwUpdateMany(
  writes: ReadonlyArray<{ ref: DocumentReference; patch: LwwPatch }>,
): Promise<LwwResult> {
  return runTransaction(firestore, async (tx) => {
    // First pass: read everything (Firestore requires all reads before
    // writes inside a transaction).
    const snaps = await Promise.all(writes.map((w) => tx.get(w.ref)));
    for (let i = 0; i < writes.length; i++) {
      const { patch } = writes[i];
      const snap = snaps[i];
      if (!snap.exists()) continue;
      const remote = snap.data();
      const remoteUpdatedAt =
        typeof remote.updatedAt === 'number' ? remote.updatedAt : 0;
      if (remoteUpdatedAt > patch.updatedAt) {
        return {
          applied: false as const,
          reason: 'stale' as const,
          remoteUpdatedAt,
        };
      }
    }
    // Second pass: writes.
    for (const w of writes) {
      tx.set(w.ref, w.patch, { merge: true });
    }
    return { applied: true as const };
  });
}

async function evaluate(
  tx: Transaction,
  ref: DocumentReference,
  patch: LwwPatch,
): Promise<LwwResult> {
  const snap = await tx.get(ref);
  if (!snap.exists()) {
    // First-create wins. No remote `updatedAt` to compare against —
    // the snapshot listener will pull whatever Android writes next.
    tx.set(ref, patch, { merge: true });
    return { applied: true };
  }
  const remote = snap.data();
  const remoteUpdatedAt =
    typeof remote?.updatedAt === 'number' ? remote.updatedAt : 0;
  if (remoteUpdatedAt > patch.updatedAt) {
    // Out-of-order push. Log so we have visibility on cross-device
    // collisions; don't throw — the snapshot listener will surface
    // the newer remote state momentarily.
    console.warn(
      `[lww] aborted stale write to ${ref.path} ` +
        `(remote=${remoteUpdatedAt} local=${patch.updatedAt})`,
    );
    return { applied: false, reason: 'stale', remoteUpdatedAt };
  }
  tx.update(ref, patch);
  return { applied: true };
}
