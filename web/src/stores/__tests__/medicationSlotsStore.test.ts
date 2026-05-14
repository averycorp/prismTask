import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeToSlotDefsMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeToSlotDefsMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/medicationSlots', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/firestore/medicationSlots')>(
      '@/api/firestore/medicationSlots',
    );
  return {
    ...actual,
    subscribeToSlotDefs: subscribeToSlotDefsMock,
  };
});
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useMedicationSlotsStore } from '@/stores/medicationSlotsStore';
import type { MedicationSlotDef } from '@/api/firestore/medicationSlots';

function resetStore() {
  useMedicationSlotsStore.setState({ slotDefs: [] });
}

const sampleDef: MedicationSlotDef = {
  id: 'slot-1',
  slot_key: 'morning',
  display_name: 'Morning',
  sort_order: 0,
  reminder_mode: null,
  reminder_interval_minutes: null,
  ideal_time: '09:00',
  drift_minutes: 180,
  is_active: true,
  created_at: 0,
  updated_at: 0,
};

describe('useMedicationSlotsStore', () => {
  beforeEach(() => {
    subscribeToSlotDefsMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeToSlotDefsMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no slot defs', () => {
    expect(useMedicationSlotsStore.getState().slotDefs).toEqual([]);
  });

  it('applyRemoteSlotDefs replaces local state with the remote snapshot', () => {
    useMedicationSlotsStore.getState().applyRemoteSlotDefs([sampleDef]);
    expect(useMedicationSlotsStore.getState().slotDefs).toEqual([sampleDef]);
  });

  it('subscribeToSlotDefs forwards uid to Firestore and pipes snapshots into state', () => {
    const unsub = useMedicationSlotsStore
      .getState()
      .subscribeToSlotDefs('uid-1');

    expect(subscribeToSlotDefsMock).toHaveBeenCalledTimes(1);
    expect(subscribeToSlotDefsMock).toHaveBeenCalledWith(
      'uid-1',
      expect.any(Function),
    );
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeToSlotDefsMock.mock.calls[0][1] as (
      defs: MedicationSlotDef[],
    ) => void;
    callback([sampleDef]);
    expect(useMedicationSlotsStore.getState().slotDefs).toEqual([sampleDef]);
  });

  it('reset clears slot defs back to empty', () => {
    useMedicationSlotsStore.setState({ slotDefs: [sampleDef] });
    useMedicationSlotsStore.getState().reset();
    expect(useMedicationSlotsStore.getState().slotDefs).toEqual([]);
  });
});
