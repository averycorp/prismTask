import {
  collection,
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
import type { CourseCompletion } from '@/types/schoolwork';

/**
 * Firestore client for `users/{uid}/course_completions`. Mirrors Android
 * `SyncMapper.courseCompletionToMap` / `mapToCourseCompletion`. Each doc
 * represents one (courseCloudId, date-midnight-millis) toggle.
 *
 * Doc-id strategy: deterministic `${courseCloudId}__${date}` so two
 * devices toggling the same class on the same day collapse to a single
 * row. Matches the canonical-row-dedup pattern PR #1121 introduced for
 * habit_completions and that mood_energy_logs uses.
 */

function completionsCol(uid: string) {
  return collection(firestore, 'users', uid, 'course_completions');
}

function completionDoc(uid: string, completionId: string) {
  return doc(firestore, 'users', uid, 'course_completions', completionId);
}

export function courseCompletionId(courseCloudId: string, dateMillis: number): string {
  return `${courseCloudId}__${dateMillis}`;
}

function docToCompletion(docId: string, data: DocumentData): CourseCompletion {
  return {
    id: docId,
    courseCloudId: typeof data.courseCloudId === 'string' ? data.courseCloudId : '',
    date: typeof data.date === 'number' ? data.date : 0,
    completed: typeof data.completed === 'boolean' ? data.completed : false,
    completedAt: typeof data.completedAt === 'number' ? data.completedAt : null,
    createdAt: typeof data.createdAt === 'number' ? data.createdAt : 0,
    updatedAt: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

export async function getCompletionsForDate(
  uid: string,
  dateMillis: number,
): Promise<CourseCompletion[]> {
  const q = query(completionsCol(uid), where('date', '==', dateMillis));
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToCompletion(d.id, d.data()));
}

/**
 * Toggle a course's completion for the supplied logical-day midnight.
 * Idempotent — uses `setDoc(merge: true)` keyed on the deterministic
 * `(courseCloudId, dateMillis)` id, so re-clicks settle to the new state
 * rather than spawning duplicate rows.
 */
export async function toggleCourseCompletion(
  uid: string,
  courseCloudId: string,
  dateMillis: number,
  completed: boolean,
): Promise<CourseCompletion> {
  const id = courseCompletionId(courseCloudId, dateMillis);
  const now = Date.now();
  const data = {
    courseCloudId,
    date: dateMillis,
    completed,
    completedAt: completed ? now : null,
    createdAt: now,
    updatedAt: now,
  };
  await setDoc(completionDoc(uid, id), data, { merge: true });
  return { id, ...data };
}

export function subscribeToCourseCompletions(
  uid: string,
  onChange: (completions: CourseCompletion[]) => void,
): Unsubscribe {
  return onSnapshot(completionsCol(uid), (snap) => {
    onChange(snap.docs.map((d) => docToCompletion(d.id, d.data())));
  });
}
