import { useMemo } from 'react';
import { CheckCircle2, Circle, GraduationCap } from 'lucide-react';
import { useTaskStore } from '@/stores/taskStore';
import { useCourseStore } from '@/stores/courseStore';
import type { Course } from '@/types/schoolwork';
import type { Task } from '@/types/task';

/**
 * Web port of `SchoolworkCard.kt` (post PR #1314). Surfaces each active
 * course as a checkable habit-style row on Today, with any tasks tagged
 * to the course shown grouped underneath. Toggling a course row writes
 * a `CourseCompletion` for the logical-day midnight via
 * `useCourseStore.toggleCompletion` — same shape Android persists.
 *
 * Web-vs-Android caveat: Android pulls "assignments due today" from a
 * dedicated `AssignmentEntity` table (Firestore `users/{uid}/assignments`).
 * That collection isn't yet wired into web's store layer, so this card
 * falls back to **course-keyword-matched Tasks** for the assignments-under
 * -a-class grouping. The keyword fallback uses the course `name` or
 * `code` as a substring filter against task titles + tags. Wiring the
 * dedicated `assignments` Firestore listener is a deliberate follow-up —
 * the parent audit Section F.2 calls this out.
 */
export function SchoolworkTodayCard() {
  const courses = useCourseStore((s) => s.courses);
  const completions = useCourseStore((s) => s.completions);
  const toggleCompletion = useCourseStore((s) => s.toggleCompletion);
  const todayTasks = useTaskStore((s) => s.todayTasks);

  const todayMidnight = useMemo(() => {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    return d.getTime();
  }, []);

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

  // Group today's tasks under the course whose name/code appears in the
  // task title or tag list — best-effort substring match until we wire
  // the dedicated `assignments` listener (audit follow-up).
  const tasksByCourse = useMemo(() => {
    const map = new Map<string, Task[]>();
    for (const course of activeCourses) {
      const needles = [course.name, course.code].filter(Boolean).map((n) => n.toLowerCase());
      const matches = todayTasks.filter((t) => {
        if (!needles.length) return false;
        const haystack =
          `${t.title ?? ''} ${(t.tags ?? []).map((tag) => tag.name ?? '').join(' ')}`.toLowerCase();
        return needles.some((n) => haystack.includes(n));
      });
      if (matches.length > 0) map.set(course.id, matches);
    }
    return map;
  }, [activeCourses, todayTasks]);

  if (activeCourses.length === 0) return null;

  const doneCount = activeCourses.filter((c) => courseCompletionMap.get(c.id) === true).length;

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
        <span className="ml-auto text-xs text-[var(--color-text-secondary)]">
          {doneCount} of {activeCourses.length} done
        </span>
      </header>
      <ul className="space-y-1">
        {activeCourses.map((course) => (
          <CourseRow
            key={course.id}
            course={course}
            completed={courseCompletionMap.get(course.id) ?? false}
            tasks={tasksByCourse.get(course.id) ?? []}
            onToggle={() => toggleCompletion(course.id, todayMidnight)}
          />
        ))}
      </ul>
    </section>
  );
}

interface CourseRowProps {
  course: Course;
  completed: boolean;
  tasks: Task[];
  onToggle: () => void;
}

function CourseRow({ course, completed, tasks, onToggle }: CourseRowProps) {
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
      {tasks.length > 0 && (
        <ul className="ml-6 space-y-0.5">
          {tasks.slice(0, 6).map((t) => (
            <li
              key={t.id}
              className="flex items-center gap-2 py-0.5 text-xs text-[var(--color-text-secondary)]"
            >
              <span
                className="block h-1.5 w-1.5 rounded-full"
                style={{ backgroundColor: course.color !== 0 ? '#' + (course.color >>> 0).toString(16).padStart(8, '0').slice(2) : '#888' }}
                aria-hidden="true"
              />
              <span
                className={
                  t.status === 'done'
                    ? 'text-[var(--color-text-secondary)] line-through'
                    : 'text-[var(--color-text-primary)]'
                }
              >
                {t.title}
              </span>
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

export default SchoolworkTodayCard;
