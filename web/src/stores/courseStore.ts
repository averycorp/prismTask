import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToCourses,
  getCourses,
} from '@/api/firestore/courses';
import {
  subscribeToCourseCompletions,
  toggleCourseCompletion as remoteToggleCourseCompletion,
  courseCompletionId,
} from '@/api/firestore/courseCompletions';
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
 * Listeners mount via `useFirestoreSync.ts`. Read-only on web — Android
 * remains the only write path for `course` rows.
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
    return () => {
      unsubCourses();
      unsubCompletions();
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
}));
