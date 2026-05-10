/**
 * Typed `batch_history` IDB store wrapper. Replaces the localStorage
 * `prismtask_batch_history_{uid}` key — IDB lets the history grow past
 * localStorage's ~5 MB quota and avoids the per-write JSON serialization
 * cost of the legacy implementation.
 *
 * Key shape: `(uid, batch_id)` compound key. Records carry their own
 * `uid` so a single store serves all users; the `by_uid` index supports
 * per-user listing.
 *
 * One-time migration: `migrateFromLocalStorageIfNeeded(uid)` reads the
 * legacy localStorage key on first hydrate, writes the records into IDB,
 * then deletes the localStorage key. Idempotent — subsequent calls
 * see no localStorage payload and no-op.
 */

import type { BatchHistoryRecord } from '@/types/batch';
import { openIdb } from './database';

interface StoredBatchHistoryRecord extends BatchHistoryRecord {
  uid: string;
}

const STORE = 'batch_history';

function legacyStorageKey(uid: string): string {
  return `prismtask_batch_history_${uid}`;
}

export async function getBatchHistoryForUser(
  uid: string,
): Promise<BatchHistoryRecord[]> {
  try {
    const db = await openIdb();
    const records = (await db.getAllFromIndex(
      STORE,
      'by_uid',
      uid,
    )) as StoredBatchHistoryRecord[];
    // Newest first to match the legacy localStorage shape (the array was
    // unshifted on commit).
    records.sort((a, b) => b.created_at - a.created_at);
    return records.map(({ uid: _uid, ...rest }) => rest);
  } catch {
    return [];
  }
}

export async function putBatchHistoryRecord(
  uid: string,
  record: BatchHistoryRecord,
): Promise<void> {
  try {
    const db = await openIdb();
    const stored: StoredBatchHistoryRecord = { uid, ...record };
    await db.put(STORE, stored);
  } catch {
    // Quota / private mode / IDB unavailable — non-fatal. The in-memory
    // store still reflects the change for the session.
  }
}

export async function replaceBatchHistoryForUser(
  uid: string,
  records: BatchHistoryRecord[],
): Promise<void> {
  try {
    const db = await openIdb();
    const tx = db.transaction(STORE, 'readwrite');
    const store = tx.objectStore(STORE);
    const existing = (await store.index('by_uid').getAllKeys(uid)) as Array<
      [string, string]
    >;
    for (const key of existing) {
      await store.delete(key);
    }
    for (const record of records) {
      await store.put({ uid, ...record } satisfies StoredBatchHistoryRecord);
    }
    await tx.done;
  } catch {
    // Best-effort persistence; in-memory store is the source of truth
    // for the active session.
  }
}

/**
 * One-time migration of the legacy localStorage payload into IDB. Idempotent:
 * after the first successful migration the localStorage key is removed and
 * subsequent calls no-op.
 *
 * Returns the migrated records (or empty array if no legacy payload existed)
 * so the caller can hydrate state without a follow-up read.
 */
export async function migrateFromLocalStorageIfNeeded(
  uid: string,
): Promise<BatchHistoryRecord[] | null> {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(legacyStorageKey(uid));
  } catch {
    return null;
  }
  if (!raw) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    // Malformed payload — drop it. The user gets an empty history; the
    // alternative (leaving the bad payload around) blocks the migration
    // forever.
    try {
      localStorage.removeItem(legacyStorageKey(uid));
    } catch {
      // ignore
    }
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const records = parsed as BatchHistoryRecord[];

  await replaceBatchHistoryForUser(uid, records);
  try {
    localStorage.removeItem(legacyStorageKey(uid));
  } catch {
    // ignore — leaving the legacy key around is a harmless leak; the
    // next migration call will retry the cleanup.
  }
  return records;
}
