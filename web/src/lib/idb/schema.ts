/**
 * D8 Item 8 — IDB schema migration framework.
 *
 * Minimal-by-design (anti-pattern #16: don't extend Item 3 framework
 * scope beyond what consumers actually need). The first consumer is
 * `batchHistoryStore` (web batch undo history, formerly localStorage).
 *
 * Versioning model: monotonic global version number. `idb`'s `openDB`
 * provides a single `upgrade` callback receiving the current and target
 * version; this module routes the upgrade through a per-version
 * migration registry so each schema bump is a single function with
 * isolated effects.
 *
 * Cross-tab leader-election (Web Locks API) and rollback semantics are
 * intentionally NOT implemented yet — see `docs/audits/D8_FINISH_BUNDLE_AUDIT.md`
 * § Item 3 for the deferred-feature list. Single-tab assumption holds for
 * the batch-history consumer (one tab writes, others read fresh on next
 * hydrate).
 */

import type { IDBPDatabase, IDBPTransaction } from 'idb';

export const DB_NAME = 'prismtask';

/**
 * Bump this when adding a migration. Each integer N requires a registered
 * `migrations[N]` function that runs when the user's existing DB has
 * `version < N`.
 */
export const DB_VERSION = 1;

/**
 * One migration step. `oldVersion` is the version of the user's existing
 * DB before this migration runs (0 means fresh install). The transaction
 * is the upgrade tx; use it to create stores / indexes.
 */
export type MigrationFn = (
  db: IDBPDatabase,
  tx: IDBPTransaction<unknown, string[], 'versionchange'>,
  oldVersion: number,
) => void;

/**
 * Migration registry. Index N runs when the DB upgrades to version N.
 * Migrations are dispatched in order from `oldVersion + 1` through the
 * target version.
 */
export const migrations: Record<number, MigrationFn> = {
  /**
   * v1 — `batch_history` store. Replaces the localStorage
   * `prismtask_batch_history_{uid}` key. Records are keyed by
   * `(uid, batch_id)` compound key so a single store holds all users'
   * histories without per-user store proliferation.
   */
  1: (db) => {
    if (!db.objectStoreNames.contains('batch_history')) {
      const store = db.createObjectStore('batch_history', {
        keyPath: ['uid', 'batch_id'],
      });
      store.createIndex('by_uid', 'uid', { unique: false });
      store.createIndex('by_uid_created', ['uid', 'created_at'], {
        unique: false,
      });
    }
  },
};
