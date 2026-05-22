import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeToAllRisksMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeToAllRisksMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/projectRisks', () => ({
  subscribeToAllRisks: subscribeToAllRisksMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));
vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: () => 'uid-test',
}));

import { useProjectRiskStore } from '@/stores/projectRiskStore';
import type { ProjectRisk } from '@/types/projectRisk';

const baseRisk: ProjectRisk = {
  id: 'risk-1',
  project_id: 'proj-1',
  title: 'Scope Creep',
  level: 'HIGH',
  mitigation: 'Strictly define scope upfront',
  resolved_at: null,
  created_at: 0,
  updated_at: 0,
};

function resetStore() {
  useProjectRiskStore.setState({
    risks: [],
  });
}

describe('useProjectRiskStore', () => {
  beforeEach(() => {
    subscribeToAllRisksMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeToAllRisksMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no risks', () => {
    expect(useProjectRiskStore.getState().risks).toEqual([]);
  });

  it('subscribeToRisks forwards uid to firestore and pipes snapshots into state', () => {
    const unsub = useProjectRiskStore.getState().subscribeToRisks('uid-1');

    expect(subscribeToAllRisksMock).toHaveBeenCalledTimes(1);
    expect(subscribeToAllRisksMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    // Simulate firestore snapshot callback
    const cb = subscribeToAllRisksMock.mock.calls[0][1] as (risks: ProjectRisk[]) => void;
    cb([baseRisk]);
    expect(useProjectRiskStore.getState().risks).toEqual([baseRisk]);
  });

  it('reset clears risks back to empty array', () => {
    useProjectRiskStore.setState({ risks: [baseRisk] });
    expect(useProjectRiskStore.getState().risks).toEqual([baseRisk]);

    useProjectRiskStore.getState().reset();
    expect(useProjectRiskStore.getState().risks).toEqual([]);
  });
});
