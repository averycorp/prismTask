import {
  collection,
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
import type { Course } from '@/types/schoolwork';

/**
 * Firestore client for `users/{uid}/courses`. Mirrors Android's
 * `SyncMapper.courseToMap` / `mapToCourse` exactly so cross-device
 * round-trips don't drift. There is no REST mirror on the backend —
 * the Android schoolwork module is Firestore-only (no
 * `/api/v1/courses` router exists). Read-only on web for now;
 * course CRUD lives on Android.
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

/**
 * Web-side course writes are uncommon (UI lives on Android), but the
 * Today class-row card needs `setDoc(..., { merge: true })` to flip
 * `active`/`sortOrder` if a future PR adds that on web. Exported for
 * future-proofing; currently unused.
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
