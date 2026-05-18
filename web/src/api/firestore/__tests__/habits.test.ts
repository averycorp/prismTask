import { describe, it, expect, vi, beforeEach } from 'vitest';

const {
  addDocMock,
  setDocMock,
  updateDocMock,
  getDocMock,
  getDocsMock,
  txGetMock,
  runTransactionMock,
  docMock,
  collectionMock,
  queryMock,
  whereMock,
  deleteDocMock,
} = vi.hoisted(() => {
  const addDocMock = vi.fn();
  const setDocMock = vi.fn(
    async (_ref?: unknown, _data?: unknown, _opts?: unknown) => undefined,
  );
  const updateDocMock = vi.fn();
  const getDocMock = vi.fn();
  const getDocsMock = vi.fn();
  const txGetMock = vi.fn();
  const runTransactionMock = vi.fn();
  // Mocked `doc(...)` returns an object identifying the doc by the
  // segments passed to it — last segment is the doc id we care about.
  const docMock = vi.fn((..._segments: unknown[]) => {
    const segs = _segments.filter((s): s is string => typeof s === 'string');
    return { id: segs[segs.length - 1], path: segs.join('/') };
  });
  const collectionMock = vi.fn(
    (_db: unknown, ...segments: string[]) => ({ path: segments.join('/') }),
  );
  const queryMock = vi.fn();
  const whereMock = vi.fn((field: string, op: string, value: unknown) => ({
    field,
    op,
    value,
  }));
  const deleteDocMock = vi.fn(async () => undefined);
  return {
    addDocMock,
    setDocMock,
    updateDocMock,
    getDocMock,
    getDocsMock,
    txGetMock,
    runTransactionMock,
    docMock,
    collectionMock,
    queryMock,
    whereMock,
    deleteDocMock,
  };
});

vi.mock('firebase/firestore', () => ({
  addDoc: addDocMock,
  setDoc: setDocMock,
  updateDoc: updateDocMock,
  getDoc: getDocMock,
  getDocs: getDocsMock,
  runTransaction: runTransactionMock,
  doc: docMock,
  collection: collectionMock,
  query: queryMock,
  where: whereMock,
  orderBy: vi.fn(),
  onSnapshot: vi.fn(),
  deleteDoc: deleteDocMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: {} }));

import {
  createHabit,
  updateHabit,
  toggleCompletion,
  getCompletions,
  getAllCompletions,
} from '@/api/firestore/habits';
import { logicalToday } from '@/utils/dayBoundary';

beforeEach(() => {
  addDocMock.mockReset();
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  updateDocMock.mockReset();
  getDocMock.mockReset();
  getDocsMock.mockReset();
  txGetMock.mockReset();
  runTransactionMock.mockReset();
  docMock.mockClear();
  collectionMock.mockClear();
  queryMock.mockReset();
  queryMock.mockReturnValue({});
  whereMock.mockClear();
  deleteDocMock.mockReset();
  deleteDocMock.mockResolvedValue(undefined);
  // Default `runTransaction`: invoke the callback with a tx whose `get`
  // delegates to txGetMock (defaulting to a doc-exists snapshot with an
  // older `updatedAt` so the LWW guard always lets the write through)
  // and whose `update`/`set` forward to the existing mocks. Tests that
  // care about a stale-write abort override `txGetMock` to return a
  // newer remote timestamp.
  txGetMock.mockResolvedValue({
    exists: () => true,
    data: () => ({ updatedAt: 0 }),
  });
  runTransactionMock.mockImplementation(
    async (_db: unknown, fn: (tx: unknown) => Promise<unknown>) => {
      const tx = {
        get: (ref: unknown) => txGetMock(ref),
        update: (ref: unknown, patch: unknown) => updateDocMock(ref, patch),
        set: (ref: unknown, patch: unknown, opts?: unknown) =>
          setDocMock(ref, patch, opts),
      };
      return fn(tx);
    },
  );
});

// Helper: stub out the three reads `toggleCompletion` makes when the
// canonical doc doesn't exist and there are no legacy duplicates either.
function stubNoExistingCompletion() {
  // 1) getDoc(canonicalRef) → does not exist
  getDocMock.mockResolvedValueOnce({ exists: () => false });
  // 2) getDocs(by completedDateLocal) → empty
  getDocsMock.mockResolvedValueOnce({ docs: [] });
  // 3) getDocs(by completedDate epoch) → empty
  getDocsMock.mockResolvedValueOnce({ docs: [] });
}

