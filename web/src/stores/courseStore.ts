import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToCourses,
  getCourses,
  createCourse as remoteCreateCourse,
  updateCourse as remoteUpdateCourse,
  deleteCourse as remoteDeleteCourse,
  type CourseInput,
} from '@/api/firestore/courses';
import {
  subscribeToCourseCompletions,
  toggleCourseCompletion as remoteToggleCourseCompletion,
  courseCompletionId,
} from '@/api/firestore/courseCompletions';
import { deleteAssignmentsForCourse } from '@/api/firestore/assignments';
import { useAssignmentStore } from '@/stores/assignmentStore';
import type { Course, CourseCompletion } from '@/types/schoolwork';

/**
 * Zustand store for schoolwork data. Mirrors Android's
 * `SchoolworkRepository` + `DailyEssentialsUseCase.SchoolworkCardState`
 * data shape:
 *
 *   - `courses`: full list, both active + archived (Today filters to
 *     active only).
 *   - `completions`: every CourseCompletion doc for the user. We don't
 *     prune client-side — Firestore size is fine for now and the
 *     `getCompletionsForDate` query path is reserved for future
 *     analytics surfaces.
 *
 * Listeners mount via `useFirestoreSync.ts`. Parity F.2 follow-up: web
 * is now a full read+write surface for course rows; the `createCourse`
 * / `updateCourse` / `archiveCourse` / `deleteCourse` actions wrap the
 * firestore module's write helpers and surface errors via store state.
 */

export interface CourseState {
  courses: Course[];
  completions: CourseCompletion[];
  isLoading: boolean;
  error: string | null;

  fetch: () => Promise<void>;
  subscribe: (uid: string) => Unsubscribe;
  toggleCompletion: (courseId: string, dateMillis: number) => Promise<void>;
  getCompletionForDate: (
    courseId: string,
    dateMillis: number,
  ) => CourseCompletion | null;

  // CRUD
  createCourse: (input: Omit<CourseInput, 'sortOrder'> & { sortOrder?: number }) => Promise<Course>;
  updateCourse: (courseId: string, patch: Partial<CourseInput>) => Promise<void>;
  archiveCourse: (courseId: string) => Promise<void>;
  unarchiveCourse: (courseId: string) => Promise<void>;
  deleteCourse: (courseId: string) => Promise<void>;
}

import { getFirebaseUid } from '@/stores/firebaseUid';

export const useCourseStore = create<CourseState>((set, get) => ({
  courses: [],
  completions: [],
  isLoading: false,
  error: null,

  fetch: async () => {
    set({ isLoading: true, error: null });
    try {
      const uid = getFirebaseUid();
      const courses = await getCourses(uid);
      set({ courses, isLoading: false });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load courses';
      set({ error: message, isLoading: false });
    }
  },

  subscribe: (uid) => {
    const unsubCourses = subscribeToCourses(uid, (courses) => set({ courses }));
    const unsubCompletions = subscribeToCourseCompletions(uid, (completions) =>
      set({ completions }),
    );
    // Bundled in here (rather than wired separately in
    // `useFirestoreSync.ts`) so the schoolwork surface keeps a single
    // mount/unmount lifecycle. The assignmentStore reset on sign-out
    // is triggered here too, mirroring the resets useFirestoreSync
    // performs for other stores.
    const unsubAssignments = useAssignmentStore.getState().subscribe(uid);
    return () => {
      unsubCourses();
      unsubCompletions();
      unsubAssignments();
      useAssignmentStore.getState().reset();
    };
  },

  toggleCompletion: async (courseId, dateMillis) => {
    try {
      const uid = getFirebaseUid();
      const current = get().getCompletionForDate(courseId, dateMillis);
      const nextCompleted = !(current?.completed ?? false);
      const updated = await remoteToggleCourseCompletion(
        uid,
        courseId,
        dateMillis,
        nextCompleted,
      );
      // Optimistic local replace — the snapshot listener will reconcile.
      set((s) => {
        const filtered = s.completions.filter((c) => c.id !== updated.id);
        return { completions: [...filtered, updated] };
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to toggle course';
      set({ error: message });
    }
  },

  getCompletionForDate: (courseId, dateMillis) => {
    const id = courseCompletionId(courseId, dateMillis);
    return get().completions.find((c) => c.id === id) ?? null;
  },

  createCourse: async (input) => {
    const uid = getFirebaseUid();
    // Default `sortOrder` to (max existing + 1) so newly-created courses
    // land at the end of the list in the order the user added them.
    const maxOrder = get().courses.reduce(
      (acc, c) => (c.sortOrder > acc ? c.sortOrder : acc),
      -1,
    );
    const resolved: CourseInput = {
      name: input.name,
      code: input.code,
      color: input.color,
      icon: input.icon,
      active: input.active,
      sortOrder: input.sortOrder ?? maxOrder + 1,
      createDailyTask: input.createDailyTask,
    };
    try {
      const course = await remoteCreateCourse(uid, resolved);
      // Optimistic local append — the snapshot listener will reconcile.
      set((s) => ({ courses: [...s.courses, course] }));
      return course;
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to create course';
      set({ error: message });
      throw err;
    }
  },

  updateCourse: async (courseId, patch) => {
    const uid = getFirebaseUid();
    try {
      await remoteUpdateCourse(uid, courseId, patch);
      // Optimistic local merge — same shape as toggleCompletion.
      set((s) => ({
        courses: s.courses.map((c) =>
          c.id === courseId ? { ...c, ...patch, updatedAt: Date.now() } : c,
        ),
      }));
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to update course';
      set({ error: message });
      throw err;
    }
  },

  archiveCourse: async (courseId) => {
    await get().updateCourse(courseId, { active: false });
  },

  unarchiveCourse: async (courseId) => {
    await get().updateCourse(courseId, { active: true });
  },

  deleteCourse: async (courseId) => {
    const uid = getFirebaseUid();
    try {
      // Firestore doesn't enforce FK cascade; clean up child assignments
      // first so we don't leave orphans that the SchoolworkTodayCard
      // would then surface in the "no parent course" section forever.
      await deleteAssignmentsForCourse(uid, courseId);
      await remoteDeleteCourse(uid, courseId);
      set((s) => ({ courses: s.courses.filter((c) => c.id !== courseId) }));
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to delete course';
      set({ error: message });
      throw err;
    }
  },
}));
