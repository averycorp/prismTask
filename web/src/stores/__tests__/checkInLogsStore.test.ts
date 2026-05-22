import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/checkInLogs', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/checkInLogs')
  >('@/api/firestore/checkInLogs');
  return {
    ...actual,
    subscribeToCheckIns: subscribeMock,
  };
});
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useCheckInLogsStore } from '@/stores/checkInLogsStore';
import type { CheckInLog } from '@/api/firestore/checkInLogs';

function resetStore() {
  useCheckInLogsStore.setState({ logs: [] });
}

const sampleLog: CheckInLog = {
  id: '2023-11-15',
  date_iso: '2023-11-15',
  steps_completed_csv: 'hydrated,medicated',
  medications_confirmed: true,
  tasks_reviewed: true,
  habits_completed: false,
  created_at: 1700000000000,
  updated_at: 1700000000000,
};

describe('useCheckInLogsStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with no logs', () => {
    expect(useCheckInLogsStore.getState().logs).toEqual([]);
  });

  it('subscribeToCheckIns forwards uid and pipes snapshots into state', () => {
    const unsub = useCheckInLogsStore.getState().subscribeToCheckIns('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      logs: CheckInLog[],
    ) => void;
    callback([sampleLog]);
    expect(useCheckInLogsStore.getState().logs).toEqual([sampleLog]);
  });

  it('reset clears logs back to empty', () => {
    useCheckInLogsStore.setState({ logs: [sampleLog] });
    useCheckInLogsStore.getState().reset();
    expect(useCheckInLogsStore.getState().logs).toEqual([]);
  });
});
