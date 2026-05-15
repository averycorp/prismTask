import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDocs,
  setDoc,
  query,
  where,
  onSnapshot,
  type Unsubscribe,
  type DocumentData,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';
import type { Assignment } from '@/types/schoolwork';

/**
 * Firestore client for `users/{uid}/assignments`. Mirrors Android's
 * `SyncMapper.assignmentToMap` / `mapToAssignment` exactly — see
 * `app/.../data/remote/mapper/SyncMapper.kt:1331-1362`.
 *
 * Schoolwork (courses + assignments) is Firestore-only; there is no
 * `/api/v1/courses` REST router on the backend. Web parity (F.2
 * follow-up): create / update / delete write paths shipped so the
 * SchoolworkScreen editor can drive CRUD without round-tripping
 * through Android.
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

export interface AssignmentInput {
  courseId: string;
  title: string;
  dueDate: number | null;
  completed: boolean;
  completedAt: number | null;
  notes: string | null;
}

/**
 * Create a new assignment row. Returns the resolved {@link Assignment}
 * with the Firestore-assigned doc id so callers can address the row
 * immediately. Mirrors Android's `SchoolworkRepository.insertAssignment`.
 */
export async function createAssignment(
  uid: string,
  input: AssignmentInput,
): Promise<Assignment> {
  const now = Date.now();
  const payload = {
    courseId: input.courseId,
    title: input.title,
    dueDate: input.dueDate,
    completed: input.completed,
    completedAt: input.completedAt,
    notes: input.notes,
    createdAt: now,
    updatedAt: now,
  };
  const ref = await addDoc(assignmentsCol(uid), payload);
  return docToAssignment(ref.id, payload);
}

/**
 * Patch one or more fields on an existing assignment. LWW-guarded so a
 * concurrent Android-side toggle (e.g. completing the assignment from
 * a widget) doesn't get clobbered by a stale web rename.
 */
export async function updateAssignment(
  uid: string,
  assignmentId: string,
  patch: Partial<AssignmentInput>,
): Promise<void> {
  const now = Date.now();
  const payload: Record<string, unknown> = { updatedAt: now };
  if (patch.courseId !== undefined) payload.courseId = patch.courseId;
  if (patch.title !== undefined) payload.title = patch.title;
  if (patch.dueDate !== undefined) payload.dueDate = patch.dueDate;
  if (patch.completed !== undefined) payload.completed = patch.completed;
  if (patch.completedAt !== undefined) payload.completedAt = patch.completedAt;
  if (patch.notes !== undefined) payload.notes = patch.notes;
  await lwwUpdate(
    assignmentDoc(uid, assignmentId),
    payload as Parameters<typeof lwwUpdate>[1],
  );
}

/** Hard-delete a single assignment row. */
export async function deleteAssignment(
  uid: string,
  assignmentId: string,
): Promise<void> {
  await deleteDoc(assignmentDoc(uid, assignmentId));
}

/**
 * Cascade helper. Android's FK enforces ON DELETE CASCADE for
 * assignments → courses, but Firestore has no enforced cascade, so
 * `courseStore.deleteCourse` must call this before deleting the parent
 * course doc. Returns the count deleted (handy for toast messages).
 */
export async function deleteAssignmentsForCourse(
  uid: string,
  courseId: string,
): Promise<number> {
  const snap = await getDocs(
    query(assignmentsCol(uid), where('courseId', '==', courseId)),
  );
  await Promise.all(snap.docs.map((d) => deleteDoc(d.ref)));
  return snap.docs.length;
}

/**
 * Web-side full-doc write. Retained for callers that already have a
 * fully-populated {@link Assignment} (e.g. the SchoolworkTodayCard
 * toggling completion). Prefer {@link updateAssignment} for partial
 * patches so the LWW guard runs.
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
