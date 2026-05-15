import { describe, it, expect, beforeEach, vi } from 'vitest';

const {
  getMock,
  subscribeMock,
  setOrderMock,
  setHiddenMock,
  setProgressStyleMock,
  unsubscribeMock,
  getFirebaseUidMock,
} = vi.hoisted(() => ({
  getMock: vi.fn(),
  subscribeMock: vi.fn(),
  setOrderMock: vi.fn(),
  setHiddenMock: vi.fn(),
  setProgressStyleMock: vi.fn(),
  unsubscribeMock: vi.fn(),
  getFirebaseUidMock: vi.fn(),
}));

vi.mock('@/api/firestore/dashboardPreferences', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/dashboardPreferences')
  >('@/api/firestore/dashboardPreferences');
  return {
    ...actual,
    getDashboardPreferences: getMock,
    subscribeToDashboardPreferences: subscribeMock,
    setSectionOrder: setOrderMock,
    setHiddenSections: setHiddenMock,
    setProgressStyle: setProgressStyleMock,
  };
});
vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: getFirebaseUidMock,
  setFirebaseUid: vi.fn(),
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useDashboardStore } from '@/stores/dashboardStore';
import {
  DEFAULT_COLLAPSED_SECTIONS,
  DEFAULT_HIDDEN_SECTIONS,
  DEFAULT_PROGRESS_STYLE,
  DEFAULT_SECTION_ORDER,
  type DashboardPreferencesSnapshot,
} from '@/api/firestore/dashboardPreferences';

