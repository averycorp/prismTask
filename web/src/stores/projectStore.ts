import { create } from 'zustand';
import type { Project, ProjectCreate, ProjectUpdate, ProjectDetail } from '@/types/project';
import type { Goal, GoalCreate, GoalUpdate, GoalDetail } from '@/types/goal';
import { goalsApi } from '@/api/goals';
import * as firestoreProjects from '@/api/firestore/projects';
import type { Unsubscribe } from 'firebase/firestore';

interface ProjectState {
  projects: Project[];
  goals: Goal[];
  selectedProject: ProjectDetail | null;
  selectedGoal: GoalDetail | null;
  isLoading: boolean;
  error: string | null;

  // Goals (still use FastAPI backend)
  fetchGoals: () => Promise<void>;
  fetchGoal: (goalId: number) => Promise<GoalDetail>;
  createGoal: (data: GoalCreate) => Promise<Goal>;
  updateGoal: (goalId: number, data: GoalUpdate) => Promise<Goal>;
  deleteGoal: (goalId: number) => Promise<void>;

  // Projects (now use Firestore)
  fetchAllProjects: () => Promise<void>;
  fetchProject: (projectId: string) => Promise<ProjectDetail>;
  createProject: (goalId: string, data: ProjectCreate) => Promise<Project>;
  updateProject: (projectId: string, data: ProjectUpdate) => Promise<Project>;
  archiveProject: (projectId: string) => Promise<Project>;
  reopenProject: (projectId: string) => Promise<Project>;
  deleteProject: (projectId: string) => Promise<void>;

  // Real-time
  subscribeToProjects: (uid: string) => Unsubscribe;

  setSelectedProject: (project: ProjectDetail | null) => void;
  clearError: () => void;
}

import { getFirebaseUid } from '@/stores/firebaseUid';

function getUid(): string {
  return getFirebaseUid();
}

export const useProjectStore = create<ProjectState>((set) => ({
  projects: [],
  goals: [],
  selectedProject: null,
  selectedGoal: null,
  isLoading: false,
  error: null,

  // ── Goals (still FastAPI) ──────────────────────────────────

  fetchGoals: async () => {
    set({ isLoading: true, error: null });
    try {
      const goals = await goalsApi.list();
      set({ goals, isLoading: false });
    } catch (e) {
      set({ error: (e as Error).message, isLoading: false });
    }
  },

  fetchGoal: async (goalId) => {
    const goal = await goalsApi.get(goalId);
    set({ selectedGoal: goal });
    return goal;
  },

  createGoal: async (data) => {
    const goal = await goalsApi.create(data);
    set((state) => ({ goals: [...state.goals, goal] }));
    return goal;
  },

  updateGoal: async (goalId, data) => {
    const updated = await goalsApi.update(goalId, data);
    set((state) => ({
      goals: state.goals.map((g) => (g.id === goalId ? updated : g)),
    }));
    return updated;
  },

  deleteGoal: async (goalId) => {
    await goalsApi.delete(goalId);
    set((state) => ({
      goals: state.goals.filter((g) => g.id !== goalId),
    }));
  },

  // ── Projects (Firestore) ──────────────────────────────────

  fetchAllProjects: async () => {
    set({ isLoading: true, error: null });
    try {
      const uid = getUid();
      const projects = await firestoreProjects.getProjects(uid);
      set({ projects, isLoading: false });
    } catch (e) {
      set({ error: (e as Error).message, isLoading: false });
    }
  },

  fetchProject: async (projectId) => {
    const uid = getUid();
    const project = await firestoreProjects.getProject(uid, projectId);
    if (!project) throw new Error('Project not found');
    set({ selectedProject: project });
    return project;
  },

  createProject: async (_goalId, data) => {
    const uid = getUid();
    const project = await firestoreProjects.createProject(uid, data);
    set((state) => ({ projects: [...state.projects, project] }));
    return project;
  },

  updateProject: async (projectId, data) => {
    const uid = getUid();
    const updated = await firestoreProjects.updateProject(uid, projectId, data as Record<string, unknown>);
    set((state) => ({
      projects: state.projects.map((p) =>
        p.id === projectId ? updated : p,
      ),
    }));
    return updated;
  },

  // Lifecycle helpers — mirror Android `ProjectRepository.archiveProject` /
  // `reopenProject` (which flip status + stamp/clear `archived_at`). The web
  // doc only owns `status`; `archivedAt` / `completedAt` are Android-only
  // fields gated by the LWW guard in `firestore/projects.ts` (parity A.2).
  archiveProject: async (projectId) => {
    const uid = getUid();
    const updated = await firestoreProjects.updateProject(uid, projectId, {
      status: 'archived',
    });
    set((state) => ({
      projects: state.projects.map((p) =>
        p.id === projectId ? updated : p,
      ),
    }));
    return updated;
  },

  reopenProject: async (projectId) => {
    const uid = getUid();
    const updated = await firestoreProjects.updateProject(uid, projectId, {
      status: 'active',
    });
    set((state) => ({
      projects: state.projects.map((p) =>
        p.id === projectId ? updated : p,
      ),
    }));
    return updated;
  },

  deleteProject: async (projectId) => {
    const uid = getUid();
    await firestoreProjects.deleteProject(uid, projectId);
    set((state) => ({
      projects: state.projects.filter((p) => p.id !== projectId),
      selectedProject:
        state.selectedProject?.id === projectId ? null : state.selectedProject,
    }));
  },

  subscribeToProjects: (uid: string) => {
    return firestoreProjects.subscribeToProjects(uid, (projects) => {
      set({ projects });
    });
  },

  setSelectedProject: (project) => set({ selectedProject: project }),
  clearError: () => set({ error: null }),
}));
