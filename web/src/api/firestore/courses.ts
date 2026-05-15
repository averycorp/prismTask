import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDocs,
  setDoc,
  query,
  orderBy,
  onSnapshot,
  type Unsubscribe,
  type DocumentData,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';
import type { Course } from '@/types/schoolwork';

/**
 * Firestore client for `users/{uid}/courses`. Mirrors Android's
 * `SyncMapper.courseToMap` / `mapToCourse` exactly so cross-device
 * round-trips don't drift. There is no REST mirror on the backend —
 * the Android schoolwork module is Firestore-only (no
 * `/api/v1/courses` router exists).
 *
 * Web parity (F.2 follow-up): we now expose create / update / delete
 * write paths so the web SchoolworkScreen can edit courses without
 * round-tripping through Android. Field shape matches Android exactly;
 * the `dailyTaskId` column is intentionally omitted (it's a per-device
 * local FK that doesn't round-trip, see SyncMapper.kt:696-699).
 */

function coursesCol(uid: string) {
  return collection(firestore, 'users', uid, 'courses');
}

function courseDoc(uid: string, courseId: string) {
  return doc(firestore, 'users', uid, 'courses', courseId);
}

function docToCourse(docId: string, data: DocumentData): Course {
  return {
    id: docId,
    name: typeof data.name === 'string' ? data.name : '',
    code: typeof data.code === 'string' ? data.code : '',
    color: typeof data.color === 'number' ? data.color : 0,
    icon: typeof data.icon === 'string' ? data.icon : '📚',
    active: typeof data.active === 'boolean' ? data.active : true,
    sortOrder: typeof data.sortOrder === 'number' ? data.sortOrder : 0,
    createdAt: typeof data.createdAt === 'number' ? data.createdAt : 0,
    updatedAt: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
    createDailyTask:
      typeof data.createDailyTask === 'boolean' ? data.createDailyTask : false,
  };
}

export async function getCourses(uid: string): Promise<Course[]> {
  const q = query(coursesCol(uid), orderBy('sortOrder', 'asc'));
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToCourse(d.id, d.data()));
}

export interface CourseInput {
  name: string;
  code: string;
  color: number;
  icon: string;
  active: boolean;
  sortOrder: number;
  createDailyTask: boolean;
}

/**
 * Create a new course row. Returns the resolved {@link Course} with the
 * Firestore-assigned doc id so callers (zustand store, optimistic
 * updates) can address the row immediately. Mirrors Android's
 * `SchoolworkRepository.insertCourse` semantics.
 */
export async function createCourse(
  uid: string,
  input: CourseInput,
): Promise<Course> {
  const now = Date.now();
  const payload = {
    name: input.name,
    code: input.code,
    color: input.color,
    icon: input.icon,
    active: input.active,
    sortOrder: input.sortOrder,
    createdAt: now,
    updatedAt: now,
    createDailyTask: input.createDailyTask,
  };
  const ref = await addDoc(coursesCol(uid), payload);
  return docToCourse(ref.id, payload);
}

/**
 * Patch one or more fields on an existing course. Goes through the LWW
 * guard so a concurrent Android-side write isn't silently clobbered
 * (mirrors the boundaryRules / projects update path).
 */
export async function updateCourse(
  uid: string,
  courseId: string,
  patch: Partial<CourseInput>,
): Promise<void> {
  const now = Date.now();
  const payload: Record<string, unknown> = { updatedAt: now };
  if (patch.name !== undefined) payload.name = patch.name;
  if (patch.code !== undefined) payload.code = patch.code;
  if (patch.color !== undefined) payload.color = patch.color;
  if (patch.icon !== undefined) payload.icon = patch.icon;
  if (patch.active !== undefined) payload.active = patch.active;
  if (patch.sortOrder !== undefined) payload.sortOrder = patch.sortOrder;
  if (patch.createDailyTask !== undefined)
    payload.createDailyTask = patch.createDailyTask;
  await lwwUpdate(
    courseDoc(uid, courseId),
    payload as Parameters<typeof lwwUpdate>[1],
  );
}

/**
 * Hard-delete a course row. Note: the Android FK `assignments.course_id`
 * cascades on delete, but Firestore has no enforced cascade — callers
 * (`courseStore.deleteCourse`) are responsible for also deleting child
 * assignment docs to avoid orphans.
 */
export async function deleteCourse(uid: string, courseId: string): Promise<void> {
  await deleteDoc(courseDoc(uid, courseId));
}

/**
 * Web-side full-doc write. Retained from the original module — used by
 * code paths that already have a fully-populated {@link Course} (e.g.
 * future restore-from-backup flows). Prefer {@link updateCourse} for
 * partial patches so the LWW guard runs.
 */
export async function setCourse(
  uid: string,
  course: Course,
): Promise<void> {
  await setDoc(
    courseDoc(uid, course.id),
    {
      name: course.name,
      code: course.code,
      color: course.color,
      icon: course.icon,
      active: course.active,
      sortOrder: course.sortOrder,
      createdAt: course.createdAt,
      updatedAt: course.updatedAt,
      createDailyTask: course.createDailyTask,
    },
    { merge: true },
  );
}

/**
 * Real-time listener for the user's course list. Same shape as the
 * other `subscribeTo*` exports — wired in `useFirestoreSync.ts`.
 */
export function subscribeToCourses(
  uid: string,
  onChange: (courses: Course[]) => void,
): Unsubscribe {
  const q = query(coursesCol(uid), orderBy('sortOrder', 'asc'));
  return onSnapshot(q, (snap) => {
    onChange(snap.docs.map((d) => docToCourse(d.id, d.data())));
  });
}