describe('useDashboardStore (parity C.1f)', () => {
  beforeEach(() => {
    getMock.mockReset();
    subscribeMock.mockReset();
    setOrderMock.mockReset();
    setHiddenMock.mockReset();
    setProgressStyleMock.mockReset();
    unsubscribeMock.mockReset();
    getFirebaseUidMock.mockReset();
    subscribeMock.mockReturnValue(unsubscribeMock);
    setOrderMock.mockResolvedValue(undefined);
    setHiddenMock.mockResolvedValue(undefined);
    setProgressStyleMock.mockResolvedValue(undefined);
    useDashboardStore.getState().reset();
  });

  it('starts with Android-mirrored defaults', () => {
    const state = useDashboardStore.getState();
    expect(state.sectionOrder).toEqual([...DEFAULT_SECTION_ORDER]);
    expect(state.hiddenSections).toEqual([...DEFAULT_HIDDEN_SECTIONS]);
    expect(state.progressStyle).toBe(DEFAULT_PROGRESS_STYLE);
    expect(state.collapsedSections).toEqual([...DEFAULT_COLLAPSED_SECTIONS]);
    expect(state.loaded).toBe(false);
  });

  it('load() pulls from Firestore and reconciles missing default keys', async () => {
    // Android-customized order missing a key newly introduced on web.
    const remote: DashboardPreferencesSnapshot = {
      sectionOrder: ['today_tasks', 'progress'],
      hiddenSections: ['plan_more'],
      progressStyle: 'bar',
      collapsedSections: ['completed'],
    };
    getMock.mockResolvedValue(remote);

    await useDashboardStore.getState().load('uid-1');

    const state = useDashboardStore.getState();
    expect(getMock).toHaveBeenCalledWith('uid-1');
    expect(state.loaded).toBe(true);
    expect(state.hiddenSections).toEqual(['plan_more']);
    expect(state.progressStyle).toBe('bar');
    // Reorder + append-missing-defaults so Settings UI still shows
    // every section.
    expect(state.sectionOrder.slice(0, 2)).toEqual(['today_tasks', 'progress']);
    const missing = DEFAULT_SECTION_ORDER.filter(
      (k) => !['today_tasks', 'progress'].includes(k),
    );
    expect(state.sectionOrder.slice(2)).toEqual(missing);
  });

  it('setSectionOrder optimistically applies + writes Firestore when signed in', () => {
    getFirebaseUidMock.mockReturnValue('uid-7');
    const reorderedSubset = ['plan_more', 'progress', 'overdue'];
    useDashboardStore.getState().setSectionOrder(reorderedSubset);

    const order = useDashboardStore.getState().sectionOrder;
    expect(order.slice(0, 3)).toEqual(reorderedSubset);
    // Missing defaults appended for the Settings UI surface.
    expect(order.length).toBeGreaterThanOrEqual(DEFAULT_SECTION_ORDER.length);
    expect(setOrderMock).toHaveBeenCalledTimes(1);
    expect(setOrderMock).toHaveBeenCalledWith('uid-7', order);
  });

  it('setSectionOrder skips Firestore write when signed out', () => {
    getFirebaseUidMock.mockImplementation(() => {
      throw new Error('Not authenticated');
    });
    useDashboardStore.getState().setSectionOrder(['progress']);
    expect(setOrderMock).not.toHaveBeenCalled();
    expect(useDashboardStore.getState().sectionOrder[0]).toBe('progress');
  });

  it('setSectionHidden toggles a key and pushes the deduped list', () => {
    getFirebaseUidMock.mockReturnValue('uid-3');
    useDashboardStore.getState().setSectionHidden('plan_more', true);

    let state = useDashboardStore.getState();
    expect(state.hiddenSections).toContain('plan_more');
    expect(setHiddenMock).toHaveBeenLastCalledWith('uid-3', ['plan_more']);

    useDashboardStore.getState().setSectionHidden('plan_more', false);
    state = useDashboardStore.getState();
    expect(state.hiddenSections).not.toContain('plan_more');
    expect(setHiddenMock).toHaveBeenLastCalledWith('uid-3', []);
  });

  it('setSectionHidden is a no-op when state would not change', () => {
    getFirebaseUidMock.mockReturnValue('uid-5');
    // Default state has plan_more visible; turning visible again
    // (hidden=false) is a no-op.
    useDashboardStore.getState().setSectionHidden('plan_more', false);
    expect(setHiddenMock).not.toHaveBeenCalled();
  });

  it('setProgressStyle pushes only when value changes', () => {
    getFirebaseUidMock.mockReturnValue('uid-4');
    useDashboardStore.getState().setProgressStyle('bar');
    expect(setProgressStyleMock).toHaveBeenCalledWith('uid-4', 'bar');
    setProgressStyleMock.mockClear();
    useDashboardStore.getState().setProgressStyle('bar');
    expect(setProgressStyleMock).not.toHaveBeenCalled();
  });

  it('applyRemoteSnapshot replaces local state with the remote shape', () => {
    const remote: DashboardPreferencesSnapshot = {
      sectionOrder: ['progress'],
      hiddenSections: ['today_tasks'],
      progressStyle: 'percentage',
      collapsedSections: ['planned'],
    };
    useDashboardStore.getState().applyRemoteSnapshot(remote);
    const state = useDashboardStore.getState();
    expect(state.hiddenSections).toEqual(['today_tasks']);
    expect(state.progressStyle).toBe('percentage');
    expect(state.collapsedSections).toEqual(['planned']);
    expect(state.loaded).toBe(true);
    // sectionOrder is reconciled so all defaults still surface in
    // the Settings UI.
    expect(state.sectionOrder[0]).toBe('progress');
    expect(state.sectionOrder.length).toBeGreaterThan(1);
  });

  it('subscribeToPrefs wires the Firestore listener and pipes snapshots through', () => {
    const unsub = useDashboardStore.getState().subscribeToPrefs('uid-9');
    expect(subscribeMock).toHaveBeenCalledWith('uid-9', expect.any(Function));
    expect(unsub).toBe(unsubscribeMock);

    const cb = subscribeMock.mock.calls[0][1] as (
      snap: DashboardPreferencesSnapshot,
    ) => void;
    cb({
      sectionOrder: ['today_tasks'],
      hiddenSections: [],
      progressStyle: 'ring',
      collapsedSections: [],
    });
    expect(useDashboardStore.getState().sectionOrder[0]).toBe('today_tasks');
  });

  it('reset restores defaults (used on sign-out)', () => {
    useDashboardStore.setState({
      sectionOrder: ['progress'],
      hiddenSections: ['plan_more'],
      progressStyle: 'bar',
      collapsedSections: [],
      loaded: true,
    });
    useDashboardStore.getState().reset();
    const state = useDashboardStore.getState();
    expect(state.sectionOrder).toEqual([...DEFAULT_SECTION_ORDER]);
    expect(state.hiddenSections).toEqual([...DEFAULT_HIDDEN_SECTIONS]);
    expect(state.progressStyle).toBe(DEFAULT_PROGRESS_STYLE);
    expect(state.loaded).toBe(false);
  });
});
