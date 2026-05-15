import { useMemo, useState } from 'react';
import {
  ArrowLeft,
  ChevronDown,
  ChevronRight,
  GraduationCap,
  Plus,
  Pencil,
} from 'lucide-react';
import { SyllabusImport } from './SyllabusImport';
import { SyllabusReviewPanel } from './SyllabusReviewPanel';
import { CourseEditor } from './CourseEditor';
import { AssignmentEditor } from './AssignmentEditor';
import { argbToCss } from './courseColor';
import type { SyllabusParseResult } from './syllabusTypes';
import { useCourseStore } from '@/stores/courseStore';
import { useAssignmentStore } from '@/stores/assignmentStore';
import type { Assignment, Course } from '@/types/schoolwork';

/**
 * Schoolwork landing screen.
 *
 * Pre-PR #1440 this screen only hosted syllabus import. Parity F.2
 * follow-up adds full course + assignment CRUD on web so the editor
 * doesn't have to live behind Android. The screen now renders three
 * stacked sections:
 *
 *   1. Active courses (with their assignments expandable underneath).
 *   2. Archived courses (collapsed by default).
 *   3. Syllabus import (the original entry point — still available).
 *
 * Editing flows through two modal editors (`CourseEditor`,
 * `AssignmentEditor`) so the screen stays focused on listing.
 */
