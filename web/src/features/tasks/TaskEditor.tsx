import { useState, useEffect, useCallback, useRef } from 'react';
import {
  FileText,
  CalendarDays,
  FolderKanban,
  Trash2,
  Copy,
  Loader2,
  Check,
  Plus,
  GripVertical,
  X,
  Save,
  Sparkles,
} from 'lucide-react';
import { toast } from 'sonner';
import { Drawer } from '@/components/ui/Drawer';
import { Tabs } from '@/components/ui/Tabs';
import { Button } from '@/components/ui/Button';
import { Checkbox } from '@/components/ui/Checkbox';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import { useTagStore } from '@/stores/tagStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { aiLifeCategoryClassifyText } from '@/api/ai/chat';
import { DependencyEditor } from '@/features/tasks/DependencyEditor';
import { PRIORITY_CONFIG } from '@/utils/priority';
import { formatRelative } from '@/utils/dates';
import type {
  CognitiveLoad,
  LifeCategory,
  Task,
  TaskMode,
  TaskPriority,
  TaskStatus,
  TaskUpdate,
} from '@/types/task';

interface TaskEditorProps {
  onClose: () => void;
  onUpdate?: () => void;
  mode?: 'edit' | 'create';
  defaultProjectId?: string;
  onSaveAsTemplate?: (prefill: {
    title?: string;
    description?: string;
    priority?: number;
    project_id?: string;
    tags?: string[];
    subtasks?: string[];
    recurrence_json?: string;
    estimated_duration?: number;
  }) => void;
}

const TABS = [
  { key: 'details', label: 'Details', icon: <FileText className="h-4 w-4" /> },
  {
    key: 'schedule',
    label: 'Schedule',
    icon: <CalendarDays className="h-4 w-4" />,
  },
  {
    key: 'organize',
    label: 'Organize',
    icon: <FolderKanban className="h-4 w-4" />,
  },
];

const STATUS_OPTIONS: { value: TaskStatus; label: string }[] = [
  { value: 'todo', label: 'To Do' },
  { value: 'in_progress', label: 'In Progress' },
  { value: 'done', label: 'Done' },
  { value: 'cancelled', label: 'Cancelled' },
];

const REMINDER_OPTIONS = [
  { value: '', label: 'No Reminder' },
  { value: '15', label: '15 Minutes Before' },
  { value: '30', label: '30 Minutes Before' },
  { value: '60', label: '1 Hour Before' },
  { value: '120', label: '2 Hours Before' },
  { value: '1440', label: '1 Day Before' },
];

const RECURRENCE_TYPES = [
  { value: '', label: 'None' },
  { value: 'daily', label: 'Daily' },
  { value: 'weekly', label: 'Weekly' },
  { value: 'biweekly', label: 'Biweekly' },
  { value: 'monthly', label: 'Monthly' },
  { value: 'yearly', label: 'Yearly' },
  { value: 'weekdays', label: 'Weekdays' },
];

const WEEKDAYS: { idx: number; label: string }[] = [
  { idx: 1, label: 'Mon' },
  { idx: 2, label: 'Tue' },
  { idx: 3, label: 'Wed' },
  { idx: 4, label: 'Thu' },
  { idx: 5, label: 'Fri' },
  { idx: 6, label: 'Sat' },
  { idx: 0, label: 'Sun' },
];

const TAG_COLORS = [
  '#ef4444', '#f97316', '#f59e0b', '#eab308',
  '#84cc16', '#22c55e', '#14b8a6', '#06b6d4',
  '#3b82f6', '#6366f1', '#a855f7', '#ec4899',
];

// Mirrors Android's `LifeCategory` enum; values must match `LifeCategory.kt`
// so the Work-Life Balance engine on Android picks them up unchanged.
const LIFE_CATEGORY_OPTIONS: { value: LifeCategory | ''; label: string }[] = [
  { value: '', label: 'Uncategorized' },
  { value: 'WORK', label: 'Work' },
  { value: 'PERSONAL', label: 'Personal' },
  { value: 'SELF_CARE', label: 'Self-Care' },
  { value: 'HEALTH', label: 'Health' },
];

// Mirrors Android's `CognitiveLoad` enum; values must match
// `CognitiveLoad.kt` so the start-friction classifier on Android picks
// them up unchanged. See `docs/COGNITIVE_LOAD.md`.
const COGNITIVE_LOAD_OPTIONS: { value: CognitiveLoad | ''; label: string }[] = [
  { value: '', label: 'Uncategorized' },
  { value: 'EASY', label: 'Easy' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'HARD', label: 'Hard' },
];

// Mirrors Android's `TaskMode` enum; values must match `TaskMode.kt`
// so the Work / Play / Relax classifier on Android picks them up
// unchanged. See `docs/WORK_PLAY_RELAX.md`.
const TASK_MODE_OPTIONS: { value: TaskMode | ''; label: string }[] = [
  { value: '', label: 'Uncategorized' },
  { value: 'WORK', label: 'Work' },
  { value: 'PLAY', label: 'Play' },
  { value: 'RELAX', label: 'Relax' },
];

