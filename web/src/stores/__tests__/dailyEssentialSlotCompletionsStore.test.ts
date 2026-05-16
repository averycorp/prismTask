import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/dailyEssentialSlotCompletions', () => ({
  subscribeToDailyEssentialSlotCompletions: subscribeMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useDailyEssentialSlotCompletionsStore } from '@/stores/dailyEssentialSlotCompletionsStore';
import type { DailyEssentialSlotCompletion } from '@/api/firestore/dailyEssentialSlotCompletions';

function resetStore() {
  useDailyEssentialSlotCompletionsStore.setState({ completions: [] });
}

const sampleCompletion: DailyEssentialSlotCompletion = {
  id: 'comp-1',
  date: 1700000000000,
  slot_key: '09:00',
  med_ids_json: '["specific_time:lipitor"]',
  taken_at: 1700000600000,
  created_at: 1700000000000,
  updated_at: 1700000600000,
};

describe('useDailyEssentialSlotCompletionsStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no completions', () => {
    expect(useDailyEssentialSlotCompletionsStore.getState().completions).toEqual(
      [],
    );
  });

  it('subscribeToCompletions forwards uid and pipes snapshots into state', () => {
    const unsub = useDailyEssentialSlotCompletionsStore
      .getState()
      .subscribeToCompletions('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      rows: DailyEssentialSlotCompletion[],
    ) => void;
    callback([sampleCompletion]);
    expect(useDailyEssentialSlotCompletionsStore.getState().completions).toEqual(
      [sampleCompletion],
    );
  });

  it('reset clears completions back to empty', () => {
    useDailyEssentialSlotCompletionsStore.setState({
      completions: [sampleCompletion],
    });
    useDailyEssentialSlotCompletionsStore.getState().reset();
    expect(useDailyEssentialSlotCompletionsStore.getState().completions).toEqual(
      [],
    );
  });
});