// ── Android-only fields the web app must NEVER hardcode on write
//    (otherwise it clobbers state owned by Android UI). ────────────
// NOTE: `todaySkipAfterCompleteDays` / `todaySkipBeforeScheduleDays`
// moved out of this list when parity audit § B.5 landed — web's
// `HabitModal.tsx` now exposes them, so they ARE allowed in the write
// payload when the caller explicitly supplied a value. The
// conditional-include semantics still preserve the no-clobber invariant
// for callers that don't touch the override switches; see the dedicated
// "today-skip overrides" tests below.
const ANDROID_ONLY_HABIT_FIELDS = [
  'isBookable',
  'isBooked',
  'bookedDate',
  'bookedNote',
  'trackBooking',
  'trackPreviousPeriod',
  'hasLogging',
  'showStreak',
  'reminderTimesPerDay',
  'reminderIntervalMillis',
  'nagSuppressionOverrideEnabled',
  'nagSuppressionDaysOverride',
  'isBuiltIn',
  'templateKey',
  'sourceVersion',
  'isUserModified',
  'isDetachedFromTemplate',
] as const;

describe('createHabit (web → Firestore merge-on-write parity)', () => {
  it('does not write hardcoded Android-only field defaults when web does not supply them', async () => {
    addDocMock.mockResolvedValueOnce({ id: 'new-habit-id' });

    await createHabit('uid-1', {
      name: 'Hydrate',
      icon: '💧',
      color: '#06b6d4',
      frequency: 'daily',
      target_count: 1,
    });

    expect(addDocMock).toHaveBeenCalledTimes(1);
    const written = addDocMock.mock.calls[0][1] as Record<string, unknown>;

    expect(written.name).toBe('Hydrate');
    expect(written.icon).toBe('💧');
    expect(written.color).toBe('#06b6d4');
    expect(written.frequencyPeriod).toBe('daily');
    expect(written.targetFrequency).toBe(1);

    for (const field of ANDROID_ONLY_HABIT_FIELDS) {
      expect(
        Object.prototype.hasOwnProperty.call(written, field),
        `expected createHabit payload to omit Android-only field "${field}", got ${JSON.stringify(written[field])}`,
      ).toBe(false);
    }
  });
});

