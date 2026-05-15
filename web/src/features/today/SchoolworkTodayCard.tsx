import { useMemo } from 'react';
import { CheckCircle2, Circle, GraduationCap } from 'lucide-react';
import { useCourseStore } from '@/stores/courseStore';
import { useAssignmentStore } from '@/stores/assignmentStore';
import { argbToCss } from '@/features/schoolwork/courseColor';
import type { Assignment, Course } from '@/types/schoolwork';

/**
 * Web port of `SchoolworkCard.kt` (post PR #1314). Surfaces each active
 * course as a checkable habit-style row on Today, with that course's
 * assignments due today grouped underneath. Toggling a course row writes
 * a `CourseCompletion` for the logical-day midnight via
 * `useCourseStore.toggleCompletion` — same shape Android persists.
 *
 * Assignments come from `useAssignmentStore`, which mirrors the
 * `users/{uid}/assignments` Firestore collection (Android is the only
 * write path; web is read-only). The listener mounts via
 * `courseStore.subscribe()` so it shares the schoolwork lifecycle.
 *
 * Orphan-assignment handling matches `SchoolworkCard.kt`: assignments
 * whose `courseId` doesn't match any active course (course archived,
 * deleted, or not yet synced) render flat at the bottom so they're
 * still actionable instead of being silently hidden.
 */
export function SchoolworkTodayCard() {
  const courses = useCourseStore((s) => s.courses);
  const completions = useCourseStore((s) => s.completions);
  const toggleCompletion = useCourseStore((s) => s.toggleCompletion);
  const assignments = useAssignmentStore((s) => s.assignments);

  const todayMidnight = useMemo(() => {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    return d.getTime();
  }, []);
  const tomorrowMidnight = useMemo(
    () => todayMidnight + 24 * 60 * 60 * 1000,
    [todayMidnight],
  );

  const activeCourses = useMemo(
    () => courses.filter((c) => c.active),
    [courses],
  );

  const courseCompletionMap = useMemo(() => {
    const map = new Map<string, boolean>();
    for (const c of completions) {
      if (c.date === todayMidnight) {
        map.set(c.courseCloudId, c.completed);
      }
    }
    return map;
  }, [completions, todayMidnight]);

  // Assignments due in [today00:00, tomorrow00:00). Skip completed ones
  // — Android only rolls them into the card while still active.
  const dueTodayAssignments = useMemo(
    () =>
      assignments.filter((a) => {
        if (a.completed) return false;
        if (a.dueDate == null) return false;
        return a.dueDate >= todayMidnight && a.dueDate < tomorrowMidnight;
      }),
    [assignments, todayMidnight, tomorrowMidnight],
  );

  const assignmentsByCourse = useMemo(() => {
    const map = new Map<string, Assignment[]>();
    for (const a of dueTodayAssignments) {
      const list = map.get(a.courseId);
      if (list) list.push(a);
      else map.set(a.courseId, [a]);
    }
    return map;
  }, [dueTodayAssignments]);

  // Mirrors `SchoolworkCard.kt`'s `coveredCourseIds` filter — anything
  // whose course isn't in the active list goes into the orphan section
  // at the bottom.
  const activeCourseIdSet = useMemo(
    () => new Set(activeCourses.map((c) => c.id)),
    [activeCourses],
  );
  const orphanAssignments = useMemo(
    () => dueTodayAssignments.filter((a) => !activeCourseIdSet.has(a.courseId)),
    [dueTodayAssignments, activeCourseIdSet],
  );

  if (activeCourses.length === 0 && dueTodayAssignments.length === 0)
    return null;

  const doneCount = activeCourses.filter(
    (c) => courseCompletionMap.get(c.id) === true,
  ).length;
  const summaryParts: string[] = [];
  if (activeCourses.length > 0) {
    summaryParts.push(`${doneCount} of ${activeCourses.length} done`);
  }
  if (dueTodayAssignments.length > 0) {
    summaryParts.push(`${dueTodayAssignments.length} due today`);
  }

  return (
    <section
      className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3"
      aria-label="Schoolwork"
    >
      <header className="mb-2 flex items-center gap-2">
        <GraduationCap className="h-4 w-4 text-[var(--color-accent)]" />
        <span className="text-sm font-semibold text-[var(--color-text-primary)]">
          Schoolwork
        </span>
        {summaryParts.length > 0 && (
          <span className="ml-auto text-xs text-[var(--color-text-secondary)]">
            {summaryParts.join(' · ')}
          </span>
        )}
      </header>
      <ul className="space-y-1">
        {activeCourses.map((course) => (
          <CourseRow
            key={course.id}
            course={course}
            completed={courseCompletionMap.get(course.id) ?? false}
            assignments={assignmentsByCourse.get(course.id) ?? []}
            onToggle={() => toggleCompletion(course.id, todayMidnight)}
          />
        ))}
        {orphanAssignments.length > 0 && (
          <li>
            <ul className="ml-6 space-y-0.5">
              {orphanAssignments.map((a) => (
                <AssignmentItem key={a.id} assignment={a} colorArgb={0} />
              ))}
            </ul>
          </li>
        )}
      </ul>
    </section>
  );
}

interface CourseRowProps {
  course: Course;
  completed: boolean;
  assignments: Assignment[];
  onToggle: () => void;
}

function CourseRow({ course, completed, assignments, onToggle }: CourseRowProps) {
  return (
    <li>
      <button
        type="button"
        onClick={onToggle}
        className="flex w-full items-center gap-2 rounded px-1 py-1.5 text-left text-sm hover:bg-[var(--color-bg-tertiary)]"
        aria-pressed={completed}
        aria-label={`Toggle ${course.name} done today`}
      >
        {completed ? (
          <CheckCircle2 className="h-4 w-4 text-[var(--color-accent)]" />
        ) : (
          <Circle className="h-4 w-4 text-[var(--color-text-secondary)]" />
        )}
        <span
          className={
            completed
              ? 'font-semibold text-[var(--color-text-secondary)] line-through'
              : 'font-semibold text-[var(--color-text-primary)]'
          }
        >
          {course.name}
        </span>
        {course.code && (
          <span className="text-xs text-[var(--color-text-secondary)]">
            {course.code}
          </span>
        )}
      </button>
      {assignments.length > 0 && (
        <ul className="ml-6 space-y-0.5">
          {assignments.slice(0, 6).map((a) => (
            <AssignmentItem
              key={a.id}
              assignment={a}
              colorArgb={course.color}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

interface AssignmentItemProps {
  assignment: Assignment;
  colorArgb: number;
}

function AssignmentItem({ assignment, colorArgb }: AssignmentItemProps) {
  return (
    <li className="flex items-center gap-2 py-0.5 text-xs text-[var(--color-text-secondary)]">
      <span
        className="block h-1.5 w-1.5 rounded-full"
        style={{ backgroundColor: argbToCss(colorArgb) }}
        aria-hidden="true"
      />
      <span
        className={
          assignment.completed
            ? 'text-[var(--color-text-secondary)] line-through'
            : 'text-[var(--color-text-primary)]'
        }
      >
        {assignment.title}
      </span>
    </li>
  );
}

export default SchoolworkTodayCard;
