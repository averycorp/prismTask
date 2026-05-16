import { describe, it, expect, beforeEach, vi } from 'vitest';

const { subscribeMock, unsubscribeMock } = vi.hoisted(() => ({
  subscribeMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/sortPreferences', () => ({
  subscribeToSortPreferences: subscribeMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useSortPreferencesStore } from '@/stores/sortPreferencesStore';
import type { SortPreferencesSnapshot } from '@/api/firestore/sortPreferences';

function resetStore() {
  useSortPreferencesStore.setState({
    snapshot: { updated_at: 0, preferences: {} },
  });
}

const sampleSnapshot: SortPreferencesSnapshot = {
  updated_at: 1700000000000,
  preferences: {
    sort_today: 'priority_desc',
    sort_direction_today: 'desc',
    sort_project_cloud_abc123: 'due_date_asc',
  },
};

describe('useSortPreferencesStore', () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    unsubscribeMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    resetStore();
  });

  it('starts with an empty snapshot', () => {
    expect(useSortPreferencesStore.getState().snapshot).toEqual({
      updated_at: 0,
      preferences: {},
    });
  });

  it('subscribeToSortPreferences forwards uid and pipes snapshots into state', () => {
    const unsub = useSortPreferencesStore
      .getState()
      .subscribeToSortPreferences('uid-1');

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock).toHaveBeenCalledWith('uid-1', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const callback = subscribeMock.mock.calls[0][1] as (
      snapshot: SortPreferencesSnapshot,
    ) => void;
    callback(sampleSnapshot);
    expect(useSortPreferencesStore.getState().snapshot).toEqual(sampleSnapshot);
  });

  it('reset clears the snapshot back to empty', () => {
    useSortPreferencesStore.setState({ snapshot: sampleSnapshot });
    useSortPreferencesStore.getState().reset();
    expect(useSortPreferencesStore.getState().snapshot).toEqual({
      updated_at: 0,
      preferences: {},
    });
  });
});
