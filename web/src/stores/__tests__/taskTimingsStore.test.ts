import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/taskTimings', () => ({
  subscribeToTaskTimings: subscribeMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useTaskTimingsStore } from '@/stores/taskTimingsStore';
import type { TaskTiming } from '@/api/firestore/taskTimings';

function resetStore() {
  useTaskTimingsStore.setState({ timings: [] });
}

const sampleTiming: TaskTiming = {
  id: 'timing-1',
  task_id: 'task-cloud-id',
  started_at: 1700000000000,
  ended_at: 1700000300000,
  duration_minutes: 5,
  source: 'pomodoro',
  notes: null,
  created_at: 1700000000000,
};

describe('useTaskTimingsStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no timings', () => {
    expect(useTaskTimingsStore.getState().timings).toEqual([]);
  });

  it('subscribeToTaskTimings forwards uid and pipes snapshots into state', () => {
    const unsub = useTaskTimingsStore
      .getState()
      .subscribeToTaskTimings('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      timings: TaskTiming[],
    ) => void;
    callback([sampleTiming]);
    expect(useTaskTimingsStore.getState().timings).toEqual([sampleTiming]);
  });

  it('reset clears timings back to empty', () => {
    useTaskTimingsStore.setState({ timings: [sampleTiming] });
    useTaskTimingsStore.getState().reset();
    expect(useTaskTimingsStore.getState().timings).toEqual([]);
  });
});
