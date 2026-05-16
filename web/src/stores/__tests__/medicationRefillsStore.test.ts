import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/medicationRefills', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/medicationRefills')
  >('@/api/firestore/medicationRefills');
  return {
    ...actual,
    subscribeToRefills: subscribeMock,
  };
});
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useMedicationRefillsStore } from '@/stores/medicationRefillsStore';
import type { MedicationRefillDoc } from '@/api/firestore/medicationRefills';

function resetStore() {
  useMedicationRefillsStore.setState({ refills: [] });
}

const sampleRefill: MedicationRefillDoc = {
  id: 'refill-1',
  medication_name: 'Lipitor',
  pill_count: 30,
  pills_per_dose: 1,
  doses_per_day: 1,
  last_refill_date: 1700000000000,
  pharmacy_name: 'CVS',
  pharmacy_phone: null,
  reminder_days_before: 3,
  created_at: 1700000000000,
  updated_at: 1700000000000,
};

describe('useMedicationRefillsStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no refills', () => {
    expect(useMedicationRefillsStore.getState().refills).toEqual([]);
  });

  it('subscribeToRefills forwards uid and pipes snapshots into state', () => {
    const unsub = useMedicationRefillsStore
      .getState()
      .subscribeToRefills('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      refills: MedicationRefillDoc[],
    ) => void;
    callback([sampleRefill]);
    expect(useMedicationRefillsStore.getState().refills).toEqual([
      sampleRefill,
    ]);
  });

  it('reset clears refills back to empty', () => {
    useMedicationRefillsStore.setState({ refills: [sampleRefill] });
    useMedicationRefillsStore.getState().reset();
    expect(useMedicationRefillsStore.getState().refills).toEqual([]);
  });
});
