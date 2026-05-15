import {
  collection,
  doc,
  getDocs,
  setDoc,
  query,
  onSnapshot,
  type Unsubscribe,
  type DocumentData,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import type { Assignment } from '@/types/schoolwork';

/**
 * Firestore client for `users/{uid}/assignments`. Mirrors Android's
 * `SyncMapper.assignmentToMap` / `mapToAssignment` exactly — see
 * `app/.../data/remote/mapper/SyncMapper.kt:1331-1362`.
 *
 * Schoolwork (courses + assignments) is Firestore-only; there is no
 * `/api/v1/courses` REST router on the backend. Web is read-only —
 * assignment CRUD lives on Android. The Today schoolwork section
 * consumes this via `assignmentStore` to show a real "due today"
 * grouping under each course, replacing PR #1365's keyword-substring
 * fallback (audit follow-up F.2).
 *
 * Doc shape note: the backend stores `courseId` as the *cloud id* of
 * the parent course (the Firestore doc id from `users/{uid}/courses`),
 * not the local Room PK. That's already how `assignmentToMap` writes
 * the field, so this client can use it directly to group assignments
 * under a `Course.id` (the Firestore doc id) on the client side.
 */

function assignmentsCol(uid: string) {
  return collection(firestore, 'users', uid, 'assignments');
}

function assignmentDoc(uid: string, assignmentId: string) {
  return doc(firestore, 'users', uid, 'assignments', assignmentId);
}

function docToAssignment(docId: string, data: DocumentData): Assignment {
  return {
    id: docId,
    // Falls back to empty string when the parent course hasn't synced
    // yet — the orphan list in SchoolworkTodayCard handles that case.
    courseId: typeof data.courseId === 'string' ? data.courseId : '',
    title: typeof data.title === 'string' ? data.title : '',
    dueDate: typeof data.dueDate === 'number' ? data.dueDate : null,
    completed: typeof data.completed === 'boolean' ? data.completed : false,
    completedAt:
      typeof data.completedAt === 'number' ? data.completedAt : null,
    notes: typeof data.notes === 'string' ? data.notes : null,
    createdAt: typeof data.createdAt === 'number' ? data.createdAt : 0,
    updatedAt: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

export async function getAssignments(uid: string): Promise<Assignment[]> {
  const snap = await getDocs(query(assignmentsCol(uid)));
  return snap.docs.map((d) => docToAssignment(d.id, d.data()));
}

/**
 * Web-side write path. Currently unused — Android remains the canonical
 * editor for assignment rows — but exported so a future PR can flip an
 * assignment's `completed` flag from the Today card without going back
 * through this module. Uses `setDoc(merge: true)` so partial patches
 * don't clobber unrelated fields.
 */
export async function setAssignment(
  uid: string,
  assignment: Assignment,
): Promise<void> {
  await setDoc(
    assignmentDoc(uid, assignment.id),
    {
      courseId: assignment.courseId,
      title: assignment.title,
      dueDate: assignment.dueDate,
      completed: assignment.completed,
      completedAt: assignment.completedAt,
      notes: assignment.notes,
      createdAt: assignment.createdAt,
      updatedAt: assignment.updatedAt,
    },
    { merge: true },
  );
}

/**
 * Real-time listener — mirrors the `subscribeTo*` shape every other
 * Firestore module exposes. Mounted in `courseStore.subscribe()` so it
 * shares the schoolwork lifecycle (no extra wiring in
 * `useFirestoreSync.ts`).
 */
export function subscribeToAssignments(
  uid: string,
  onChange: (assignments: Assignment[]) => void,
): Unsubscribe {
  return onSnapshot(assignmentsCol(uid), (snap) => {
    onChange(snap.docs.map((d) => docToAssignment(d.id, d.data())));
  });
}
