import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/projectRisks', () => ({
  subscribeToAllRisks: subscribeMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useProjectRiskStore } from '@/stores/projectRiskStore';
import type { ProjectRisk } from '@/types/projectRisk';

function resetStore() {
  useProjectRiskStore.setState({ risks: [] });
}

const sampleRisk: ProjectRisk = {
  id: 'risk-1',
  project_id: 'project-cloud-id',
  title: 'Resource constraint',
  level: 'HIGH',
  mitigation: 'Hire more developers',
  resolved_at: null,
  created_at: 1700000000000,
  updated_at: 1700000000000,
};

describe('useProjectRiskStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no risks', () => {
    expect(useProjectRiskStore.getState().risks).toEqual([]);
  });

  it('subscribeToRisks forwards uid and pipes snapshots into state', () => {
    const unsub = useProjectRiskStore.getState().subscribeToRisks('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      risks: ProjectRisk[],
    ) => void;
    callback([sampleRisk]);
    expect(useProjectRiskStore.getState().risks).toEqual([sampleRisk]);
  });

  it('reset clears risks back to empty', () => {
    useProjectRiskStore.setState({ risks: [sampleRisk] });
    useProjectRiskStore.getState().reset();
    expect(useProjectRiskStore.getState().risks).toEqual([]);
  });
});