export function SchoolworkScreen() {
  const [parseResult, setParseResult] = useState<SyllabusParseResult | null>(null);
  const [editingCourse, setEditingCourse] = useState<Course | null>(null);
  const [showNewCourse, setShowNewCourse] = useState(false);
  const [editingAssignment, setEditingAssignment] = useState<Assignment | null>(null);
  const [newAssignmentFor, setNewAssignmentFor] = useState<string | null>(null);
  const [showArchived, setShowArchived] = useState(false);

  const courses = useCourseStore((s) => s.courses);
  const assignments = useAssignmentStore((s) => s.assignments);

  const { activeCourses, archivedCourses } = useMemo(() => {
    const active: Course[] = [];
    const archived: Course[] = [];
    for (const c of courses) {
      (c.active ? active : archived).push(c);
    }
    return { activeCourses: active, archivedCourses: archived };
  }, [courses]);

  // Group assignments under their parent course id once so each row
  // doesn't re-filter on render.
  const assignmentsByCourse = useMemo(() => {
    const map = new Map<string, Assignment[]>();
    for (const a of assignments) {
      const list = map.get(a.courseId);
      if (list) list.push(a);
      else map.set(a.courseId, [a]);
    }
    // Sort each bucket: incomplete first (by due date asc), then
    // completed (by completedAt desc). Mirrors the Android list order.
    const dueAsc = (a: Assignment, b: Assignment) =>
      (a.dueDate ?? Number.MAX_SAFE_INTEGER) -
      (b.dueDate ?? Number.MAX_SAFE_INTEGER);
    for (const list of map.values()) {
      list.sort((a, b) => {
        if (a.completed !== b.completed) return a.completed ? 1 : -1;
        if (!a.completed) return dueAsc(a, b);
        return (b.completedAt ?? 0) - (a.completedAt ?? 0);
      });
    }
    return map;
  }, [assignments]);

  const handleAddAssignment = (courseId: string) => setNewAssignmentFor(courseId);

  return (
    <div className="mx-auto max-w-2xl p-4">
      {/* Header */}
      <div className="mb-6">
        {parseResult ? (
          <button
            onClick={() => setParseResult(null)}
            className="mb-2 inline-flex items-center gap-1 text-sm text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
          >
            <ArrowLeft className="h-4 w-4" />
            Back
          </button>
        ) : null}
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          Schoolwork
        </h1>
        {!parseResult && (
          <p className="text-sm text-[var(--color-text-secondary)]">
            Manage your courses and assignments, or import a syllabus to
            auto-create tasks.
          </p>
        )}
      </div>

      {parseResult ? (
        <SyllabusReviewPanel result={parseResult} onDone={() => setParseResult(null)} />
      ) : (
        <div className="flex flex-col gap-6">
          {/* Active Courses */}
          <section>
            <header className="mb-3 flex items-center gap-2">
              <h2 className="text-base font-semibold text-[var(--color-text-primary)]">
                Courses
              </h2>
              <button
                type="button"
                onClick={() => setShowNewCourse(true)}
                className="ml-auto inline-flex items-center gap-1 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] px-2.5 py-1 text-xs font-medium text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)]"
              >
                <Plus className="h-3.5 w-3.5" />
                Course
              </button>
            </header>
            {activeCourses.length === 0 ? (
              <EmptyCourseList onAdd={() => setShowNewCourse(true)} />
            ) : (
              <ul className="flex flex-col gap-2">
                {activeCourses.map((course) => (
                  <CourseRow
                    key={course.id}
                    course={course}
                    assignments={assignmentsByCourse.get(course.id) ?? []}
                    onEditCourse={() => setEditingCourse(course)}
                    onAddAssignment={() => handleAddAssignment(course.id)}
                    onEditAssignment={setEditingAssignment}
                  />
                ))}
              </ul>
            )}
          </section>

          {/* Archived Courses */}
          {archivedCourses.length > 0 && (
            <section>
              <button
                type="button"
                onClick={() => setShowArchived((v) => !v)}
                className="flex w-full items-center gap-2 rounded-md px-1 py-1 text-sm text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
              >
                {showArchived ? (
                  <ChevronDown className="h-4 w-4" />
                ) : (
                  <ChevronRight className="h-4 w-4" />
                )}
                <span>
                  Archived courses ({archivedCourses.length})
                </span>
              </button>
              {showArchived && (
                <ul className="mt-2 flex flex-col gap-2">
                  {archivedCourses.map((course) => (
                    <CourseRow
                      key={course.id}
                      course={course}
                      assignments={assignmentsByCourse.get(course.id) ?? []}
                      onEditCourse={() => setEditingCourse(course)}
                      onAddAssignment={() => handleAddAssignment(course.id)}
                      onEditAssignment={setEditingAssignment}
                    />
                  ))}
                </ul>
              )}
            </section>
          )}

          {/* Syllabus Import (original) */}
          <section>
            <h2 className="mb-3 text-base font-semibold text-[var(--color-text-primary)]">
              Import from Syllabus
            </h2>
            <SyllabusImport onParsed={setParseResult} />
          </section>
        </div>
      )}

      {/* Editors */}
      {showNewCourse && (
        <CourseEditor course={null} onClose={() => setShowNewCourse(false)} />
      )}
      {editingCourse && (
        <CourseEditor
          course={editingCourse}
          onClose={() => setEditingCourse(null)}
        />
      )}
      {newAssignmentFor && (
        <AssignmentEditor
          assignment={null}
          defaultCourseId={newAssignmentFor}
          onClose={() => setNewAssignmentFor(null)}
        />
      )}
      {editingAssignment && (
        <AssignmentEditor
          assignment={editingAssignment}
          onClose={() => setEditingAssignment(null)}
        />
      )}
    </div>
  );
}

interface CourseRowProps {
  course: Course;
  assignments: Assignment[];
  onEditCourse: () => void;
  onAddAssignment: () => void;
  onEditAssignment: (assignment: Assignment) => void;
}

