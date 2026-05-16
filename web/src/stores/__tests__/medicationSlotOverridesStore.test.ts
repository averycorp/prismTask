import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/medicationSlotOverrides', () => ({
  subscribeToMedicationSlotOverrides: subscribeMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useMedicationSlotOverridesStore } from '@/stores/medicationSlotOverridesStore';
import type { MedicationSlotOverride } from '@/api/firestore/medicationSlotOverrides';

function resetStore() {
  useMedicationSlotOverridesStore.setState({ overrides: [] });
}

const sampleOverride: MedicationSlotOverride = {
  id: 'override-1',
  medication_id: 'med-cloud-id',
  slot_id: 'slot-cloud-id',
  override_ideal_time: '07:30',
  override_drift_minutes: 60,
  created_at: 1700000000000,
  updated_at: 1700000000000,
};

describe('useMedicationSlotOverridesStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no overrides', () => {
    expect(useMedicationSlotOverridesStore.getState().overrides).toEqual([]);
  });

  it('subscribeToOverrides forwards uid and pipes snapshots into state', () => {
    const unsub = useMedicationSlotOverridesStore
      .getState()
      .subscribeToOverrides('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      overrides: MedicationSlotOverride[],
    ) => void;
    callback([sampleOverride]);
    expect(useMedicationSlotOverridesStore.getState().overrides).toEqual([
      sampleOverride,
    ]);
  });

  it('reset clears overrides back to empty', () => {
    useMedicationSlotOverridesStore.setState({ overrides: [sampleOverride] });
    useMedicationSlotOverridesStore.getState().reset();
    expect(useMedicationSlotOverridesStore.getState().overrides).toEqual([]);
  });
});
