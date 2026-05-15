import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  getAssignments,
  subscribeToAssignments,
  createAssignment as remoteCreateAssignment,
  updateAssignment as remoteUpdateAssignment,
  deleteAssignment as remoteDeleteAssignment,
  type AssignmentInput,
} from '@/api/firestore/assignments';
import type { Assignment } from '@/types/schoolwork';
import { getFirebaseUid } from '@/stores/firebaseUid';

/**
 * Zustand slice for the schoolwork-assignment Firestore collection.
 * Today's `SchoolworkTodayCard` reads from this store to surface real
 * "due today" assignments grouped under each course (replacing PR
 * #1365's keyword-substring fallback, audit follow-up F.2).
 *
 * Parity F.2 follow-up: web is now a full read+write surface. The
 * editor in `SchoolworkScreen.tsx` drives CRUD via this store; the
 * SchoolworkTodayCard's `toggleComplete` shortcut also routes through
 * `toggleComplete()` here so writes are consistent.
 *
 * The Firestore listener mounts inside `courseStore.subscribe()` so the
 * schoolwork surface keeps a single subscribe/unsubscribe lifecycle
 * (and so `useFirestoreSync.ts` doesn't need to grow another hook
 * dependency for an audit follow-up).
 *
 * Day-window filtering ("due today") happens at the selector level via
 * `dueBetween()` so callers can choose their own window; the store
 * never prunes incoming snapshots, since the same data backs future
 * surfaces (per-course assignment list, weekly outlook, etc.).
 */

export interface AssignmentState {
  assignments: Assignment[];
  isLoading: boolean;
  error: string | null;

  fetch: () => Promise<void>;
  subscribe: (uid: string) => Unsubscribe;
  reset: () => void;

  // CRUD
  createAssignment: (input: AssignmentInput) => Promise<Assignment>;
  updateAssignment: (
    assignmentId: string,
    patch: Partial<AssignmentInput>,
  ) => Promise<void>;
  toggleComplete: (assignmentId: string) => Promise<void>;
  deleteAssignment: (assignmentId: string) => Promise<void>;

  // Selectors
  /** All assignments whose `dueDate` falls in `[startMillis, endMillis)`. */
  dueBetween: (startMillis: number, endMillis: number) => Assignment[];
  /** All not-yet-completed assignments due in the supplied window. */
  activeDueBetween: (startMillis: number, endMillis: number) => Assignment[];
  /** Group assignments by course doc id (the Firestore parent cloud id). */
  groupByCourse: (assignments: Assignment[]) => Map<string, Assignment[]>;
  /** All assignments for one course (handy for the per-course list view). */
  forCourse: (courseId: string) => Assignment[];
}

export const useAssignmentStore = create<AssignmentState>((set, get) => ({
  assignments: [],
  isLoading: false,
  error: null,

  fetch: async () => {
    set({ isLoading: true, error: null });
    try {
      const uid = getFirebaseUid();
      const assignments = await getAssignments(uid);
      set({ assignments, isLoading: false });
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Failed to load assignments';
      set({ error: message, isLoading: false });
    }
  },

  subscribe: (uid) => {
    return subscribeToAssignments(uid, (assignments) => set({ assignments }));
  },

  reset: () => set({ assignments: [], isLoading: false, error: null }),

  createAssignment: async (input) => {
    const uid = getFirebaseUid();
    try {
      const assignment = await remoteCreateAssignment(uid, input);
      set((s) => ({ assignments: [...s.assignments, assignment] }));
      return assignment;
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Failed to create assignment';
      set({ error: message });
      throw err;
    }
  },

  updateAssignment: async (assignmentId, patch) => {
    const uid = getFirebaseUid();
    try {
      await remoteUpdateAssignment(uid, assignmentId, patch);
      set((s) => ({
        assignments: s.assignments.map((a) =>
          a.id === assignmentId
            ? { ...a, ...patch, updatedAt: Date.now() }
            : a,
        ),
      }));
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Failed to update assignment';
      set({ error: message });
      throw err;
    }
  },

  toggleComplete: async (assignmentId) => {
    const current = get().assignments.find((a) => a.id === assignmentId);
    if (!current) return;
    const nextCompleted = !current.completed;
    await get().updateAssignment(assignmentId, {
      completed: nextCompleted,
      completedAt: nextCompleted ? Date.now() : null,
    });
  },

  deleteAssignment: async (assignmentId) => {
    const uid = getFirebaseUid();
    try {
      await remoteDeleteAssignment(uid, assignmentId);
      set((s) => ({
        assignments: s.assignments.filter((a) => a.id !== assignmentId),
      }));
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Failed to delete assignment';
      set({ error: message });
      throw err;
    }
  },

  dueBetween: (startMillis, endMillis) => {
    return get().assignments.filter((a) => {
      if (a.dueDate == null) return false;
      return a.dueDate >= startMillis && a.dueDate < endMillis;
    });
  },

  activeDueBetween: (startMillis, endMillis) => {
    return get()
      .dueBetween(startMillis, endMillis)
      .filter((a) => !a.completed);
  },

  groupByCourse: (assignments) => {
    const map = new Map<string, Assignment[]>();
    for (const a of assignments) {
      const list = map.get(a.courseId);
      if (list) {
        list.push(a);
      } else {
        map.set(a.courseId, [a]);
      }
    }
    return map;
  },

  forCourse: (courseId) => {
    return get().assignments.filter((a) => a.courseId === courseId);
  },
}));
