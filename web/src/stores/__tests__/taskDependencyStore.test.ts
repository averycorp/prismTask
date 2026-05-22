import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/taskDependencies', () => ({
  subscribeToDependencies: subscribeMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useTaskDependencyStore } from '@/stores/taskDependencyStore';
import type { TaskDependency } from '@/types/taskDependency';

function resetStore() {
  useTaskDependencyStore.setState({ dependencies: [] });
}

const sampleDependency: TaskDependency = {
  id: 'dep-1',
  blocker_task_id: 'task-1',
  blocked_task_id: 'task-2',
  created_at: 1700000000000,
};

describe('useTaskDependencyStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no dependencies', () => {
    expect(useTaskDependencyStore.getState().dependencies).toEqual([]);
  });

  it('subscribeToDependencies forwards uid and pipes snapshots into state', () => {
    const unsub = useTaskDependencyStore.getState().subscribeToDependencies('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      dependencies: TaskDependency[],
    ) => void;
    callback([sampleDependency]);
    expect(useTaskDependencyStore.getState().dependencies).toEqual([sampleDependency]);
  });

  it('reset clears dependencies back to empty', () => {
    useTaskDependencyStore.setState({ dependencies: [sampleDependency] });
    useTaskDependencyStore.getState().reset();
    expect(useTaskDependencyStore.getState().dependencies).toEqual([]);
  });
});
