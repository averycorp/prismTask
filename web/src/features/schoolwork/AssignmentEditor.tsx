import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useAssignmentStore } from '@/stores/assignmentStore';
import { useCourseStore } from '@/stores/courseStore';
import type { Assignment } from '@/types/schoolwork';

/**
 * Web assignment editor — parity with Android's
 * `AssignmentEditScreen.kt`. Surfaces the same fields the Android
 * editor edits: title, due date, completed flag, notes, and the
 * parent course.
 *
 * Field parity note: the scope brief asks for "estimated minutes" and
 * "priority" too, but `AssignmentEntity.kt` doesn't carry those
 * columns — adding them on web would create a drift that Android
 * can't render. Sticking to the shipped column surface keeps
 * cross-device round-trips clean. (Priority + estimate can be
 * threaded in once Android adds the columns.)
 *
 * The dialog is dual-purpose: passing an `assignment` opens it in
 * edit mode (with delete + toggle-completion); passing `null` opens
 * it in create mode. `defaultCourseId` pre-selects a course in create
 * mode (used by the "+ Assignment" affordance inside a per-course
 * row).
 */

interface AssignmentEditorProps {
  assignment: Assignment | null;
  defaultCourseId?: string | null;
  onClose: () => void;
}

function formatDateInputValue(millis: number | null): string {
  if (millis == null) return '';
  const d = new Date(millis);
  // <input type="date"> wants yyyy-MM-dd in *local* timezone.
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

function parseDateInputValue(value: string): number | null {
  if (!value) return null;
  // Locale-safe: build the date in local time at midnight so the
  // "due today" window in SchoolworkTodayCard (which uses local
  // midnight) lines up with this value.
  const [yyyy, mm, dd] = value.split('-').map((s) => parseInt(s, 10));
  if (!yyyy || !mm || !dd) return null;
  const d = new Date(yyyy, mm - 1, dd, 0, 0, 0, 0);
  return d.getTime();
}

export function AssignmentEditor({
  assignment,
  defaultCourseId,
  onClose,
}: AssignmentEditorProps) {
  const { createAssignment, updateAssignment, deleteAssignment, toggleComplete } =
    useAssignmentStore();
  const courses = useCourseStore((s) => s.courses);
  const isEditing = assignment !== null;

  const [title, setTitle] = useState(assignment?.title ?? '');
  const [courseId, setCourseId] = useState<string>(() => {
    if (assignment) return assignment.courseId;
    if (defaultCourseId) return defaultCourseId;
    // Default to the first active course so creation never lands on an
    // archived parent.
    const active = courses.find((c) => c.active);
    return active?.id ?? courses[0]?.id ?? '';
  });
  const [dueDateValue, setDueDateValue] = useState<string>(
    formatDateInputValue(assignment?.dueDate ?? null),
  );
  const [completed, setCompleted] = useState(assignment?.completed ?? false);
  const [notes, setNotes] = useState(assignment?.notes ?? '');
  const [saving, setSaving] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  // Sort courses for the picker the same way the SchoolworkTodayCard
  // and assignment list render them — active first, then archived.
  const sortedCourses = useMemo(() => {
    const active = courses.filter((c) => c.active);
    const archived = courses.filter((c) => !c.active);
    return [...active, ...archived];
  }, [courses]);

  const handleSubmit = async () => {
    const trimmed = title.trim();
    if (!trimmed) {
      toast.error('Title is required');
      return;
    }
    if (!courseId) {
      toast.error('Pick a course first — create one if you don\'t have any.');
      return;
    }
    setSaving(true);
    try {
      const dueDate = parseDateInputValue(dueDateValue);
      // Preserve the prior `completedAt` if we're saving an already-
      // completed assignment, otherwise stamp a fresh "now". When the
      // user is flipping it back to incomplete, null it out.
      const completedAt = completed
        ? (assignment?.completed && assignment.completedAt) || Date.now()
        : null;
      if (isEditing) {
        await updateAssignment(assignment.id, {
          title: trimmed,
          courseId,
          dueDate,
          completed,
          completedAt,
          notes: notes.trim() || null,
        });
        toast.success('Assignment updated');
      } else {
        await createAssignment({
          title: trimmed,
          courseId,
          dueDate,
          completed,
          completedAt,
          notes: notes.trim() || null,
        });
        toast.success('Assignment created');
      }
      onClose();
    } catch {
      toast.error(
        isEditing
          ? 'Failed to update assignment'
          : 'Failed to create assignment',
      );
    } finally {
      setSaving(false);
    }
  };

  const handleToggleComplete = async () => {
    if (!assignment) return;
    setSaving(true);
    try {
      await toggleComplete(assignment.id);
      toast.success(
        assignment.completed ? 'Marked incomplete' : 'Marked complete',
      );
      onClose();
    } catch {
      toast.error('Failed to update assignment');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!assignment) return;
    setSaving(true);
    try {
      await deleteAssignment(assignment.id);
      toast.success('Assignment deleted');
      onClose();
    } catch {
      toast.error('Failed to delete assignment');
    } finally {
      setSaving(false);
      setConfirmingDelete(false);
    }
  };

  const noCourses = courses.length === 0;

  return (
    <>
      <Modal
        isOpen
        onClose={onClose}
        title={isEditing ? 'Edit Assignment' : 'New Assignment'}
        size="md"
        footer={
          <div className="flex w-full items-center gap-3">
            {isEditing && (
              <Button
                variant="ghost"
                onClick={() => setConfirmingDelete(true)}
                disabled={saving}
                className="text-red-500 hover:text-red-600"
              >
                Delete
              </Button>
            )}
            <div className="ml-auto flex gap-3">
              {isEditing && (
                <Button
                  variant="secondary"
                  onClick={handleToggleComplete}
                  disabled={saving}
                >
                  {assignment.completed ? 'Mark Incomplete' : 'Mark Complete'}
                </Button>
              )}
              <Button variant="ghost" onClick={onClose} disabled={saving}>
                Cancel
              </Button>
              <Button
                onClick={handleSubmit}
                loading={saving}
                disabled={noCourses}
              >
                {isEditing ? 'Save Changes' : 'Create Assignment'}
              </Button>
            </div>
          </div>
        }
      >
        <div className="flex flex-col gap-5">
          {noCourses && (
            <p className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3 text-xs text-[var(--color-text-secondary)]">
              You don't have any courses yet. Create a course first, then add
              assignments under it.
            </p>
          )}

          <Input
            label="Title"
            placeholder="e.g., Problem Set 3"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            autoFocus
          />

          {/* Course Picker */}
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="assignment-course"
              className="text-sm font-medium text-[var(--color-text-primary)]"
            >
              Course
            </label>
            <select
              id="assignment-course"
              value={courseId}
              onChange={(e) => setCourseId(e.target.value)}
              disabled={noCourses}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none transition-colors focus:border-[var(--color-accent)] focus:ring-1 focus:ring-[var(--color-accent)] disabled:opacity-50"
            >
              {sortedCourses.length === 0 && (
                <option value="">(no courses)</option>
              )}
              {sortedCourses.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.icon} {c.name}
                  {c.code ? ` (${c.code})` : ''}
                  {!c.active ? ' — archived' : ''}
                </option>
              ))}
            </select>
          </div>

          {/* Due Date */}
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="assignment-due-date"
              className="text-sm font-medium text-[var(--color-text-primary)]"
            >
              Due Date
            </label>
            <input
              id="assignment-due-date"
              type="date"
              value={dueDateValue}
              onChange={(e) => setDueDateValue(e.target.value)}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none transition-colors focus:border-[var(--color-accent)] focus:ring-1 focus:ring-[var(--color-accent)]"
            />
            <p className="text-xs text-[var(--color-text-secondary)]">
              Leave blank for assignments without a hard due date.
            </p>
          </div>

          {/* Notes */}
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="assignment-notes"
              className="text-sm font-medium text-[var(--color-text-primary)]"
            >
              Notes
            </label>
            <textarea
              id="assignment-notes"
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] placeholder-[var(--color-text-secondary)] outline-none transition-colors focus:border-[var(--color-accent)] focus:ring-1 focus:ring-[var(--color-accent)]"
              placeholder="Optional details..."
              rows={3}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
            />
          </div>

          {/* Completed toggle */}
          <label className="flex cursor-pointer items-center gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2.5">
            <input
              type="checkbox"
              checked={completed}
              onChange={(e) => setCompleted(e.target.checked)}
              className="h-4 w-4 cursor-pointer accent-[var(--color-accent)]"
            />
            <span className="text-sm font-medium text-[var(--color-text-primary)]">
              Mark as completed
            </span>
          </label>
        </div>
      </Modal>

      {confirmingDelete && assignment && (
        <ConfirmDialog
          isOpen={confirmingDelete}
          onClose={() => setConfirmingDelete(false)}
          onConfirm={handleDelete}
          title="Delete this assignment?"
          message={
            <>
              <strong>{assignment.title}</strong> will be permanently deleted.
              This can't be undone.
            </>
          }
          confirmLabel="Delete"
          variant="danger"
          loading={saving}
        />
      )}
    </>
  );
}

export default AssignmentEditor;
