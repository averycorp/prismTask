import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/milestones', () => ({
  subscribeToMilestones: subscribeMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useMilestoneStore } from '@/stores/milestoneStore';
import type { Milestone } from '@/api/firestore/milestones';

function resetStore() {
  useMilestoneStore.setState({ milestones: [] });
}

const sampleMilestone: Milestone = {
  id: 'milestone-1',
  project_id: 'project-cloud-id',
  title: 'Launch beta',
  is_completed: false,
  completed_at: null,
  order_index: 0,
  created_at: 1700000000000,
  updated_at: 1700000000000,
};

describe('useMilestoneStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no milestones', () => {
    expect(useMilestoneStore.getState().milestones).toEqual([]);
  });

  it('subscribeToMilestones forwards uid and pipes snapshots into state', () => {
    const unsub = useMilestoneStore.getState().subscribeToMilestones('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      milestones: Milestone[],
    ) => void;
    callback([sampleMilestone]);
    expect(useMilestoneStore.getState().milestones).toEqual([sampleMilestone]);
  });

  it('reset clears milestones back to empty', () => {
    useMilestoneStore.setState({ milestones: [sampleMilestone] });
    useMilestoneStore.getState().reset();
    expect(useMilestoneStore.getState().milestones).toEqual([]);
  });

  it('reset can be called multiple times safely', () => {
    useMilestoneStore.setState({ milestones: [sampleMilestone] });
    useMilestoneStore.getState().reset();
    useMilestoneStore.getState().reset();
    useMilestoneStore.getState().reset();
    expect(useMilestoneStore.getState().milestones).toEqual([]);
  });
});
