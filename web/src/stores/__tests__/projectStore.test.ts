import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * Web-Android parity: `archiveProject` / `reopenProject` are the
 * counterpart to the Android `ProjectRepository.archiveProject` /
 * `reopenProject` helpers wired through `ProjectListViewModel`.
 *
 * They flip `status` between `'active'` and `'archived'` and write back
 * via the LWW-guarded `firestoreProjects.updateProject` helper.
 */

const {
  updateProjectMock,
  getFirebaseUidMock,
} = vi.hoisted(() => ({
  updateProjectMock: vi.fn(),
  getFirebaseUidMock: vi.fn(() => 'uid-test'),
}));

vi.mock('@/api/firestore/projects', () => ({
  updateProject: updateProjectMock,
  // Stubbed to keep imports happy; not exercised in these tests.
  getProjects: vi.fn(),
  getProject: vi.fn(),
  createProject: vi.fn(),
  deleteProject: vi.fn(),
  subscribeToProjects: vi.fn(),
}));

vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: getFirebaseUidMock,
}));

vi.mock('@/api/goals', () => ({
  goalsApi: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
}));

import { useProjectStore } from '@/stores/projectStore';
import type { Project } from '@/types/project';

function makeProject(overrides: Partial<Project> = {}): Project {
  return {
    id: 'proj-1',
    goal_id: '',
    user_id: 'uid-test',
    title: 'Side project',
    description: null,
    status: 'active',
    due_date: null,
    color: '#4A90D9',
    icon: '📁',
    sort_order: 0,
    created_at: new Date(0).toISOString(),
    updated_at: new Date(0).toISOString(),
    ...overrides,
  };
}

beforeEach(() => {
  updateProjectMock.mockReset();
  getFirebaseUidMock.mockReset();
  getFirebaseUidMock.mockReturnValue('uid-test');
  useProjectStore.setState({
    projects: [makeProject()],
    goals: [],
    selectedProject: null,
    selectedGoal: null,
    isLoading: false,
    error: null,
  });
});

describe('archiveProject', () => {
  it('writes status=archived via firestore and updates the in-memory list', async () => {
    updateProjectMock.mockResolvedValue(makeProject({ status: 'archived' }));

    const result = await useProjectStore.getState().archiveProject('proj-1');

    expect(updateProjectMock).toHaveBeenCalledTimes(1);
    expect(updateProjectMock).toHaveBeenCalledWith('uid-test', 'proj-1', {
      status: 'archived',
    });
    expect(result.status).toBe('archived');

    const stored = useProjectStore.getState().projects.find((p) => p.id === 'proj-1');
    expect(stored?.status).toBe('archived');
  });

  it('surfaces firestore errors so the caller can toast', async () => {
    updateProjectMock.mockRejectedValue(new Error('boom'));
    await expect(
      useProjectStore.getState().archiveProject('proj-1'),
    ).rejects.toThrow('boom');
  });
});

describe('reopenProject', () => {
  it('writes status=active via firestore and updates the in-memory list', async () => {
    useProjectStore.setState({
      projects: [makeProject({ status: 'archived' })],
    });
    updateProjectMock.mockResolvedValue(makeProject({ status: 'active' }));

    const result = await useProjectStore.getState().reopenProject('proj-1');

    expect(updateProjectMock).toHaveBeenCalledWith('uid-test', 'proj-1', {
      status: 'active',
    });
    expect(result.status).toBe('active');

    const stored = useProjectStore.getState().projects.find((p) => p.id === 'proj-1');
    expect(stored?.status).toBe('active');
  });

  it('leaves other projects in the list untouched', async () => {
    const other = makeProject({ id: 'proj-2', title: 'Other' });
    useProjectStore.setState({
      projects: [makeProject({ status: 'archived' }), other],
    });
    updateProjectMock.mockResolvedValue(makeProject({ status: 'active' }));

    await useProjectStore.getState().reopenProject('proj-1');

    const stored = useProjectStore.getState().projects.find((p) => p.id === 'proj-2');
    expect(stored).toEqual(other);
  });
});