describe('updateHabit (web → Firestore merge-on-write parity)', () => {
  beforeEach(() => {
    getDocMock.mockResolvedValue({
      id: 'habit-1',
      data: () => ({
        name: 'Renamed',
        frequencyPeriod: 'daily',
        targetFrequency: 1,
        isArchived: false,
        createdAt: 1_700_000_000_000,
        updatedAt: 1_700_000_000_000,
      }),
    });
  });

  it('only writes the fields the web user actually edited', async () => {
    updateDocMock.mockResolvedValueOnce(undefined);

    await updateHabit('uid-1', 'habit-1', { name: 'Renamed' });

    expect(updateDocMock).toHaveBeenCalledTimes(1);
    const patch = updateDocMock.mock.calls[0][1] as Record<string, unknown>;

    expect(patch.name).toBe('Renamed');
    expect(typeof patch.updatedAt).toBe('number');

    for (const field of ANDROID_ONLY_HABIT_FIELDS) {
      expect(
        Object.prototype.hasOwnProperty.call(patch, field),
        `updateHabit patch must omit Android-only field "${field}"`,
      ).toBe(false);
    }

    expect(Object.prototype.hasOwnProperty.call(patch, 'description')).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(patch, 'icon')).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(patch, 'color')).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(patch, 'category')).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(patch, 'frequencyPeriod')).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(patch, 'targetFrequency')).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(patch, 'activeDays')).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(patch, 'isArchived')).toBe(false);
  });

  it('writes is_active by toggling the Android isArchived flag (web⇄Android polarity)', async () => {
    updateDocMock.mockResolvedValueOnce(undefined);

    await updateHabit('uid-1', 'habit-1', { is_active: false });

    const patch = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(patch.isArchived).toBe(true);
  });

  it('aborts the write when remote updatedAt is strictly newer (LWW guard)', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      data: () => ({ updatedAt: Date.now() + 1_000_000 }),
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    await updateHabit('uid-1', 'habit-1', { name: 'Stale write' });

    expect(updateDocMock).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('[lww] aborted stale write'),
    );

    warnSpy.mockRestore();
  });

  it('first-create wins when the remote habit doc does not exist (LWW guard)', async () => {
    txGetMock.mockResolvedValue({
      exists: () => false,
      data: () => undefined,
    });
    await updateHabit('uid-1', 'habit-1', { name: 'New habit' });
    // Missing-doc path goes through `tx.set(..., {merge:true})`.
    expect(setDocMock).toHaveBeenCalledTimes(1);
    expect(updateDocMock).not.toHaveBeenCalled();
  });

  // Parity audit § B.5 — web now owns the per-habit Today-skip
  // overrides (`HabitModal.tsx`). The patch shape must write the
  // Android column names (`todaySkipAfterCompleteDays` /
  // `todaySkipBeforeScheduleDays`) only when the caller supplied
  // them, preserving the no-clobber contract for callers that don't
  // touch the override switches.
  it('writes Today-skip overrides when the caller supplies them', async () => {
    updateDocMock.mockResolvedValueOnce(undefined);

    await updateHabit('uid-1', 'habit-1', {
      today_skip_after_complete_days: 3,
      today_skip_before_schedule_days: -1,
    });

    expect(updateDocMock).toHaveBeenCalledTimes(1);
    const patch = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(patch.todaySkipAfterCompleteDays).toBe(3);
    expect(patch.todaySkipBeforeScheduleDays).toBe(-1);
  });

  it('omits Today-skip overrides when the caller does not touch them', async () => {
    updateDocMock.mockResolvedValueOnce(undefined);

    await updateHabit('uid-1', 'habit-1', { name: 'Just rename' });

    const patch = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(
      Object.prototype.hasOwnProperty.call(patch, 'todaySkipAfterCompleteDays'),
    ).toBe(false);
    expect(
      Object.prototype.hasOwnProperty.call(patch, 'todaySkipBeforeScheduleDays'),
    ).toBe(false);
  });

  // Per-habit streak-forgiveness overrides (spec
  // `docs/superpowers/specs/2026-05-18-per-habit-forgiveness-design.md`).
  // Same conditional-include / no-clobber contract as the Today-skip
  // overrides above.
  it('writes streak-forgiveness overrides when the caller supplies them', async () => {
    updateDocMock.mockResolvedValueOnce(undefined);

    await updateHabit('uid-1', 'habit-1', {
      streak_max_missed_days: 3,
      forgiveness_enabled: 1,
      forgiveness_allowed_misses: 2,
      forgiveness_grace_period_days: 14,
    });

    expect(updateDocMock).toHaveBeenCalledTimes(1);
    const patch = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(patch.streakMaxMissedDays).toBe(3);
    expect(patch.forgivenessEnabled).toBe(1);
    expect(patch.forgivenessAllowedMisses).toBe(2);
    expect(patch.forgivenessGracePeriodDays).toBe(14);
  });

  it('writes null streak-forgiveness fields as the explicit clear signal', async () => {
    updateDocMock.mockResolvedValueOnce(undefined);

    await updateHabit('uid-1', 'habit-1', {
      streak_max_missed_days: null,
      forgiveness_enabled: null,
      forgiveness_allowed_misses: null,
      forgiveness_grace_period_days: null,
    });

    const patch = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    // `null` is the explicit "inherit global" signal — it must reach
    // Firestore so older non-null values get cleared.
    expect(patch.streakMaxMissedDays).toBeNull();
    expect(patch.forgivenessEnabled).toBeNull();
    expect(patch.forgivenessAllowedMisses).toBeNull();
    expect(patch.forgivenessGracePeriodDays).toBeNull();
  });

  it('omits streak-forgiveness overrides when the caller does not touch them', async () => {
    updateDocMock.mockResolvedValueOnce(undefined);

    await updateHabit('uid-1', 'habit-1', { name: 'Just rename' });

    const patch = updateDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(
      Object.prototype.hasOwnProperty.call(patch, 'streakMaxMissedDays'),
    ).toBe(false);
    expect(
      Object.prototype.hasOwnProperty.call(patch, 'forgivenessEnabled'),
    ).toBe(false);
    expect(
      Object.prototype.hasOwnProperty.call(patch, 'forgivenessAllowedMisses'),
    ).toBe(false);
    expect(
      Object.prototype.hasOwnProperty.call(patch, 'forgivenessGracePeriodDays'),
    ).toBe(false);
  });
});

