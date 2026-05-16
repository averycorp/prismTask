import { describe, it, expect, afterEach } from 'vitest';
import { resetIdbForTesting } from '@/lib/idb/database';
import {
  RECONCILER_FLAG_V1,
  clearFlagsForUser,
  isFlagSet,
  setFlag,
} from '@/lib/builtInSyncPreferences';

describe('builtInSyncPreferences — one-shot repair flags', () => {
  afterEach(async () => {
    await clearFlagsForUser('user-a');
    await clearFlagsForUser('user-b');
    resetIdbForTesting();
  });

  it('returns false for an unset flag', async () => {
    expect(await isFlagSet('user-a', RECONCILER_FLAG_V1)).toBe(false);
  });

  it('persists a set flag across reads (same uid)', async () => {
    await setFlag('user-a', RECONCILER_FLAG_V1);
    expect(await isFlagSet('user-a', RECONCILER_FLAG_V1)).toBe(true);
  });

  it('scopes flags per uid — user-a writes do not leak into user-b', async () => {
    await setFlag('user-a', RECONCILER_FLAG_V1);
    expect(await isFlagSet('user-a', RECONCILER_FLAG_V1)).toBe(true);
    expect(await isFlagSet('user-b', RECONCILER_FLAG_V1)).toBe(false);
  });

  it('clearFlagsForUser wipes that user without touching others', async () => {
    await setFlag('user-a', RECONCILER_FLAG_V1);
    await setFlag('user-b', RECONCILER_FLAG_V1);
    await clearFlagsForUser('user-a');
    expect(await isFlagSet('user-a', RECONCILER_FLAG_V1)).toBe(false);
    expect(await isFlagSet('user-b', RECONCILER_FLAG_V1)).toBe(true);
  });

  it('no-ops on empty uid (set + read)', async () => {
    await setFlag('', RECONCILER_FLAG_V1);
    expect(await isFlagSet('', RECONCILER_FLAG_V1)).toBe(false);
  });
});