function CourseRow({
  course,
  assignments,
  onEditCourse,
  onAddAssignment,
  onEditAssignment,
}: CourseRowProps) {
  const [expanded, setExpanded] = useState(false);
  const activeCount = assignments.filter((a) => !a.completed).length;
  const accent = argbToCss(course.color);

  return (
    <li className="overflow-hidden rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)]">
      <div className="flex items-center gap-2 px-3 py-2">
        <button
          type="button"
          onClick={() => setExpanded((v) => !v)}
          className="flex flex-1 items-center gap-2 text-left"
          aria-expanded={expanded}
        >
          {expanded ? (
            <ChevronDown className="h-4 w-4 text-[var(--color-text-secondary)]" />
          ) : (
            <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" />
          )}
          <span
            className="block h-3 w-3 flex-shrink-0 rounded-full"
            style={{ backgroundColor: accent }}
            aria-hidden="true"
          />
          <span className="text-base" aria-hidden="true">
            {course.icon}
          </span>
          <span className="font-semibold text-[var(--color-text-primary)]">
            {course.name}
          </span>
          {course.code && (
            <span className="text-xs text-[var(--color-text-secondary)]">
              {course.code}
            </span>
          )}
          {!course.active && (
            <span className="rounded-full bg-[var(--color-bg-secondary)] px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-[var(--color-text-secondary)]">
              Archived
            </span>
          )}
          <span className="ml-auto text-xs text-[var(--color-text-secondary)]">
            {activeCount} active · {assignments.length} total
          </span>
        </button>
        <button
          type="button"
          onClick={onEditCourse}
          className="rounded-md p-1.5 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
          aria-label={`Edit ${course.name}`}
        >
          <Pencil className="h-4 w-4" />
        </button>
      </div>
      {expanded && (
        <div className="border-t border-[var(--color-border)] bg-[var(--color-bg-primary)] px-3 py-2">
          {assignments.length === 0 ? (
            <p className="py-2 text-xs text-[var(--color-text-secondary)]">
              No assignments yet.
            </p>
          ) : (
            <ul className="flex flex-col">
              {assignments.map((a) => (
                <AssignmentRow
                  key={a.id}
                  assignment={a}
                  accent={accent}
                  onEdit={() => onEditAssignment(a)}
                />
              ))}
            </ul>
          )}
          <button
            type="button"
            onClick={onAddAssignment}
            className="mt-2 inline-flex items-center gap-1 rounded-md border border-dashed border-[var(--color-border)] px-2.5 py-1 text-xs font-medium text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
          >
            <Plus className="h-3.5 w-3.5" />
            Assignment
          </button>
        </div>
      )}
    </li>
  );
}

interface AssignmentRowProps {
  assignment: Assignment;
  accent: string;
  onEdit: () => void;
}

function formatDueDate(millis: number | null): string {
  if (millis == null) return 'No due date';
  const d = new Date(millis);
  // Use the user's locale; falls back to en-US-ish format.
  return d.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
}

function AssignmentRow({ assignment, accent, onEdit }: AssignmentRowProps) {
  return (
    <li className="flex items-center gap-2 py-1.5">
      <span
        className="block h-1.5 w-1.5 flex-shrink-0 rounded-full"
        style={{ backgroundColor: accent }}
        aria-hidden="true"
      />
      <button
        type="button"
        onClick={onEdit}
        className="flex flex-1 items-center gap-2 text-left text-sm hover:underline"
      >
        <span
          className={
            assignment.completed
              ? 'text-[var(--color-text-secondary)] line-through'
              : 'text-[var(--color-text-primary)]'
          }
        >
          {assignment.title}
        </span>
        <span className="ml-auto text-xs text-[var(--color-text-secondary)]">
          {formatDueDate(assignment.dueDate)}
        </span>
      </button>
    </li>
  );
}

interface EmptyCourseListProps {
  onAdd: () => void;
}

function EmptyCourseList({ onAdd }: EmptyCourseListProps) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-8 text-center">
      <GraduationCap className="h-8 w-8 text-[var(--color-text-secondary)]" />
      <div className="text-sm font-medium text-[var(--color-text-primary)]">
        No courses yet
      </div>
      <p className="text-xs text-[var(--color-text-secondary)]">
        Add a course to start tracking assignments and daily study time.
      </p>
      <button
        type="button"
        onClick={onAdd}
        className="inline-flex items-center gap-1 rounded-md bg-[var(--color-accent)] px-3 py-1.5 text-xs font-medium text-white hover:opacity-90"
      >
        <Plus className="h-3.5 w-3.5" />
        Add Course
      </button>
    </div>
  );
}
