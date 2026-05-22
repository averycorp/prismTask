import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/projectPhases', () => ({
  subscribeToAllPhases: subscribeMock,
}));

vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useProjectPhaseStore } from '@/stores/projectPhaseStore';
import type { ProjectPhase } from '@/types/projectPhase';

function resetStore() {
  useProjectPhaseStore.setState({ phases: [] });
}

const samplePhase: ProjectPhase = {
  id: 'phase-1',
  project_id: 'project-cloud-id',
  title: 'Phase 1',
  description: 'First phase',
  color_key: 'blue',
  start_date: null,
  end_date: null,
  version_anchor: 'v1.0.0',
  version_note: 'Initial release',
  order_index: 0,
  completed_at: null,
  created_at: 1700000000000,
  updated_at: 1700000000000,
};

describe('useProjectPhaseStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no phases', () => {
    expect(useProjectPhaseStore.getState().phases).toEqual([]);
  });

  it('subscribeToPhases forwards uid and pipes snapshots into state', () => {
    const unsub = useProjectPhaseStore.getState().subscribeToPhases('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      phases: ProjectPhase[],
    ) => void;
    callback([samplePhase]);
    expect(useProjectPhaseStore.getState().phases).toEqual([samplePhase]);
  });

  it('reset clears phases back to empty', () => {
    useProjectPhaseStore.setState({ phases: [samplePhase] });
    useProjectPhaseStore.getState().reset();
    expect(useProjectPhaseStore.getState().phases).toEqual([]);
  });
});