describe('toggleCompletion (writes completedDateLocal for cross-device DST parity)', () => {
  it('writes completedDateLocal matching the SoD-relative logical-day key for the supplied date', async () => {
    stubNoExistingCompletion();

    const date = '2026-04-26';
    await toggleCompletion('uid-1', 'habit-1', date);

    expect(setDocMock).toHaveBeenCalledTimes(1);
    const written = (setDocMock.mock.calls[0] as unknown[])[1] as Record<string, unknown>;

    expect(written.habitCloudId).toBe('habit-1');
    expect(written.completedDateLocal).toBe(date);
    expect(written.completedDateLocal).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    // The legacy completedDate timestamp is still written for back-compat.
    expect(typeof written.completedDate).toBe('number');
  });

  it('matches useLogicalToday-shaped output for the user-configured SoD hour', async () => {
    const wallClock = new Date(2026, 3, 26, 2, 30, 0);
    const sodHour = 4;
    const expected = logicalToday(wallClock, sodHour);
    expect(expected).toBe('2026-04-25');

    stubNoExistingCompletion();

    await toggleCompletion('uid-1', 'habit-1', expected);

    const written = (setDocMock.mock.calls[0] as unknown[])[1] as Record<string, unknown>;
    expect(written.completedDateLocal).toBe('2026-04-25');
  });

  it('DST spring-forward: a completion at 23:55 local on the spring-forward day rounds to that calendar day (SoD = 0)', async () => {
    const wallClock = new Date(2026, 2, 8, 23, 55, 0);
    const sodHour = 0;
    const expected = logicalToday(wallClock, sodHour);
    expect(expected).toBe('2026-03-08');

    stubNoExistingCompletion();

    await toggleCompletion('uid-1', 'habit-1', expected);

    const written = (setDocMock.mock.calls[0] as unknown[])[1] as Record<string, unknown>;
    expect(written.completedDateLocal).toBe('2026-03-08');
  });
});

describe('toggleCompletion canonical-row dedup', () => {
  it('writes to a deterministic doc id of `${habitId}__${date}`', async () => {
    stubNoExistingCompletion();

    await toggleCompletion('uid-1', 'habit-7', '2026-04-26');

    expect(setDocMock).toHaveBeenCalledTimes(1);
    const ref = (setDocMock.mock.calls[0] as unknown[])[0] as { id: string; path: string };
    expect(ref.id).toBe('habit-7__2026-04-26');
    expect(ref.path).toContain('habit_completions/habit-7__2026-04-26');
  });

  it('uses { merge: true } so two parallel writers converge to one doc', async () => {
    stubNoExistingCompletion();

    await toggleCompletion('uid-1', 'habit-7', '2026-04-26');

    const opts = (setDocMock.mock.calls[0] as unknown[])[2] as { merge: boolean } | undefined;
    expect(opts).toEqual({ merge: true });
  });

  it('two consecutive toggleCompletion calls for the same (habit, date) target the same doc id', async () => {
    // Call 1: canonical doesn't exist yet → setDoc, action=added.
    stubNoExistingCompletion();
    const r1 = await toggleCompletion('uid-1', 'habit-9', '2026-04-26');
    expect(r1.action).toBe('added');

    // Call 2: canonical now exists → toggle off.
    getDocMock.mockResolvedValueOnce({ exists: () => true });
    // Plus the legacy-sweep getDocs are still issued (returning empty).
    getDocsMock.mockResolvedValueOnce({ docs: [] });
    getDocsMock.mockResolvedValueOnce({ docs: [] });

    const r2 = await toggleCompletion('uid-1', 'habit-9', '2026-04-26');
    expect(r2.action).toBe('removed');

    // First setDoc and the subsequent deleteDoc both targeted the SAME doc id.
    const writeRef = (setDocMock.mock.calls[0] as unknown[])[0] as { id: string };
    const deleteRef = (deleteDocMock.mock.calls[0] as unknown[])[0] as { id: string };
    expect(writeRef.id).toBe('habit-9__2026-04-26');
    expect(deleteRef.id).toBe('habit-9__2026-04-26');
  });

  it('cross-timezone parity: same logical-day string from different TZ contexts maps to the same doc id', async () => {
    // Two devices in different timezones agree on the YYYY-MM-DD logical-day
    // key. The pre-fix code keyed dedup on `completedDate` epoch ms, which
    // was timezone-dependent — same logical day produced different epochs.
    // The post-fix doc id keys on completedDateLocal (the YYYY-MM-DD), so
    // the doc path is identical regardless of where each writer is.
    stubNoExistingCompletion();
    await toggleCompletion('uid-1', 'habit-x', '2026-04-26');
    const ref1 = (setDocMock.mock.calls[0] as unknown[])[0] as { id: string };

    setDocMock.mockClear();
    stubNoExistingCompletion();
    await toggleCompletion('uid-1', 'habit-x', '2026-04-26');
    const ref2 = (setDocMock.mock.calls[0] as unknown[])[0] as { id: string };

    expect(ref1.id).toBe(ref2.id);
    expect(ref1.id).toBe('habit-x__2026-04-26');
  });

  it('legacy random-id duplicate: toggleCompletion sweeps it instead of leaving siblings', async () => {
    // Canonical doesn't exist...
    getDocMock.mockResolvedValueOnce({ exists: () => false });
    // ...but a legacy random-id doc with matching completedDateLocal exists.
    getDocsMock.mockResolvedValueOnce({
      docs: [
        {
          id: 'random-legacy-id',
          ref: { id: 'random-legacy-id' },
        },
      ],
    });
    // No additional matches via the epoch-keyed query.
    getDocsMock.mockResolvedValueOnce({ docs: [] });

    const result = await toggleCompletion('uid-1', 'habit-7', '2026-04-26');

    // Pre-fix semantics preserved: existing matching row → toggle = remove.
    expect(result.action).toBe('removed');
    // The legacy doc was deleted...
    expect(deleteDocMock).toHaveBeenCalled();
    // ...and no canonical doc was written (preserving the toggle-off shape).
    expect(setDocMock).not.toHaveBeenCalled();
  });

  it('removing the canonical doc also sweeps any leftover legacy duplicates', async () => {
    getDocMock.mockResolvedValueOnce({ exists: () => true });
    // Legacy sweep finds one duplicate by completedDateLocal.
    getDocsMock.mockResolvedValueOnce({
      docs: [
        {
          id: 'legacy-1',
          ref: { id: 'legacy-1' },
        },
      ],
    });
    getDocsMock.mockResolvedValueOnce({ docs: [] });

    const result = await toggleCompletion('uid-1', 'habit-7', '2026-04-26');

    expect(result.action).toBe('removed');
    // Two delete calls: canonical + legacy.
    expect(deleteDocMock).toHaveBeenCalledTimes(2);
  });
});

