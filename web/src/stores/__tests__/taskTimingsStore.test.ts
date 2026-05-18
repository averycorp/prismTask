import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock, addTaskTimingMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
  addTaskTimingMock: vi.fn(),
}));

vi.mock('@/api/firestore/taskTimings', () => ({
  subscribeToTaskTimings: subscribeMock,
  addTaskTiming: addTaskTimingMock,
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
    addTaskTimingMock.mockReset();
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

  describe('logTiming (Pomodoro write parity)', () => {
    it('optimistically inserts a timing then replaces it with the Firestore-saved row', async () => {
      const saved = { ...sampleTiming, id: 'firestore-doc-1' };
      let observedAfterOptimistic = -1;
      addTaskTimingMock.mockImplementation(async () => {
        // Snapshot state mid-write so we can assert the optimistic row
        // was visible before the Firestore write resolved.
        observedAfterOptimistic =
          useTaskTimingsStore.getState().timings.length;
        return saved;
      });

      const out = await useTaskTimingsStore.getState().logTiming('uid-1', {
        taskCloudId: 'task-cloud-id',
        durationMinutes: 5,
        source: 'pomodoro',
        startedAt: 1_700_000_000_000,
        endedAt: 1_700_000_300_000,
        createdAt: 1_700_000_300_000,
      });

      expect(observedAfterOptimistic).toBe(1);
      expect(out).toBe(saved);
      const finalTimings = useTaskTimingsStore.getState().timings;
      expect(finalTimings).toHaveLength(1);
      expect(finalTimings[0]).toBe(saved);
    });

    it('rolls back the optimistic row when the Firestore write fails', async () => {
      addTaskTimingMock.mockRejectedValueOnce(new Error('boom'));
      await expect(
        useTaskTimingsStore.getState().logTiming('uid-1', {
          taskCloudId: 'task-cloud-id',
          durationMinutes: 5,
          source: 'pomodoro',
        }),
      ).rejects.toThrow('boom');
      expect(useTaskTimingsStore.getState().timings).toEqual([]);
    });

    it('forwards the timing input straight to addTaskTiming without dropping fields', async () => {
      addTaskTimingMock.mockResolvedValueOnce(sampleTiming);
      await useTaskTimingsStore.getState().logTiming('uid-1', {
        taskCloudId: 'task-cloud-id',
        durationMinutes: 25,
        source: 'pomodoro',
        startedAt: 1_700_000_000_000,
        endedAt: 1_700_001_500_000,
        notes: 'session 1',
        createdAt: 1_700_001_500_000,
      });
      expect(addTaskTimingMock).toHaveBeenCalledTimes(1);
      const args = addTaskTimingMock.mock.calls[0] as unknown[];
      expect(args[0]).toBe('uid-1');
      expect(args[1]).toMatchObject({
        taskCloudId: 'task-cloud-id',
        durationMinutes: 25,
        source: 'pomodoro',
        startedAt: 1_700_000_000_000,
        endedAt: 1_700_001_500_000,
        notes: 'session 1',
        createdAt: 1_700_001_500_000,
      });
    });
  });
});
