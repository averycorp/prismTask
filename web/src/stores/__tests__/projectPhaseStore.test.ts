import { describe, it, expect, vi, beforeEach } from 'vitest';

const { subscribeToAllPhasesMock } = vi.hoisted(() => ({
  subscribeToAllPhasesMock: vi.fn(),
}));

vi.mock('@/api/firestore/projectPhases', () => ({
  subscribeToAllPhases: subscribeToAllPhasesMock,
}));

import { useProjectPhaseStore } from '../projectPhaseStore';
import type { ProjectPhase } from '@/types/projectPhase';

function makePhase(overrides: Partial<ProjectPhase> = {}): ProjectPhase {
  return {
    id: 'phase-1',
    project_id: 'proj-1',
    title: 'Phase 1',
    description: null,
    color_key: null,
    start_date: null,
    end_date: null,
    version_anchor: null,
    version_note: null,
    order_index: 0,
    completed_at: null,
    created_at: 0,
    updated_at: 0,
    ...overrides,
  };
}

beforeEach(() => {
  subscribeToAllPhasesMock.mockReset();
  useProjectPhaseStore.setState({ phases: [] });
});

describe('useProjectPhaseStore', () => {
  it('initializes with empty phases', () => {
    expect(useProjectPhaseStore.getState().phases).toEqual([]);
  });

  describe('subscribeToPhases', () => {
    it('sets up a listener and updates phases when callback fires', () => {
      const unsubscribeMock = vi.fn();
      let capturedCallback: (phases: ProjectPhase[]) => void = () => {};

      subscribeToAllPhasesMock.mockImplementation((uid: string, callback: (phases: ProjectPhase[]) => void) => {
        capturedCallback = callback;
        return unsubscribeMock;
      });

      const uid = 'test-uid';
      const unsubscribe = useProjectPhaseStore.getState().subscribeToPhases(uid);

      expect(subscribeToAllPhasesMock).toHaveBeenCalledWith(uid, expect.any(Function));
      expect(unsubscribe).toBe(unsubscribeMock);

      const phases = [makePhase()];
      capturedCallback(phases);

      expect(useProjectPhaseStore.getState().phases).toEqual(phases);
    });
  });

  describe('reset', () => {
    it('clears the phases array', () => {
      useProjectPhaseStore.setState({ phases: [makePhase()] });
      expect(useProjectPhaseStore.getState().phases).toHaveLength(1);

      useProjectPhaseStore.getState().reset();

      expect(useProjectPhaseStore.getState().phases).toEqual([]);
    });
  });
});