describe('getCompletions / getAllCompletions read-path coalesce', () => {
  it('collapses duplicate (habit_id, date) rows, preferring the canonical-id row', async () => {
    getDocsMock.mockResolvedValueOnce({
      docs: [
        // Legacy random-id row (older).
        {
          id: 'random-legacy',
          data: () => ({
            habitCloudId: 'habit-7',
            completedDateLocal: '2026-04-26',
            completedAt: 1_700_000_000_000,
          }),
        },
        // Canonical-id row (post-fix). Older `completedAt` but canonical wins.
        {
          id: 'habit-7__2026-04-26',
          data: () => ({
            habitCloudId: 'habit-7',
            completedDateLocal: '2026-04-26',
            completedAt: 1_600_000_000_000,
          }),
        },
      ],
    });

    const completions = await getCompletions('uid-1', 'habit-7');

    expect(completions).toHaveLength(1);
    expect(completions[0].id).toBe('habit-7__2026-04-26');
  });

  it('without a canonical row, keeps the row with the newest created_at', async () => {
    getDocsMock.mockResolvedValueOnce({
      docs: [
        {
          id: 'random-1',
          data: () => ({
            habitCloudId: 'habit-7',
            completedDateLocal: '2026-04-26',
            completedAt: 1_700_000_000_000,
          }),
        },
        {
          id: 'random-2',
          data: () => ({
            habitCloudId: 'habit-7',
            completedDateLocal: '2026-04-26',
            completedAt: 1_700_001_000_000,
          }),
        },
      ],
    });

    const completions = await getCompletions('uid-1', 'habit-7');

    expect(completions).toHaveLength(1);
    expect(completions[0].id).toBe('random-2');
  });

  it('does not collapse rows with different habit_id or different date', async () => {
    getDocsMock.mockResolvedValueOnce({
      docs: [
        {
          id: 'habit-7__2026-04-26',
          data: () => ({
            habitCloudId: 'habit-7',
            completedDateLocal: '2026-04-26',
            completedAt: 1,
          }),
        },
        {
          id: 'habit-7__2026-04-27',
          data: () => ({
            habitCloudId: 'habit-7',
            completedDateLocal: '2026-04-27',
            completedAt: 2,
          }),
        },
        {
          id: 'habit-8__2026-04-26',
          data: () => ({
            habitCloudId: 'habit-8',
            completedDateLocal: '2026-04-26',
            completedAt: 3,
          }),
        },
      ],
    });

    const completions = await getAllCompletions('uid-1');

    expect(completions).toHaveLength(3);
  });
});
