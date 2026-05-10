import { describe, it, expect, afterEach } from 'vitest';
import { openIdb, resetIdbForTesting } from '@/lib/idb/database';
import {
  getBatchHistoryForUser,
  putBatchHistoryRecord,
  replaceBatchHistoryForUser,
} from '@/lib/idb/batchHistoryStore';
import type { BatchHistoryRecord } from '@/types/batch';

function makeRecord(
  batchId: string,
  createdAt: number,
  expiresAt: number = createdAt + 60_000,
): BatchHistoryRecord {
  return {
    batch_id: batchId,
    command_text: `cmd-${batchId}`,
    created_at: createdAt,
    expires_at: expiresAt,
    undone_at: null,
    entries: [],
    applied_count: 0,
    skipped_count: 0,
  };
}

describe('IDB framework — schema + batchHistoryStore', () => {
  afterEach(async () => {
    // Wipe both test users so the shared fake-indexeddb doesn't leak
    // state across tests in the same file.
    await replaceBatchHistoryForUser('user-a', []);
    await replaceBatchHistoryForUser('user-b', []);
    resetIdbForTesting();
  });

  it('opens the database with the registered version', async () => {
    const db = await openIdb();
    expect(db.name).toBe('prismtask');
    expect(db.objectStoreNames.contains('batch_history')).toBe(true);
  });

  it('round-trips a single record via putBatchHistoryRecord', async () => {
    const record = makeRecord('alpha', 1000);
    await putBatchHistoryRecord('user-a', record);
    const loaded = await getBatchHistoryForUser('user-a');
    expect(loaded.map((r) => r.batch_id)).toEqual(['alpha']);
  });

  it('returns records newest-first on read', async () => {
    await putBatchHistoryRecord('user-a', makeRecord('older', 1000));
    await putBatchHistoryRecord('user-a', makeRecord('newer', 2000));
    const loaded = await getBatchHistoryForUser('user-a');
    expect(loaded.map((r) => r.batch_id)).toEqual(['newer', 'older']);
  });

  it('scopes records by uid — user-a writes do not bleed into user-b', async () => {
    await putBatchHistoryRecord('user-a', makeRecord('a-only', 1000));
    await putBatchHistoryRecord('user-b', makeRecord('b-only', 1000));
    const a = await getBatchHistoryForUser('user-a');
    const b = await getBatchHistoryForUser('user-b');
    expect(a.map((r) => r.batch_id)).toEqual(['a-only']);
    expect(b.map((r) => r.batch_id)).toEqual(['b-only']);
  });

  it('replaceBatchHistoryForUser wipes prior records for that uid', async () => {
    await putBatchHistoryRecord('user-a', makeRecord('first', 1000));
    await putBatchHistoryRecord('user-a', makeRecord('second', 2000));
    await replaceBatchHistoryForUser('user-a', [makeRecord('only', 3000)]);
    const loaded = await getBatchHistoryForUser('user-a');
    expect(loaded.map((r) => r.batch_id)).toEqual(['only']);
  });
});
