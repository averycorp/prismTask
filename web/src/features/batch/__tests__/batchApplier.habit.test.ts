import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { BatchUndoLogEntry, ProposedMutation } from '@/types/batch';

/**
 * Web parity for habit batch ops. Verifies the canonical-row dedup
 * contract for HABIT.COMPLETE and HABIT.SKIP — both shape habit_completion
 * writes through the natural-key doc id `${habitId}__${dateLocal}` so two
 * devices running the same batch on the same day collapse onto one
 * Firestore doc rather than producing siblings.
 */

const {
  setDocMock,
  getDocMock,
  getDocsMock,
  deleteDocMock,
  updateDocMock,
  docMock,
  collectionMock,
} = vi.hoisted(() => {
  const setDocMock = vi.fn(async () => undefined);
  const getDocMock = vi.fn();
  const getDocsMock = vi.fn();
  const deleteDocMock = vi.fn(async () => undefined);
  const updateDocMock = vi.fn(async () => undefined);
  const docMock = vi.fn((..._segments: unknown[]) => {
    const segs = _segments.filter((s): s is string => typeof s === 'string');
    return { id: segs[segs.length - 1], path: segs.join('/') };
  });
  const collectionMock = vi.fn(
    (_db: unknown, ...segments: string[]) => ({ path: segments.join('/') }),
  );
  return {
    setDocMock,
    getDocMock,
    getDocsMock,
    deleteDocMock,
    updateDocMock,
    docMock,
    collectionMock,
  };
});

vi.mock('firebase/firestore', () => ({
  doc: docMock,
  updateDoc: updateDocMock,
  getDoc: getDocMock,
  setDoc: setDocMock,
  deleteDoc: deleteDocMock,
  collection: collectionMock,
  query: vi.fn(),
  where: vi.fn(),
  getDocs: getDocsMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: {} }));
vi.mock('@/api/firestore/converters', () => ({
  dateStrToTimestamp: (iso: string) => Date.parse(iso),
}));
vi.mock('@/api/firestore/tasks', () => ({ setTagsForTask: vi.fn() }));
vi.mock('@/api/firestore/tags', () => ({
  getTags: vi.fn().mockResolvedValue([]),
  createTag: vi.fn(),
}));
vi.mock('@/api/firestore/medicationSlots', async () => {
  const actual = await vi.importActual<typeof import('@/api/firestore/medicationSlots')>(
    '@/api/firestore/medicationSlots',
  );
  return {
    ...actual,
    getTierState: vi.fn(),
    setTierState: vi.fn(),
    clearTierState: vi.fn(),
  };
});

import { applyMutation, undoEntry } from '../batchApplier';

const UID = 'user-1';

function habitMutation(
  mutationType: ProposedMutation['mutation_type'],
  values: Record<string, unknown>,
): ProposedMutation {
  return {
    entity_type: 'HABIT',
    entity_id: 'habit-1',
    mutation_type: mutationType,
    proposed_new_values: values,
    human_readable_description: `${mutationType} on habit-1`,
  };
}

beforeEach(() => {
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  getDocMock.mockReset();
  getDocsMock.mockReset();
  deleteDocMock.mockReset();
  deleteDocMock.mockResolvedValue(undefined);
  updateDocMock.mockReset();
  updateDocMock.mockResolvedValue(undefined);
  docMock.mockClear();
  collectionMock.mockClear();

  // Every habit op starts with a `getDoc(habitDoc(...))` existence check.
  getDocMock.mockResolvedValue({
    exists: () => true,
    data: () => ({ name: 'Hydrate', isArchived: false }),
  });
});

