import { describe, it, expect, vi, beforeEach } from 'vitest';

const { onSnapshotMock, queryMock, orderByMock, collectionMock } = vi.hoisted(
  () => ({
    onSnapshotMock: vi.fn(),
    queryMock: vi.fn((..._args: unknown[]) => ({ __query: true })),
    orderByMock: vi.fn((field: string, dir?: string) => ({
      __orderBy: { field, dir },
    })),
    collectionMock: vi.fn(
      (_db: unknown, ...segments: string[]) => ({ path: segments.join('/') }),
    ),
  }),
);

vi.mock('@/lib/firebase', () => ({ firestore: {} }));
vi.mock('firebase/firestore', () => ({
  addDoc: vi.fn(),
  collection: collectionMock,
  deleteDoc: vi.fn(),
  doc: vi.fn(),
  getDocs: vi.fn(),
  onSnapshot: onSnapshotMock,
  orderBy: orderByMock,
  query: queryMock,
  updateDoc: vi.fn(),
  runTransaction: vi.fn(),
}));

import {
  subscribeToRules,
  type BoundaryRule,
} from '@/api/firestore/boundaryRules';

const UID = 'user-A';

function fakeSnap(
  rules: Array<{
    id: string;
    type?: string;
    label?: string;
    value?: number;
    secondaryValue?: number | null;
    enabled?: boolean;
    createdAt?: number;
    updatedAt?: number;
  }>,
) {
  return {
    docs: rules.map((r) => ({
      id: r.id,
      data: () => ({
        type: r.type ?? 'daily_task_cap',
        label: r.label ?? '',
        value: r.value ?? 0,
        secondaryValue: r.secondaryValue ?? null,
        enabled: r.enabled !== false,
        createdAt: r.createdAt ?? 0,
        updatedAt: r.updatedAt ?? 0,
      }),
    })),
  };
}

describe('subscribeToRules', () => {
  beforeEach(() => {
    onSnapshotMock.mockReset();
    queryMock.mockClear();
    orderByMock.mockClear();
    collectionMock.mockClear();
  });

  it('queries the user-scoped boundary_rules collection ordered by createdAt asc', () => {
    onSnapshotMock.mockImplementation(() => () => undefined);

    subscribeToRules(UID, () => undefined);

    // Collection scoped to the right uid.
    expect(collectionMock).toHaveBeenCalledWith(
      expect.anything(),
      'users',
      UID,
      'boundary_rules',
    );
    // Ordered by createdAt ascending so multi-rule lists render
    // deterministically across devices.
    expect(orderByMock).toHaveBeenCalledWith('createdAt', 'asc');
    expect(queryMock).toHaveBeenCalled();
    expect(onSnapshotMock).toHaveBeenCalledTimes(1);
  });

  it('forwards mapped rules to the callback', () => {
    let onNext: ((snap: ReturnType<typeof fakeSnap>) => void) | null = null;
    onSnapshotMock.mockImplementation((_q, cb) => {
      onNext = cb as typeof onNext;
      return () => undefined;
    });

    const received: BoundaryRule[][] = [];
    subscribeToRules(UID, (rules) => {
      received.push(rules);
    });

    expect(onNext).toBeTruthy();
    onNext!(
      fakeSnap([
        {
          id: 'r1',
          type: 'daily_task_cap',
          label: 'Max 12',
          value: 12,
          enabled: true,
          createdAt: 100,
          updatedAt: 200,
        },
        {
          id: 'r2',
          type: 'work_hours_window',
          label: '09–18',
          value: 9,
          secondaryValue: 18,
          enabled: false,
          createdAt: 300,
          updatedAt: 400,
        },
      ]),
    );

    expect(received).toHaveLength(1);
    expect(received[0]).toHaveLength(2);

    const [r1, r2] = received[0];
    expect(r1).toMatchObject({
      id: 'r1',
      type: 'daily_task_cap',
      label: 'Max 12',
      value: 12,
      secondary_value: null,
      enabled: true,
    });
    expect(r2).toMatchObject({
      id: 'r2',
      type: 'work_hours_window',
      label: '09–18',
      value: 9,
      secondary_value: 18,
      enabled: false,
    });
  });

  it('returns the firestore unsubscribe function for cleanup', () => {
    const unsub = vi.fn();
    onSnapshotMock.mockImplementation(() => unsub);

    const result = subscribeToRules(UID, () => undefined);
    expect(result).toBe(unsub);

    result();
    expect(unsub).toHaveBeenCalledTimes(1);
  });

  it('emits an empty array when the snapshot has no docs (signed-in user with no rules)', () => {
    let onNext: ((snap: ReturnType<typeof fakeSnap>) => void) | null = null;
    onSnapshotMock.mockImplementation((_q, cb) => {
      onNext = cb as typeof onNext;
      return () => undefined;
    });

    let lastEmit: BoundaryRule[] | null = null;
    subscribeToRules(UID, (rules) => {
      lastEmit = rules;
    });

    onNext!(fakeSnap([]));
    expect(lastEmit).toEqual([]);
  });
});
