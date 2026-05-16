import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/medicationSlotsExt', () => ({
  subscribeToMedicationSlotsExt: subscribeMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useMedicationSlotsExtStore } from '@/stores/medicationSlotsExtStore';
import type { MedicationSlotExt } from '@/api/firestore/medicationSlotsExt';

function resetStore() {
  useMedicationSlotsExtStore.setState({ slots: [] });
}

const sampleSlot: MedicationSlotExt = {
  id: 'slot-1',
  name: 'Morning',
  ideal_time: '09:00',
  drift_minutes: 180,
  sort_order: 0,
  is_active: true,
  reminder_mode: null,
  reminder_interval_minutes: null,
  created_at: 1700000000000,
  updated_at: 1700000000000,
};

describe('useMedicationSlotsExtStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no slots', () => {
    expect(useMedicationSlotsExtStore.getState().slots).toEqual([]);
  });

  it('subscribeToMedicationSlots forwards uid and pipes snapshots into state', () => {
    const unsub = useMedicationSlotsExtStore
      .getState()
      .subscribeToMedicationSlots('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      slots: MedicationSlotExt[],
    ) => void;
    callback([sampleSlot]);
    expect(useMedicationSlotsExtStore.getState().slots).toEqual([sampleSlot]);
  });

  it('reset clears slots back to empty', () => {
    useMedicationSlotsExtStore.setState({ slots: [sampleSlot] });
    useMedicationSlotsExtStore.getState().reset();
    expect(useMedicationSlotsExtStore.getState().slots).toEqual([]);
  });
});
