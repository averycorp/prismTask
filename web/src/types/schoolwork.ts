/**
 * Schoolwork types — mirrors the Android Room entities at
 * `app/.../data/local/entity/CourseEntity.kt`,
 * `CourseCompletionEntity.kt`, and `AssignmentEntity.kt`. Doc shape
 * matches `SyncMapper.kt`'s `courseToMap` / `courseCompletionToMap` /
 * `assignmentToMap` — Firestore is the canonical store (there is no
 * `/api/v1/courses` REST router on the backend).
 */

export interface Course {
  /** Firestore doc id (acts as the cross-device cloud id). */
  id: string;
  name: string;
  code: string;
  color: number; // ARGB int — same shape Android persists
  icon: string;
  active: boolean;
  sortOrder: number;
  createdAt: number;
  updatedAt: number;
  createDailyTask: boolean;
}

export interface CourseCompletion {
  id: string;
  courseCloudId: string;
  /** Epoch-ms representation of the logical-day midnight, same as Android. */
  date: number;
  completed: boolean;
  completedAt: number | null;
  createdAt: number;
  updatedAt: number;
}

export interface Assignment {
  id: string;
  /** Course doc id this assignment hangs off. */
  courseId: string;
  title: string;
  dueDate: number | null;
  completed: boolean;
  completedAt: number | null;
  notes: string | null;
  createdAt: number;
  updatedAt: number;
}
