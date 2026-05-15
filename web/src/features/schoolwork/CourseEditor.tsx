import { useState } from 'react';
import { toast } from 'sonner';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useCourseStore } from '@/stores/courseStore';
import { COURSE_COLOR_OPTIONS } from './courseColor';
import type { Course } from '@/types/schoolwork';

/**
 * Web course editor — parity with Android's `CourseEditScreen.kt`.
 * Surfaces the same fields the Android editor edits: name, code,
 * icon, color, active flag, and the `createDailyTask` toggle.
 *
 * Schedule slots: Android's `CourseEntity` does not store schedule
 * slots itself; recurring time-of-day reminders are handled by the
 * companion `TaskTemplate` / recurrence engine. The parity follow-up
 * asks for "days of week + time blocks" but the canonical data shape
 * doesn't carry them yet, so this editor sticks to the shipped column
 * surface to avoid diverging from Android. (Schedule blocks can be
 * added in a follow-up once Android exposes them.)
 *
 * The dialog is dual-purpose: passing a `course` prop opens it in
 * edit mode (with delete and archive/unarchive actions); passing
 * `null` opens it in create mode.
 */

const EMOJI_OPTIONS = [
  '📚', '📖', '📝', '✏️', '🧮', '🔬',
  '🧪', '🧬', '🌍', '🗺️', '🎨', '🎭',
  '🎵', '🎻', '🏛️', '⚖️', '💻', '🤖',
  '📊', '📈', '🧠', '💡', '🌱', '🔭',
];

interface CourseEditorProps {
  course: Course | null;
  onClose: () => void;
}

export function CourseEditor({ course, onClose }: CourseEditorProps) {
  const { createCourse, updateCourse, archiveCourse, unarchiveCourse, deleteCourse } =
    useCourseStore();
  const isEditing = course !== null;

  const [name, setName] = useState(course?.name ?? '');
  const [code, setCode] = useState(course?.code ?? '');
  const [icon, setIcon] = useState(course?.icon || '📚');
  const [color, setColor] = useState(course?.color ?? COURSE_COLOR_OPTIONS[0].argb);
  const [createDailyTask, setCreateDailyTask] = useState(
    course?.createDailyTask ?? false,
  );
  const [saving, setSaving] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const handleSubmit = async () => {
    const trimmed = name.trim();
    if (!trimmed) {
      toast.error('Course name is required');
      return;
    }
    setSaving(true);
    try {
      if (isEditing) {
        await updateCourse(course.id, {
          name: trimmed,
          code: code.trim(),
          icon,
          color,
          createDailyTask,
        });
        toast.success('Course updated');
      } else {
        await createCourse({
          name: trimmed,
          code: code.trim(),
          icon,
          color,
          active: true,
          createDailyTask,
        });
        toast.success('Course created');
      }
      onClose();
    } catch {
      toast.error(isEditing ? 'Failed to update course' : 'Failed to create course');
    } finally {
      setSaving(false);
    }
  };

  const handleToggleArchive = async () => {
    if (!course) return;
    setSaving(true);
    try {
      if (course.active) {
        await archiveCourse(course.id);
        toast.success('Course archived');
      } else {
        await unarchiveCourse(course.id);
        toast.success('Course unarchived');
      }
      onClose();
    } catch {
      toast.error('Failed to update course');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!course) return;
    setSaving(true);
    try {
      await deleteCourse(course.id);
      toast.success('Course deleted');
      onClose();
    } catch {
      toast.error('Failed to delete course');
    } finally {
      setSaving(false);
      setConfirmingDelete(false);
    }
  };

  return (
    <>
      <Modal
        isOpen
        onClose={onClose}
        title={isEditing ? 'Edit Course' : 'New Course'}
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
                  onClick={handleToggleArchive}
                  disabled={saving}
                >
                  {course.active ? 'Archive' : 'Unarchive'}
                </Button>
              )}
              <Button variant="ghost" onClick={onClose} disabled={saving}>
                Cancel
              </Button>
              <Button onClick={handleSubmit} loading={saving}>
                {isEditing ? 'Save Changes' : 'Create Course'}
              </Button>
            </div>
          </div>
        }
      >
        <div className="flex flex-col gap-5">
          <Input
            label="Name"
            placeholder="e.g., Intro to Computer Science"
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoFocus
          />

          <Input
            label="Code"
            placeholder="e.g., CS-101"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            helperText="Optional. Shown next to the course name."
          />

          {/* Icon Picker */}
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-[var(--color-text-primary)]">
              Icon
            </label>
            <div className="flex flex-wrap gap-2">
              {EMOJI_OPTIONS.map((emoji) => (
                <button
                  key={emoji}
                  type="button"
                  onClick={() => setIcon(emoji)}
                  className={`flex h-9 w-9 items-center justify-center rounded-lg text-lg transition-all ${
                    icon === emoji
                      ? 'bg-[var(--color-accent)]/15 ring-2 ring-[var(--color-accent)]'
                      : 'bg-[var(--color-bg-secondary)] hover:bg-[var(--color-bg-primary)]'
                  }`}
                  aria-label={`Icon ${emoji}`}
                  aria-pressed={icon === emoji}
                >
                  {emoji}
                </button>
              ))}
            </div>
          </div>

          {/* Color Picker */}
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-[var(--color-text-primary)]">
              Color
            </label>
            <div className="flex flex-wrap gap-2">
              {COURSE_COLOR_OPTIONS.map((c) => (
                <button
                  key={c.argb}
                  type="button"
                  onClick={() => setColor(c.argb)}
                  className={`h-8 w-8 rounded-full transition-all ${
                    color === c.argb
                      ? 'ring-2 ring-offset-2 ring-offset-[var(--color-bg-card)]'
                      : 'hover:scale-110'
                  }`}
                  style={{
                    backgroundColor: c.hex,
                    ...(color === c.argb
                      ? ({ '--tw-ring-color': c.hex } as React.CSSProperties)
                      : {}),
                  }}
                  aria-label={`Color ${c.hex}`}
                  aria-pressed={color === c.argb}
                />
              ))}
            </div>
          </div>

          {/* Daily task toggle */}
          <label className="flex cursor-pointer items-start gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2.5">
            <input
              type="checkbox"
              checked={createDailyTask}
              onChange={(e) => setCreateDailyTask(e.target.checked)}
              className="mt-0.5 h-4 w-4 cursor-pointer accent-[var(--color-accent)]"
            />
            <div className="flex flex-col">
              <span className="text-sm font-medium text-[var(--color-text-primary)]">
                Spawn a daily task
              </span>
              <span className="text-xs text-[var(--color-text-secondary)]">
                Generates a "Work on {name || 'this course'}" task each day so it
                shows up in your Today list automatically.
              </span>
            </div>
          </label>
        </div>
      </Modal>

      {confirmingDelete && course && (
        <ConfirmDialog
          isOpen={confirmingDelete}
          onClose={() => setConfirmingDelete(false)}
          onConfirm={handleDelete}
          title="Delete this course?"
          message={
            <>
              <strong>{course.name}</strong> and all its assignments will be
              permanently deleted. This can't be undone.
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

export default CourseEditor;