describe('applyMutation — HABIT.COMPLETE canonical-row dedup', () => {
  it('writes via setDoc to a deterministic doc id of `${habitId}__${date}`', async () => {
    const result = await applyMutation(
      UID,
      habitMutation('COMPLETE', { date: '2026-04-26' }),
    );

    expect(result.applied).toBe(true);
    expect(setDocMock).toHaveBeenCalledTimes(1);
    const ref = (setDocMock.mock.calls[0] as unknown[])[0] as { id: string; path: string };
    expect(ref.id).toBe('habit-1__2026-04-26');
    expect(ref.path).toContain('habit_completions/habit-1__2026-04-26');
  });

  it('uses { merge: true } so parallel writers converge to one doc', async () => {
    await applyMutation(
      UID,
      habitMutation('COMPLETE', { date: '2026-04-26' }),
    );

    const opts = (setDocMock.mock.calls[0] as unknown[])[2] as { merge: boolean } | undefined;
    expect(opts).toEqual({ merge: true });
  });

  it('writes both completedDate (epoch back-compat) and completedDateLocal (TZ-neutral key)', async () => {
    await applyMutation(
      UID,
      habitMutation('COMPLETE', { date: '2026-04-26' }),
    );

    const written = (setDocMock.mock.calls[0] as unknown[])[1] as Record<string, unknown>;
    expect(written.habitCloudId).toBe('habit-1');
    expect(written.completedDateLocal).toBe('2026-04-26');
    expect(typeof written.completedDate).toBe('number');
  });

  it('records the deterministic doc id in the undo log so undo deletes the same doc', async () => {
    const result = await applyMutation(
      UID,
      habitMutation('COMPLETE', { date: '2026-04-26' }),
    );

    expect(result.entry?.pre_state.completion_doc_id).toBe('habit-1__2026-04-26');

    // Undo the COMPLETE: deletes the canonical doc.
    await undoEntry(UID, result.entry as BatchUndoLogEntry);

    expect(deleteDocMock).toHaveBeenCalled();
    const deletedRef = (deleteDocMock.mock.calls[0] as unknown[])[0] as { id: string };
    expect(deletedRef.id).toBe('habit-1__2026-04-26');
  });
});

describe('undoEntry — HABIT.SKIP re-create preserves doc identity', () => {
  it('uses setDoc with the original doc id rather than addDoc-ing a fresh sibling', async () => {
    const entry: BatchUndoLogEntry = {
      entity_type: 'HABIT',
      entity_id: 'habit-1',
      mutation_type: 'SKIP',
      pre_state: {
        date_iso: '2026-04-26',
        deleted_completions: [
          {
            id: 'habit-1__2026-04-26',
            data: {
              habitCloudId: 'habit-1',
              completedDate: 1_745_625_600_000,
              completedDateLocal: '2026-04-26',
              completedAt: 1_745_625_700_000,
              notes: null,
            },
          },
        ],
      },
      applied: true,
    };

    const ok = await undoEntry(UID, entry);

    expect(ok).toBe(true);
    expect(setDocMock).toHaveBeenCalledTimes(1);
    const ref = (setDocMock.mock.calls[0] as unknown[])[0] as { id: string };
    expect(ref.id).toBe('habit-1__2026-04-26');
    const opts = (setDocMock.mock.calls[0] as unknown[])[2] as { merge: boolean } | undefined;
    expect(opts).toEqual({ merge: true });
  });

  it('round-tripping SKIP twice (apply, undo, apply, undo) is idempotent on the canonical doc id', async () => {
    // Apply 1 — SKIP wipes one matching doc.
    getDocsMock.mockResolvedValueOnce({
      docs: [
        {
          id: 'habit-1__2026-04-26',
          ref: { id: 'habit-1__2026-04-26' },
          data: () => ({
            habitCloudId: 'habit-1',
            completedDateLocal: '2026-04-26',
          }),
        },
      ],
    });

    const apply1 = await applyMutation(
      UID,
      habitMutation('SKIP', { date: '2026-04-26' }),
    );
    expect(apply1.applied).toBe(true);

    // Undo 1 — re-create. Verify setDoc with original id.
    setDocMock.mockClear();
    await undoEntry(UID, apply1.entry as BatchUndoLogEntry);
    const ref1 = (setDocMock.mock.calls[0] as unknown[])[0] as { id: string };
    expect(ref1.id).toBe('habit-1__2026-04-26');
  });
});
