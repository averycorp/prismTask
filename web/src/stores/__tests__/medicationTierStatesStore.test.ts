import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/medicationTierStates', () => ({
  subscribeToMedicationTierStates: subscribeMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useMedicationTierStatesStore } from '@/stores/medicationTierStatesStore';
import type { MedicationTierStateExt } from '@/api/firestore/medicationTierStates';

function resetStore() {
  useMedicationTierStatesStore.setState({ states: [] });
}

const sampleState: MedicationTierStateExt = {
  id: 'state-1',
  medication_id: 'med-cloud-id',
  slot_id: 'slot-cloud-id',
  log_date: '2026-05-14',
  tier: 'complete',
  tier_source: 'computed',
  intended_time: null,
  logged_at: 1700000000000,
  created_at: 1700000000000,
  updated_at: 1700000000000,
};

describe('useMedicationTierStatesStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no tier states', () => {
    expect(useMedicationTierStatesStore.getState().states).toEqual([]);
  });

  it('subscribeToTierStates forwards uid and pipes snapshots into state', () => {
    const unsub = useMedicationTierStatesStore
      .getState()
      .subscribeToTierStates('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      states: MedicationTierStateExt[],
    ) => void;
    callback([sampleState]);
    expect(useMedicationTierStatesStore.getState().states).toEqual([
      sampleState,
    ]);
  });

  it('reset clears states back to empty', () => {
    useMedicationTierStatesStore.setState({ states: [sampleState] });
    useMedicationTierStatesStore.getState().reset();
    expect(useMedicationTierStatesStore.getState().states).toEqual([]);
  });
});
