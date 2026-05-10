/**
 * IDB connection singleton. Opens the `prismtask` database with the
 * current schema version, dispatching pending migrations through
 * `migrations[]` registry on `versionchange`.
 *
 * `openIdb()` is idempotent — repeated calls return the same connection
 * promise. Call sites should treat it as fire-and-forget on app
 * bootstrap; consumer modules (e.g. `batchHistoryStore`) await the
 * returned `IDBPDatabase` whenever they read or write.
 */

import { openDB, type IDBPDatabase } from 'idb';
import { DB_NAME, DB_VERSION, migrations } from './schema';

let cached: Promise<IDBPDatabase> | null = null;

export function openIdb(): Promise<IDBPDatabase> {
  if (cached) return cached;
  cached = openDB(DB_NAME, DB_VERSION, {
    upgrade(db, oldVersion, newVersion, tx) {
      const target = newVersion ?? DB_VERSION;
      for (let v = oldVersion + 1; v <= target; v += 1) {
        const migration = migrations[v];
        if (!migration) {
          // Unknown version step — fail loudly. Better to crash early
          // than to leave the DB in a half-migrated state.
          throw new Error(
            `IDB migration ${v} is not registered. Did you bump DB_VERSION without adding a migration?`,
          );
        }
        migration(db, tx, oldVersion);
      }
    },
    blocked() {
      // Another tab holds an old version. The user-visible symptom is
      // that the new tab waits for the other to close. Logging here
      // helps debugging without requiring leader-election (deferred).
      // eslint-disable-next-line no-console
      console.warn(
        '[idb] Upgrade blocked — another tab has the database open at an older version.',
      );
    },
    blocking() {
      // We're holding an older version while another tab wants to
      // upgrade. Close our connection so the upgrade can proceed.
      cached?.then((db) => db.close());
      cached = null;
    },
  });
  return cached;
}

/** Test-only reset; resets the cached connection so tests can re-open. */
export function resetIdbForTesting(): void {
  cached?.then((db) => db.close()).catch(() => {});
  cached = null;
}
