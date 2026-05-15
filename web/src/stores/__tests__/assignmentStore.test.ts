import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, getMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  getMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/assignments', () => ({
  subscribeToAssignments: subscribeMock,
  getAssignments: getMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));
vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: () => 'uid-test',
}));

import { useAssignmentStore } from '@/stores/assignmentStore';
import type { Assignment } from '@/types/schoolwork';

const baseAssignment: Assignment = {
  id: 'a1',
  courseId: 'course-1',
  title: 'Reading Ch 3',
  dueDate: null,
  completed: false,
  completedAt: null,
  notes: null,
  createdAt: 0,
  updatedAt: 0,
};

const TODAY_MIDNIGHT = (() => {
  const d = new Date('2026-05-14T00:00:00Z');
  return d.getTime();
})();
const TOMORROW_MIDNIGHT = TODAY_MIDNIGHT + 24 * 60 * 60 * 1000;

function resetStore() {
  useAssignmentStore.setState({
    assignments: [],
    isLoading: false,
    error: null,
  });
}

describe('useAssignmentStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    getMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no assignments', () => {
    expect(useAssignmentStore.getState().assignments).toEqual([]);
  });

  it('subscribe forwards uid to firestore and pipes snapshots into state', () => {
    const unsub = useAssignmentStore.getState().subscribe('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const cb = subscribeMock.mock.calls[0][1] as (a: Assignment[]) => void;
    cb([baseAssignment]);
    expect(useAssignmentStore.getState().assignments).toEqual([baseAssignment]);
  });

  it('fetch loads via getAssignments and stores the result', async () => {
    getMock.mockResolvedValueOnce([baseAssignment]);
    await useAssignmentStore.getState().fetch();
    expect(getMock).toHaveBeenCalledWith('uid-test');
    expect(useAssignmentStore.getState().assignments).toEqual([baseAssignment]);
    expect(useAssignmentStore.getState().isLoading).toBe(false);
    expect(useAssignmentStore.getState().error).toBe(null);
  });

  it('fetch surfaces errors without clobbering existing assignments', async () => {
    useAssignmentStore.setState({ assignments: [baseAssignment] });
    getMock.mockRejectedValueOnce(new Error('boom'));
    await useAssignmentStore.getState().fetch();
    expect(useAssignmentStore.getState().error).toBe('boom');
    expect(useAssignmentStore.getState().isLoading).toBe(false);
    // Existing rows preserved on failure (the store never empties on
    // error, so a transient blip doesn't blank the Today card).
    expect(useAssignmentStore.getState().assignments).toEqual([baseAssignment]);
  });

  it('reset clears assignments back to empty', () => {
    useAssignmentStore.setState({ assignments: [baseAssignment] });
    useAssignmentStore.getState().reset();
    expect(useAssignmentStore.getState().assignments).toEqual([]);
  });

  describe('dueBetween', () => {
    beforeEach(() => {
      useAssignmentStore.setState({
        assignments: [
          { ...baseAssignment, id: 'no-due', dueDate: null },
          {
            ...baseAssignment,
            id: 'before',
            dueDate: TODAY_MIDNIGHT - 60_000,
          },
          {
            ...baseAssignment,
            id: 'today-am',
            dueDate: TODAY_MIDNIGHT + 9 * 60 * 60 * 1000,
          },
          {
            ...baseAssignment,
            id: 'today-pm',
            dueDate: TODAY_MIDNIGHT + 23 * 60 * 60 * 1000,
          },
          {
            ...baseAssignment,
            id: 'on-tomorrow-boundary',
            dueDate: TOMORROW_MIDNIGHT,
          },
          {
            ...baseAssignment,
            id: 'next-week',
            dueDate: TODAY_MIDNIGHT + 7 * 24 * 60 * 60 * 1000,
          },
        ],
      });
    });

    it('selects only assignments whose dueDate falls in [start, end)', () => {
      const ids = useAssignmentStore
        .getState()
        .dueBetween(TODAY_MIDNIGHT, TOMORROW_MIDNIGHT)
        .map((a) => a.id);
      expect(ids).toEqual(['today-am', 'today-pm']);
    });

    it('omits assignments without a dueDate', () => {
      const result = useAssignmentStore
        .getState()
        .dueBetween(0, Number.MAX_SAFE_INTEGER);
      expect(result.find((a) => a.id === 'no-due')).toBeUndefined();
    });

    it('activeDueBetween additionally filters out completed assignments', () => {
      useAssignmentStore.setState({
        assignments: [
          {
            ...baseAssignment,
            id: 'done',
            completed: true,
            dueDate: TODAY_MIDNIGHT + 60_000,
          },
          {
            ...baseAssignment,
            id: 'todo',
            completed: false,
            dueDate: TODAY_MIDNIGHT + 60_000,
          },
        ],
      });
      const ids = useAssignmentStore
        .getState()
        .activeDueBetween(TODAY_MIDNIGHT, TOMORROW_MIDNIGHT)
        .map((a) => a.id);
      expect(ids).toEqual(['todo']);
    });
  });

  describe('groupByCourse', () => {
    it('buckets assignments by their courseId', () => {
      const grouped = useAssignmentStore.getState().groupByCourse([
        { ...baseAssignment, id: 'a', courseId: 'course-1' },
        { ...baseAssignment, id: 'b', courseId: 'course-1' },
        { ...baseAssignment, id: 'c', courseId: 'course-2' },
      ]);
      expect(grouped.get('course-1')?.map((x) => x.id)).toEqual(['a', 'b']);
      expect(grouped.get('course-2')?.map((x) => x.id)).toEqual(['c']);
      expect(grouped.size).toBe(2);
    });
  });
});