export default function TaskEditor({
  onClose,
  onUpdate,
  mode = 'edit',
  defaultProjectId,
  onSaveAsTemplate,
}: TaskEditorProps) {
  const {
    selectedTask,
    updateTask,
    deleteTask,
    createTask,
    createSubtask,
    fetchTask,
  } = useTaskStore();
  const { projects, fetchAllProjects } = useProjectStore();
  const { tags, fetchTags, createTag } = useTagStore();

  const [activeTab, setActiveTab] = useState('details');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [duplicateOpen, setDuplicateOpen] = useState(false);
  const [duplicateSubtasks, setDuplicateSubtasks] = useState(true);
  // Claude-backed Life Category auto-classify (parity Batch 3 D.1c —
  // mirrors Android `AddEditTaskViewModel.tryUpgradeLifeCategoryWithClaude`).
  const [lifeCategoryAutoBusy, setLifeCategoryAutoBusy] = useState(false);
  const aiFeaturesEnabled = useSettingsStore((s) => s.aiFeaturesEnabled);

  // Form state
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [priority, setPriority] = useState<TaskPriority>(3);
  const [status, setStatus] = useState<TaskStatus>('todo');
  const [dueDate, setDueDate] = useState('');
  const [dueTime, setDueTime] = useState('');
  const [projectId, setProjectId] = useState<string | null>(null);
  const [notes, setNotes] = useState('');
  const [duration, setDuration] = useState('');
  const [recurrenceType, setRecurrenceType] = useState('');
  const [recurrenceInterval, setRecurrenceInterval] = useState(1);
  const [recurrenceDaysOfWeek, setRecurrenceDaysOfWeek] = useState<number[]>([]);
  const [recurrenceAfterCompletion, setRecurrenceAfterCompletion] = useState(false);
  const [recurrenceEndMode, setRecurrenceEndMode] = useState<
    'never' | 'after' | 'on'
  >('never');
  const [recurrenceEndAfter, setRecurrenceEndAfter] = useState(10);
  const [recurrenceEndDate, setRecurrenceEndDate] = useState('');
  const [reminderOffset, setReminderOffset] = useState('');
  const [plannedDate, setPlannedDate] = useState('');
  // Work-Life Balance category. Empty string means "leave unset" — we omit
  // the field from the Firestore payload so Android's auto-classifier can
  // still operate. Only when the user explicitly picks a category do we
  // write it (parity audit § Surface 3 / T-S2).
  const [lifeCategory, setLifeCategory] = useState<LifeCategory | ''>('');
  // Cognitive load (start-friction). Same omit-on-empty semantics as
  // lifeCategory — the Firestore writer omits the field when value is
  // empty/null so Android-side state isn't clobbered.
  const [cognitiveLoad, setCognitiveLoad] = useState<CognitiveLoad | ''>('');
  // Reward / output mode (Work / Play / Relax). Same omit-on-empty
  // semantics as lifeCategory + cognitiveLoad.
  const [taskMode, setTaskMode] = useState<TaskMode | ''>('');

  // Subtasks
  const [subtasks, setSubtasks] = useState<Task[]>([]);
  const [newSubtaskTitle, setNewSubtaskTitle] = useState('');

  // Tags
  const [taskTagIds, setTaskTagIds] = useState<string[]>([]);
  const [showNewTag, setShowNewTag] = useState(false);
  const [newTagName, setNewTagName] = useState('');
  const [newTagColor, setNewTagColor] = useState(TAG_COLORS[0]);

  const saveTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const isCreate = mode === 'create';
  const task = selectedTask;

  // Load project and tag data
  useEffect(() => {
    fetchAllProjects();
    fetchTags();
  }, [fetchAllProjects, fetchTags]);

  // Initialize form from task
  useEffect(() => {
    if (isCreate) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- form-init: seed editor state from task / reset on create-mode toggle (parent passes value via prop)
      setTitle('');
      setDescription('');
      setPriority(3);
      setStatus('todo');
      setDueDate('');
      setDueTime('');
      setProjectId(defaultProjectId ?? null);
      setNotes('');
      setSubtasks([]);
      setTaskTagIds([]);
      setLifeCategory('');
      setCognitiveLoad('');
      setTaskMode('');
      return;
    }
    if (!task) return;
    setTitle(task.title);
    setDescription(task.description || '');
    setPriority(task.priority);
    setStatus(task.status);
    setDueDate(task.due_date || '');
    setDueTime(task.due_time || '');
    setProjectId(task.project_id);
    setNotes(task.notes || '');
    setDuration(task.estimated_duration?.toString() || '');
    setSubtasks(task.subtasks || []);
    setTaskTagIds(task.tags?.map((t) => t.id) || []);

    setPlannedDate(task.planned_date || '');
    setLifeCategory((task.life_category as LifeCategory | null) ?? '');
    setCognitiveLoad((task.cognitive_load as CognitiveLoad | null) ?? '');
    setTaskMode((task.task_mode as TaskMode | null) ?? '');
    if (task.recurrence_json) {
      try {
        const rule = JSON.parse(task.recurrence_json);
        setRecurrenceType(rule.type || '');
        setRecurrenceInterval(rule.interval || 1);
        setRecurrenceDaysOfWeek(
          Array.isArray(rule.days_of_week) ? rule.days_of_week : [],
        );
        setRecurrenceAfterCompletion(!!rule.after_completion);
        if (rule.end_date) {
          setRecurrenceEndMode('on');
          setRecurrenceEndDate(rule.end_date);
        } else if (rule.end_after_count) {
          setRecurrenceEndMode('after');
          setRecurrenceEndAfter(rule.end_after_count);
        } else {
          setRecurrenceEndMode('never');
        }
      } catch {
        // ignore parse errors
      }
    }
  }, [task, isCreate, defaultProjectId]);

  // Auto-save debounced (edit mode only)
  const autoSave = useCallback(
    (data: TaskUpdate) => {
      if (isCreate || !task) return;
      if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
      saveTimerRef.current = setTimeout(async () => {
        setSaving(true);
        try {
          await updateTask(task.id, data);
          setSaved(true);
          setTimeout(() => setSaved(false), 2000);
          onUpdate?.();
        } catch {
          toast.error('Failed to save changes');
        } finally {
          setSaving(false);
        }
      }, 1000);
    },
    [isCreate, task, updateTask, onUpdate],
  );

  // Persist recurrence changes via auto-save. Rebuilds the JSON blob any
  // time any recurrence-related control moves so the saved shape stays
  // in sync with what the user sees.
  useEffect(() => {
    if (isCreate || !task) return;
    let recurrenceJson: string | undefined;
    if (recurrenceType) {
      const rule: Record<string, unknown> = {
        type: recurrenceType,
        interval: recurrenceInterval,
      };
      if (recurrenceType === 'weekly' && recurrenceDaysOfWeek.length > 0) {
        rule.days_of_week = [...recurrenceDaysOfWeek].sort();
      }
      if (recurrenceAfterCompletion) rule.after_completion = true;
      if (recurrenceEndMode === 'after') {
        rule.end_after_count = recurrenceEndAfter;
      } else if (recurrenceEndMode === 'on' && recurrenceEndDate) {
        rule.end_date = recurrenceEndDate;
      }
      recurrenceJson = JSON.stringify(rule);
    } else {
      recurrenceJson = '';
    }
    autoSave({ recurrence_json: recurrenceJson });
    // Intentionally omit autoSave from deps — it's stable within a session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    isCreate,
    task,
    recurrenceType,
    recurrenceInterval,
    recurrenceDaysOfWeek,
    recurrenceAfterCompletion,
    recurrenceEndMode,
    recurrenceEndAfter,
    recurrenceEndDate,
  ]);

  useEffect(() => {
    return () => {
      if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    };
  }, []);

  const handleTitleChange = (v: string) => {
    setTitle(v);
    autoSave({ title: v });
  };

  const handleDescriptionChange = (v: string) => {
    setDescription(v);
    autoSave({ description: v });
  };

  const handlePriorityChange = (p: TaskPriority) => {
    setPriority(p);
    autoSave({ priority: p });
  };

  const handleStatusChange = (s: TaskStatus) => {
    setStatus(s);
    autoSave({ status: s });
  };

  const handleDueDateChange = (v: string) => {
    setDueDate(v);
    autoSave({ due_date: v || undefined });
  };

  const handleDueTimeChange = (v: string) => {
    setDueTime(v);
    // Persist the wall-clock time so Android receives the same value on
    // its next sync. Empty string clears the time; the firestore writer
    // anchors `HH:mm` to the current `due_date` to produce the millis
    // representation Android stores in `tasks.due_time`.
    autoSave({ due_time: v === '' ? null : v });
  };

  const handleLifeCategoryChange = (v: LifeCategory | '') => {
    setLifeCategory(v);
    // Always pass the value through — selecting "Uncategorized" maps to
    // `null` so the Android-side classifier can take over again.
    autoSave({ lifeCategory: v === '' ? null : v });
  };

  // Parity Batch 3 D.1c. Mirrors Android
  // `AddEditTaskViewModel.tryUpgradeLifeCategoryWithClaude`
  // (`app/.../AddEditTaskViewModel.kt:714-738`). Fire-and-forget call to
  // `/ai/life-category/classify_text`. On any failure (AI off, network,
  // 429, 451, 5xx, garbage response, UNCATEGORIZED result) the current
  // chip stays — Auto never blanks a real selection.
  const handleLifeCategoryAutoClick = useCallback(async () => {
    if (!title.trim()) {
      toast.error('Enter a title before auto-classifying');
      return;
    }
    if (!aiFeaturesEnabled) {
      toast.error('AI Features are off — enable them in Settings to auto-classify');
      return;
    }
    if (lifeCategoryAutoBusy) return;
    setLifeCategoryAutoBusy(true);
    const titleSnapshot = title;
    const descriptionSnapshot = description;
    try {
      const result = await aiLifeCategoryClassifyText({
        title: titleSnapshot,
        description: descriptionSnapshot.trim() || undefined,
      });
      // Race-guard: if the user kept typing during the call, don't
      // overwrite stale input. Matches Android's snapshot check.
      if (title !== titleSnapshot || description !== descriptionSnapshot) {
        return;
      }
      const next = result.category as LifeCategory | 'UNCATEGORIZED';
      if (next === 'UNCATEGORIZED' || !next) {
        toast.success("AI couldn't pick — keeping your current choice");
        return;
      }
      if (next === lifeCategory) {
        toast.success('Already a match');
        return;
      }
      handleLifeCategoryChange(next as LifeCategory);
      toast.success(`Set Life Category: ${next.replace('_', '-')}`);
    } catch {
      toast.error('Auto-classify failed — try again later');
    } finally {
      setLifeCategoryAutoBusy(false);
    }
    // handleLifeCategoryChange + setLifeCategory are stable closures over
    // setState — intentionally omitted from deps to avoid re-creating this
    // callback on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    title,
    description,
    aiFeaturesEnabled,
    lifeCategoryAutoBusy,
    lifeCategory,
  ]);

  const handleCognitiveLoadChange = (v: CognitiveLoad | '') => {
    setCognitiveLoad(v);
    // Same semantics as lifeCategory — empty => null lets the Android
    // CognitiveLoadClassifier take over again on the next save.
    autoSave({ cognitiveLoad: v === '' ? null : v });
  };

  const handleTaskModeChange = (v: TaskMode | '') => {
    setTaskMode(v);
    // Same omit-on-null semantics as the other orthogonal dimensions.
    autoSave({ taskMode: v === '' ? null : v });
  };

  const handleCreate = async () => {
    if (!title.trim()) {
      toast.error('Title is required');
      return;
    }
    const targetProjectId = projectId || projects[0]?.id;
    if (!targetProjectId) {
      toast.error('No project available. Create a project first.');
      return;
    }
    setSaving(true);
    try {
      await createTask(targetProjectId, {
        title: title.trim(),
        description: description || undefined,
        priority,
        status,
        due_date: dueDate || undefined,
        due_time: dueTime || undefined,
        lifeCategory: lifeCategory || undefined,
        cognitiveLoad: cognitiveLoad || undefined,
        taskMode: taskMode || undefined,
      });
      toast.success('Task created');
      onUpdate?.();
      onClose();
    } catch {
      toast.error('Failed to create task');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!task) return;
    setDeleting(true);
    try {
      await deleteTask(task.id);
      toast.success('Task deleted');
      onUpdate?.();
      onClose();
    } catch {
      toast.error('Failed to delete task');
    } finally {
      setDeleting(false);
      setDeleteOpen(false);
    }
  };

  const handleDuplicate = async () => {
    if (!task) return;
    const targetProjectId = task.project_id || projects[0]?.id;
    if (!targetProjectId) return;
    try {
      const newTask = await createTask(targetProjectId, {
        title: `${task.title} (copy)`,
        description: task.description || undefined,
        priority: task.priority,
      });

      // Duplicate subtasks if option selected
      if (duplicateSubtasks && subtasks.length > 0) {
        for (const subtask of subtasks) {
          await createSubtask(newTask.id, {
            title: subtask.title,
            description: subtask.description || undefined,
            priority: subtask.priority,
          });
        }
      }

      toast.success('Task duplicated');
      setDuplicateOpen(false);
      onUpdate?.();
    } catch {
      toast.error('Failed to duplicate task');
    }
  };

  const handleAddSubtask = async () => {
    if (!newSubtaskTitle.trim() || !task) return;
    try {
      const subtask = await createSubtask(task.id, {
        title: newSubtaskTitle.trim(),
      });
      setSubtasks((prev) => [...prev, subtask]);
      setNewSubtaskTitle('');
      // Re-fetch to get updated parent
      await fetchTask(task.id);
    } catch {
      toast.error('Failed to add subtask');
    }
  };

  const handleToggleSubtask = async (subtask: Task) => {
    const newStatus = subtask.status === 'done' ? 'todo' : 'done';
    try {
      await updateTask(subtask.id, { status: newStatus });
      setSubtasks((prev) =>
        prev.map((s) =>
          s.id === subtask.id ? { ...s, status: newStatus } : s,
        ),
      );
    } catch {
      toast.error('Failed to update subtask');
    }
  };

  const handleDeleteSubtask = async (subtaskId: string) => {
    try {
      await deleteTask(subtaskId);
      setSubtasks((prev) => prev.filter((s) => s.id !== subtaskId));
    } catch {
      toast.error('Failed to delete subtask');
    }
  };

  const handleCreateTag = async () => {
    if (!newTagName.trim()) return;
    try {
      const tag = await createTag({
        name: newTagName.trim(),
        color: newTagColor,
      });
      setTaskTagIds((prev) => [...prev, tag.id]);
      setNewTagName('');
      setShowNewTag(false);
    } catch {
      toast.error('Failed to create tag');
    }
  };

  const toggleTag = (tagId: string) => {
    setTaskTagIds((prev) =>
      prev.includes(tagId) ? prev.filter((id) => id !== tagId) : [...prev, tagId],
    );
  };

  const drawerTitle = (
    <div className="flex items-center gap-2">
      <span>{isCreate ? 'New Task' : 'Edit Task'}</span>
      {saving && (
        <span className="flex items-center gap-1 text-xs text-[var(--color-text-secondary)]">
          <Loader2 className="h-3 w-3 animate-spin" />
          Saving...
        </span>
      )}
      {saved && !saving && (
        <span className="flex items-center gap-1 text-xs text-green-500">
          <Check className="h-3 w-3" />
          Saved
        </span>
      )}
    </div>
  );

  const subtasksDone = subtasks.filter((s) => s.status === 'done').length;
  const subtaskProgress =
    subtasks.length > 0
      ? Math.round((subtasksDone / subtasks.length) * 100)
      : 0;

  return (
    <>
      <Drawer isOpen onClose={onClose} title={drawerTitle as unknown as string}>
        <div className="flex h-full flex-col">
          <Tabs
            tabs={TABS}
            activeTab={activeTab}
            onChange={setActiveTab}
            className="mb-4 shrink-0"
          />

          <div className="flex-1 overflow-y-auto">
            {/* Details Tab */}
            {activeTab === 'details' && (
              <div className="flex flex-col gap-4">
                {/* Title */}
                <div>
                  <input
                    type="text"
                    value={title}
                    onChange={(e) => handleTitleChange(e.target.value)}
                    placeholder="Task title..."
                    className="w-full border-none bg-transparent text-lg font-semibold text-[var(--color-text-primary)] outline-none placeholder-[var(--color-text-secondary)]"
                    autoFocus={isCreate}
                  />
                </div>

                {/* Description */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Description
                  </label>
                  <textarea
                    value={description}
                    onChange={(e) => handleDescriptionChange(e.target.value)}
                    placeholder="Add description..."
                    rows={3}
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  />
                </div>

                {/* Priority */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Priority
                  </label>
                  <div className="flex gap-2">
                    {([1, 2, 3, 4] as TaskPriority[]).map((p) => (
                      <button
                        key={p}
                        onClick={() => handlePriorityChange(p)}
                        className={`flex-1 rounded-lg border px-3 py-2 text-xs font-medium transition-colors ${
                          priority === p
                            ? 'border-current'
                            : 'border-[var(--color-border)]'
                        }`}
                        style={{
                          color:
                            priority === p
                              ? PRIORITY_CONFIG[p].color
                              : 'var(--color-text-secondary)',
                          backgroundColor:
                            priority === p
                              ? PRIORITY_CONFIG[p].bgColor
                              : 'transparent',
                        }}
                      >
                        {PRIORITY_CONFIG[p].label}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Status */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Status
                  </label>
                  <select
                    value={status}
                    onChange={(e) =>
                      handleStatusChange(e.target.value as TaskStatus)
                    }
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  >
                    {STATUS_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </div>

                {/* Subtasks */}
                {!isCreate && (
                  <div>
                    <div className="mb-2 flex items-center justify-between">
                      <label className="text-xs font-medium text-[var(--color-text-secondary)]">
                        Subtasks
                      </label>
                      {subtasks.length > 0 && (
                        <span className="text-xs text-[var(--color-text-secondary)]">
                          {subtasksDone}/{subtasks.length}
                        </span>
                      )}
                    </div>

                    {/* Subtask progress bar */}
                    {subtasks.length > 0 && (
                      <div className="mb-2 h-1.5 w-full overflow-hidden rounded-full bg-[var(--color-bg-secondary)]">
                        <div
                          className="h-full rounded-full bg-[var(--color-accent)] transition-all duration-300"
                          style={{ width: `${subtaskProgress}%` }}
                        />
                      </div>
                    )}

                    {/* Subtask list */}
                    <div className="flex flex-col gap-1">
                      {subtasks.map((subtask) => (
                        <div
                          key={subtask.id}
                          className="group flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-[var(--color-bg-secondary)]"
                        >
                          <GripVertical className="h-3 w-3 shrink-0 cursor-grab text-[var(--color-text-secondary)] opacity-0 group-hover:opacity-100" />
                          <Checkbox
                            checked={subtask.status === 'done'}
                            onChange={() => handleToggleSubtask(subtask)}
                          />
                          <span
                            className={`flex-1 text-sm ${
                              subtask.status === 'done'
                                ? 'text-[var(--color-text-secondary)] line-through'
                                : 'text-[var(--color-text-primary)]'
                            }`}
                          >
                            {subtask.title}
                          </span>
                          <button
                            onClick={() => handleDeleteSubtask(subtask.id)}
                            className="shrink-0 text-[var(--color-text-secondary)] opacity-0 hover:text-red-500 group-hover:opacity-100 transition-colors"
                          >
                            <X className="h-3.5 w-3.5" />
                          </button>
                        </div>
                      ))}
                    </div>

                    {/* Add subtask input */}
                    <div className="mt-1 flex items-center gap-2">
                      <Plus className="h-4 w-4 shrink-0 text-[var(--color-text-secondary)]" />
                      <input
                        type="text"
                        value={newSubtaskTitle}
                        onChange={(e) => setNewSubtaskTitle(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') handleAddSubtask();
                        }}
                        placeholder="Add subtask..."
                        className="flex-1 border-none bg-transparent py-1 text-sm text-[var(--color-text-primary)] outline-none placeholder-[var(--color-text-secondary)]"
                      />
                    </div>
                  </div>
                )}
              </div>
            )}

            {/* Schedule Tab */}
            {activeTab === 'schedule' && (
              <div className="flex flex-col gap-4">
                {/* Due Date */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Due Date
                  </label>
                  <input
                    type="date"
                    value={dueDate}
                    onChange={(e) => handleDueDateChange(e.target.value)}
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  />
                </div>

                {/* Due Time */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Due Time (Optional)
                  </label>
                  <input
                    type="time"
                    value={dueTime}
                    onChange={(e) => handleDueTimeChange(e.target.value)}
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  />
                </div>

                {/* Planned Date (plan-for-a-specific-day) */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Planned Date (Optional)
                  </label>
                  <input
                    type="date"
                    value={plannedDate}
                    onChange={(e) => {
                      setPlannedDate(e.target.value);
                      autoSave({ planned_date: e.target.value || undefined } as TaskUpdate);
                    }}
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  />
                  <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
                    Surfaces this task on the Today screen for the chosen day,
                    independent of the due date.
                  </p>
                </div>

                {/* Reminder */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Reminder
                  </label>
                  <select
                    value={reminderOffset}
                    onChange={(e) => setReminderOffset(e.target.value)}
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  >
                    {REMINDER_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                  {reminderOffset && (
                    <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
                      Web notifications are local-only — reminders don't fire
                      on Android until cross-device reminder scheduling lands.
                    </p>
                  )}
                </div>

                {/* Recurrence */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Recurrence
                  </label>
                  <select
                    value={recurrenceType}
                    onChange={(e) => setRecurrenceType(e.target.value)}
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  >
                    {RECURRENCE_TYPES.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>

                  {recurrenceType && (
                    <div className="mt-2">
                      <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                        Every
                      </label>
                      <div className="flex items-center gap-2">
                        <input
                          type="number"
                          min={1}
                          value={recurrenceInterval}
                          onChange={(e) =>
                            setRecurrenceInterval(parseInt(e.target.value) || 1)
                          }
                          className="w-20 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                        />
                        <span className="text-sm text-[var(--color-text-secondary)]">
                          {recurrenceType === 'daily'
                            ? 'day(s)'
                            : recurrenceType === 'weekly'
                              ? 'week(s)'
                              : recurrenceType === 'monthly'
                                ? 'month(s)'
                                : 'year(s)'}
                        </span>
                      </div>

                      {(recurrenceType === 'weekly' ||
                        recurrenceType === 'biweekly') && (
                        <div className="mt-2">
                          <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                            Days
                          </label>
                          <div className="flex gap-1">
                            {WEEKDAYS.map(({ idx, label }) => {
                              const selected = recurrenceDaysOfWeek.includes(idx);
                              return (
                                <button
                                  key={idx}
                                  type="button"
                                  onClick={() =>
                                    setRecurrenceDaysOfWeek((prev) =>
                                      prev.includes(idx)
                                        ? prev.filter((d) => d !== idx)
                                        : [...prev, idx],
                                    )
                                  }
                                  className={`rounded-md border px-2 py-1 text-xs font-medium transition-colors ${
                                    selected
                                      ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                                      : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
                                  }`}
                                  aria-pressed={selected}
                                >
                                  {label}
                                </button>
                              );
                            })}
                          </div>
                        </div>
                      )}

                      {/* After-completion flag */}
                      <label className="mt-2 flex items-center gap-2 text-sm text-[var(--color-text-primary)]">
                        <input
                          type="checkbox"
                          checked={recurrenceAfterCompletion}
                          onChange={(e) =>
                            setRecurrenceAfterCompletion(e.target.checked)
                          }
                          className="h-4 w-4 rounded border-[var(--color-border)] text-[var(--color-accent)]"
                        />
                        Schedule next occurrence from when I complete this
                        one (not from the due date)
                      </label>

                      {/* End condition */}
                      <div className="mt-3">
                        <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                          Ends
                        </label>
                        <div className="flex flex-col gap-1.5">
                          {(
                            [
                              { key: 'never', label: 'Never' },
                              { key: 'after', label: 'After N occurrences' },
                              { key: 'on', label: 'On a specific date' },
                            ] as const
                          ).map(({ key, label }) => (
                            <label
                              key={key}
                              className="flex items-center gap-2 text-sm text-[var(--color-text-primary)]"
                            >
                              <input
                                type="radio"
                                name="recurrence-end"
                                checked={recurrenceEndMode === key}
                                onChange={() => setRecurrenceEndMode(key)}
                                className="text-[var(--color-accent)]"
                              />
                              {label}
                            </label>
                          ))}
                        </div>
                        {recurrenceEndMode === 'after' && (
                          <input
                            type="number"
                            min={1}
                            value={recurrenceEndAfter}
                            onChange={(e) =>
                              setRecurrenceEndAfter(
                                Math.max(1, Number(e.target.value) || 1),
                              )
                            }
                            className="mt-1 w-24 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                          />
                        )}
                        {recurrenceEndMode === 'on' && (
                          <input
                            type="date"
                            value={recurrenceEndDate}
                            onChange={(e) =>
                              setRecurrenceEndDate(e.target.value)
                            }
                            className="mt-1 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                          />
                        )}
                      </div>
                    </div>
                  )}
                </div>

                {/* Duration */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Estimated Duration (Minutes)
                  </label>
                  <input
                    type="number"
                    min={0}
                    value={duration}
                    onChange={(e) => setDuration(e.target.value)}
                    placeholder="e.g. 30"
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  />
                </div>
              </div>
            )}

            {/* Organize Tab */}
            {activeTab === 'organize' && (
              <div className="flex flex-col gap-4">
                {/* Project */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Project
                  </label>
                  <select
                    value={projectId || ''}
                    onChange={(e) =>
                      setProjectId(e.target.value || null)
                    }
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  >
                    <option value="">No Project</option>
                    {projects.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.title}
                      </option>
                    ))}
                  </select>
                </div>

                {/* Life Category (Work-Life Balance) */}
                <div>
                  <div className="mb-1 flex items-center justify-between">
                    <label className="block text-xs font-medium text-[var(--color-text-secondary)]">
                      Life Category
                    </label>
                    <button
                      type="button"
                      onClick={() => void handleLifeCategoryAutoClick()}
                      disabled={
                        lifeCategoryAutoBusy ||
                        !aiFeaturesEnabled ||
                        !title.trim()
                      }
                      aria-label="Auto-classify Life Category with AI"
                      title={
                        !aiFeaturesEnabled
                          ? 'AI Features are off — enable them in Settings'
                          : !title.trim()
                          ? 'Enter a title first'
                          : 'Auto-classify with AI'
                      }
                      className="inline-flex items-center gap-1 rounded-full border border-[var(--color-accent)]/40 bg-[var(--color-accent)]/10 px-2 py-0.5 text-[10px] font-medium text-[var(--color-accent)] transition hover:bg-[var(--color-accent)]/20 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      {lifeCategoryAutoBusy ? (
                        <Loader2 className="h-3 w-3 animate-spin" />
                      ) : (
                        <Sparkles className="h-3 w-3" />
                      )}
                      Auto
                    </button>
                  </div>
                  <select
                    value={lifeCategory}
                    onChange={(e) =>
                      handleLifeCategoryChange(
                        (e.target.value as LifeCategory | '') || '',
                      )
                    }
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  >
                    {LIFE_CATEGORY_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                  <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
                    Powers the Work-Life Balance dashboard. Tap Auto to let
                    Claude classify the task from its title + description.
                  </p>
                </div>

                {/* Cognitive Load (start-friction) */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Cognitive Load
                  </label>
                  <select
                    value={cognitiveLoad}
                    onChange={(e) =>
                      handleCognitiveLoadChange(
                        (e.target.value as CognitiveLoad | '') || '',
                      )
                    }
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  >
                    {COGNITIVE_LOAD_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                  <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
                    How hard is it to start? Independent of duration,
                    importance, and reward type. Leave as Uncategorized to
                    let Android auto-classify.
                  </p>
                </div>

                {/* Task Mode (Work / Play / Relax) */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Task Mode
                  </label>
                  <select
                    value={taskMode}
                    onChange={(e) =>
                      handleTaskModeChange(
                        (e.target.value as TaskMode | '') || '',
                      )
                    }
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  >
                    {TASK_MODE_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                  <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
                    What kind of output does this produce? Orthogonal to
                    Life Category and Cognitive Load. Leave as Uncategorized
                    to let Android auto-classify.
                  </p>
                </div>

                {/* Dependencies / Blockers (parity B.12 — mirrors Android
                    `OrganizeTab.kt :: BlockersSection`). Only meaningful
                    once the task has a stable id, so suppress in create
                    mode and show a hint instead. */}
                {selectedTask ? (
                  <DependencyEditor taskId={selectedTask.id} />
                ) : (
                  <div>
                    <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                      Blockers
                    </label>
                    <p className="text-xs italic text-[var(--color-text-secondary)]">
                      Save the task first to add blockers.
                    </p>
                  </div>
                )}

                {/* Tags */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Tags
                  </label>
                  <div className="flex flex-wrap gap-1.5">
                    {tags.map((tag) => (
                      <button
                        key={tag.id}
                        onClick={() => toggleTag(tag.id)}
                        className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition-colors ${
                          taskTagIds.includes(tag.id)
                            ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                            : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:border-[var(--color-accent)]'
                        }`}
                      >
                        <span
                          className="h-2 w-2 rounded-full"
                          style={{
                            backgroundColor: tag.color || 'var(--color-accent)',
                          }}
                        />
                        {tag.name}
                      </button>
                    ))}
                    <button
                      onClick={() => setShowNewTag(!showNewTag)}
                      className="inline-flex items-center gap-1 rounded-full border border-dashed border-[var(--color-border)] px-2.5 py-1 text-xs text-[var(--color-text-secondary)] hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]"
                    >
                      <Plus className="h-3 w-3" />
                      New Tag
                    </button>
                  </div>

                  {/* New tag form */}
                  {showNewTag && (
                    <div className="mt-2 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3">
                      <input
                        type="text"
                        value={newTagName}
                        onChange={(e) => setNewTagName(e.target.value)}
                        placeholder="Tag name..."
                        className="mb-2 w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                        autoFocus
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') handleCreateTag();
                        }}
                      />
                      <div className="mb-2 flex flex-wrap gap-1.5">
                        {TAG_COLORS.map((c) => (
                          <button
                            key={c}
                            onClick={() => setNewTagColor(c)}
                            className={`h-6 w-6 rounded-full transition-transform ${
                              newTagColor === c
                                ? 'scale-110 ring-2 ring-offset-1'
                                : ''
                            }`}
                            style={{
                              backgroundColor: c,
                              outlineColor: c,
                            }}
                          />
                        ))}
                      </div>
                      <div className="flex justify-end gap-2">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setShowNewTag(false)}
                        >
                          Cancel
                        </Button>
                        <Button size="sm" onClick={handleCreateTag}>
                          Create
                        </Button>
                      </div>
                    </div>
                  )}
                </div>

                {/* Notes */}
                <div>
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Notes
                  </label>
                  <textarea
                    value={notes}
                    onChange={(e) => setNotes(e.target.value)}
                    placeholder="Add notes..."
                    rows={4}
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  />
                </div>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="mt-4 shrink-0 border-t border-[var(--color-border)] pt-4">
            {isCreate ? (
              <div className="flex justify-end gap-2">
                <Button variant="ghost" onClick={onClose}>
                  Cancel
                </Button>
                <Button onClick={handleCreate} loading={saving}>
                  Create Task
                </Button>
              </div>
            ) : (
              <>
                <div className="flex items-center gap-2">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setDuplicateOpen(true)}
                  >
                    <Copy className="h-4 w-4" />
                    Duplicate
                  </Button>
                  {onSaveAsTemplate && (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() =>
                        onSaveAsTemplate({
                          title: title || undefined,
                          description: description || undefined,
                          priority: priority,
                          project_id: projectId || undefined,
                          tags: taskTagIds,
                          subtasks: subtasks.map((s) => s.title),
                          recurrence_json: recurrenceType
                            ? JSON.stringify({ type: recurrenceType, interval: recurrenceInterval })
                            : undefined,
                          estimated_duration: duration ? parseInt(duration) : undefined,
                        })
                      }
                    >
                      <Save className="h-4 w-4" />
                      Save as Template
                    </Button>
                  )}
                  <Button
                    variant="danger"
                    size="sm"
                    onClick={() => setDeleteOpen(true)}
                  >
                    <Trash2 className="h-4 w-4" />
                    Delete
                  </Button>
                </div>
                {task && (
                  <p className="mt-3 text-xs text-[var(--color-text-secondary)]">
                    Created {formatRelative(task.created_at)} · Updated{' '}
                    {formatRelative(task.updated_at)}
                  </p>
                )}
              </>
            )}
          </div>
        </div>
      </Drawer>

      <ConfirmDialog
        isOpen={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        onConfirm={handleDelete}
        title="Delete Task"
        message="Are you sure you want to delete this task? This action cannot be undone."
        confirmLabel="Delete"
        variant="danger"
        loading={deleting}
      />

      {/* Duplicate dialog */}
      <ConfirmDialog
        isOpen={duplicateOpen}
        onClose={() => setDuplicateOpen(false)}
        onConfirm={handleDuplicate}
        title="Duplicate Task"
        message={
          <div className="flex flex-col gap-3">
            <p className="text-sm text-[var(--color-text-secondary)]">
              Create a copy of &ldquo;{task?.title}&rdquo; with the same description, priority, project, and tags. The due date will be cleared.
            </p>
            {subtasks.length > 0 && (
              <label className="flex items-center gap-2 text-sm text-[var(--color-text-primary)]">
                <input
                  type="checkbox"
                  checked={duplicateSubtasks}
                  onChange={(e) => setDuplicateSubtasks(e.target.checked)}
                  className="rounded border-[var(--color-border)]"
                />
                Include {subtasks.length} subtask{subtasks.length === 1 ? '' : 's'}
              </label>
            )}
          </div>
        }
        confirmLabel="Duplicate"
      />
    </>
  );
}
