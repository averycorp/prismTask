import { useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, Plus, Pencil } from 'lucide-react';
import { useCourseStore } from '@/stores/courseStore';
import { useAssignmentStore } from '@/stores/assignmentStore';

/**
 * Settings entry for Schoolwork. Mirrors `LeisureBudgetSection`'s
 * shape: a small summary card (course counts + assignments due today)
 * and a primary CTA that routes to `/schoolwork` where the full
 * course / assignment editor lives. Parity unit 22.
 *
 * The summary numbers re-use whatever the existing subscription
 * pipeline (`useFirestoreSync`) already loaded — this section never
 * triggers its own fetch beyond a single lazy `fetch()` so signed-out
 * / offline users still see the routing affordance.
 */
export function SchoolworkSection() {
  const navigate = useNavigate();
  const fetchCourses = useCourseStore((s) => s.fetch);
  const courses = useCourseStore((s) => s.courses);
  const assignments = useAssignmentStore((s) => s.assignments);

  useEffect(() => {
    if (courses.length === 0) {
      void fetchCourses();
    }
    // Intentionally only run on mount — the real-time listener handles
    // the steady-state updates after that.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const activeCount = useMemo(
    () => courses.filter((c) => c.active).length,
    [courses],
  );
  const archivedCount = courses.length - activeCount;

  const dueTodayCount = useMemo(() => {
    const start = new Date();
    start.setHours(0, 0, 0, 0);
    const startMs = start.getTime();
    const endMs = startMs + 24 * 60 * 60 * 1000;
    return assignments.filter(
      (a) =>
        !a.completed &&
        a.dueDate != null &&
        a.dueDate >= startMs &&
        a.dueDate < endMs,
    ).length;
  }, [assignments]);

  return (
    <div className="space-y-3">
      <p className="text-sm text-[var(--color-text-secondary)]">
        Track your courses, assignments, and per-class daily check-ins. Today
        surfaces each active course as a checkable row with assignments due
        today grouped underneath.
      </p>

      <div className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3 text-sm">
        <div className="flex items-center justify-between">
          <span className="text-[var(--color-text-secondary)]">
            Active Courses
          </span>
          <span className="font-semibold text-[var(--color-text-primary)]">
            {activeCount}
          </span>
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-[var(--color-text-secondary)]">
            Archived Courses
          </span>
          <span className="font-semibold text-[var(--color-text-primary)]">
            {archivedCount}
          </span>
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-[var(--color-text-secondary)]">
            Assignments Due Today
          </span>
          <span className="font-semibold text-[var(--color-text-primary)]">
            {dueTodayCount}
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-2 sm:flex-row">
        <button
          type="button"
          onClick={() => navigate('/schoolwork')}
          className="inline-flex items-center justify-between gap-2 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm font-medium text-[var(--color-text-primary)] hover:bg-[var(--color-bg-tertiary)]"
          data-testid="settings-schoolwork-manage"
        >
          <span className="inline-flex items-center gap-2">
            <Pencil className="h-4 w-4" />
            Manage Courses &amp; Assignments
          </span>
          <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" />
        </button>
        <button
          type="button"
          onClick={() => navigate('/schoolwork')}
          className="inline-flex items-center justify-between gap-2 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm font-medium text-[var(--color-text-primary)] hover:bg-[var(--color-bg-tertiary)]"
        >
          <span className="inline-flex items-center gap-2">
            <Plus className="h-4 w-4" />
            Add Course
          </span>
          <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" />
        </button>
      </div>
    </div>
  );
}

export default SchoolworkSection;
