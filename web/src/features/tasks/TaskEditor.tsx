import { useState, useEffect, useCallback, useRef } from 'react';
import {
  FileText,
  CalendarDays,
  FolderKanban,
  Copy,
  Loader2,
  Check,
  Save,
} from 'lucide-react';
import { toast } from 'sonner';
import { Drawer } from '@/components/ui/Drawer';
import { Tabs } from '@/components/ui/Tabs';
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import { useTagStore } from '@/stores/tagStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { aiLifeCategoryClassifyText } from '@/api/ai/chat';
import { formatRelative } from '@/utils/dates';
import { classifyTaskMode } from '@/utils/taskModeClassifier';
import { classifyCognitiveLoad } from '@/utils/cognitiveLoadClassifier';
import { DetailsTab } from '@/features/tasks/tabs/DetailsTab';
import { ScheduleTab } from '@/features/tasks/tabs/ScheduleTab';
import { OrganizeTab, TAG_COLORS } from '@/features/tasks/tabs/OrganizeTab';
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

/**
 * TaskEditor — 3-tab orchestrator (Details / Schedule / Organize).
 * Mirrors Android `addedittask/AddEditTaskScreen.kt`. Owns the form
 * state, debounced auto-save, and all save/delete/duplicate
 * orchestration; the tabs themselves are presentational.
 */
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
  // Auto-classify busy flags
  const [lifeCategoryAutoBusy, setLifeCategoryAutoBusy] = useState(false);
  // On-device keyword classifier Auto buttons for TaskMode +
  // CognitiveLoad. Classifier calls are synchronous; the busy flag
  // exists so we can paint a brief spinner for parity with the
  // LifeCategory Claude button.
  const [taskModeAutoBusy, setTaskModeAutoBusy] = useState(false);
  const [cognitiveLoadAutoBusy, setCognitiveLoadAutoBusy] = useState(false);
  // Zustand v5 stable selector — primitive value, no fresh object refs.
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
  const [recurrenceAfterCompletion, setRecurrenceAfterCompletion] =
    useState(false);
  const [recurrenceEndMode, setRecurrenceEndMode] = useState<
    'never' | 'after' | 'on'
  >('never');
  const [recurrenceEndAfter, setRecurrenceEndAfter] = useState(10);
  const [recurrenceEndDate, setRecurrenceEndDate] = useState('');
  const [reminderOffset, setReminderOffset] = useState('');
  const [plannedDate, setPlannedDate] = useState('');
  const [lifeCategory, setLifeCategory] = useState<LifeCategory | ''>('');
  const [cognitiveLoad, setCognitiveLoad] = useState<CognitiveLoad | ''>('');
  const [taskMode, setTaskMode] = useState<TaskMode | ''>('');
  // "Manually set" flags for the Organize-tab auto-classify chips. Mirror
  // of Android `AddEditTaskViewModel.{taskMode,cognitiveLoad}ManuallySet`
  // — the debounced auto-classify effect below short-circuits when the
  // user (or the DB load) has already picked a real chip, so we never
  // clobber an explicit choice. Refs (not state) so the effect can read
  // the latest value without re-running on flag flips. See Android
  // `OrganizeTab.kt:95-99` for the trigger this mirrors.
  const taskModeManuallySetRef = useRef(false);
  const cognitiveLoadManuallySetRef = useRef(false);
  const [goodEnoughOverrideEnabled, setGoodEnoughOverrideEnabled] =
    useState(false);
  const [goodEnoughMinutes, setGoodEnoughMinutes] = useState<string>('');
  const [maxRevisionsOverrideEnabled, setMaxRevisionsOverrideEnabled] =
    useState(false);
  const [maxRevisions, setMaxRevisions] = useState<string>('');

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
      taskModeManuallySetRef.current = false;
      cognitiveLoadManuallySetRef.current = false;
      setGoodEnoughOverrideEnabled(false);
      setGoodEnoughMinutes('');
      setMaxRevisionsOverrideEnabled(false);
      setMaxRevisions('');
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
    // Mirror Android: `xManuallySet = (value != null)` after DB load, so a
    // saved chip survives subsequent title edits but an empty chip
    // remains eligible for auto-classify.
    taskModeManuallySetRef.current = !!task.task_mode;
    cognitiveLoadManuallySetRef.current = !!task.cognitive_load;
    const ge = task.good_enough_minutes_override;
    setGoodEnoughOverrideEnabled(typeof ge === 'number');
    setGoodEnoughMinutes(typeof ge === 'number' ? String(ge) : '');
    const mr = task.max_revisions_override;
    setMaxRevisionsOverrideEnabled(typeof mr === 'number');
    setMaxRevisions(typeof mr === 'number' ? String(mr) : '');
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
          toast.error('Failed to Save Changes');
        } finally {
          setSaving(false);
        }
      }, 1000);
    },
    [isCreate, task, updateTask, onUpdate],
  );

  // Persist recurrence changes via auto-save.
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

  // Debounced auto-classify on title/description edit (Android parity).
  // Mirrors `OrganizeTab.kt:95-99` LaunchedEffect that re-runs
  // `autoPickTaskMode` / `autoPickCognitiveLoad` on every text edit.
  // Sticky once the user has manually picked a chip (or the DB load
  // seeded one) — see the manuallySet refs above. 400 ms debounce so
  // we classify on settled text, not every keystroke.
  useEffect(() => {
    const haystack = title.trim();
    // Empty title: clear any auto-picked chip so the next real edit
    // gets a fresh classification (matches Android's force=false path
    // where UNCATEGORIZED clears the chip). Manual picks survive.
    if (!haystack) {
      if (!taskModeManuallySetRef.current && taskMode !== '') {
        setTaskMode('');
        autoSave({ taskMode: null });
      }
      if (!cognitiveLoadManuallySetRef.current && cognitiveLoad !== '') {
        setCognitiveLoad('');
        autoSave({ cognitiveLoad: null });
      }
      return;
    }
    const handle = setTimeout(() => {
      if (!taskModeManuallySetRef.current) {
        const guess = classifyTaskMode(title, description.trim() || null);
        const next = guess === 'UNCATEGORIZED' ? '' : guess;
        if (next !== taskMode) {
          setTaskMode(next);
          autoSave({ taskMode: next === '' ? null : next });
        }
      }
      if (!cognitiveLoadManuallySetRef.current) {
        const guess = classifyCognitiveLoad(
          title,
          description.trim() || null,
        );
        const next = guess === 'UNCATEGORIZED' ? '' : guess;
        if (next !== cognitiveLoad) {
          setCognitiveLoad(next);
          autoSave({ cognitiveLoad: next === '' ? null : next });
        }
      }
    }, 400);
    return () => clearTimeout(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title, description]);

  // ---- Field change handlers (debounced auto-save) ----
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
    autoSave({ due_time: v === '' ? null : v });
  };

  const handlePlannedDateChange = (v: string) => {
    setPlannedDate(v);
    autoSave({ planned_date: v || undefined } as TaskUpdate);
  };

  const handleNotesChange = (v: string) => {
    setNotes(v);
    autoSave({ notes: v } as TaskUpdate);
  };

  const handleDurationChange = (v: string) => {
    setDuration(v);
    const minutes = parseInt(v, 10);
    autoSave({
      estimated_duration:
        Number.isFinite(minutes) && minutes > 0 ? minutes : undefined,
    } as TaskUpdate);
  };

  const handleReminderOffsetChange = (v: string) => {
    setReminderOffset(v);
    // Reminder is local-only on web today; auto-save fires when a real
    // field is wired up server-side (parity note above the input).
  };

  const handleLifeCategoryChange = (v: LifeCategory | '') => {
    setLifeCategory(v);
    autoSave({ lifeCategory: v === '' ? null : v });
  };

  const handleLifeCategoryAutoClick = useCallback(async () => {
    if (!title.trim()) {
      toast.error('Enter a Title Before Auto-Classifying');
      return;
    }
    if (!aiFeaturesEnabled) {
      toast.error(
        'AI Features Are Off — Enable Them in Settings to Auto-Classify',
      );
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
      if (title !== titleSnapshot || description !== descriptionSnapshot) {
        return;
      }
      const next = result.category as LifeCategory | 'UNCATEGORIZED';
      if (next === 'UNCATEGORIZED' || !next) {
        toast.success("AI Couldn't Pick — Keeping Your Current Choice");
        return;
      }
      if (next === lifeCategory) {
        toast.success('Already a Match');
        return;
      }
      handleLifeCategoryChange(next as LifeCategory);
      toast.success(`Set Life Category: ${next.replace('_', '-')}`);
    } catch {
      toast.error('Auto-Classify Failed — Try Again Later');
    } finally {
      setLifeCategoryAutoBusy(false);
    }
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
    // Manual pick is sticky — see ref declaration above. Picking the
    // empty chip clears the flag so the debounced effect can run again.
    cognitiveLoadManuallySetRef.current = v !== '';
    autoSave({ cognitiveLoad: v === '' ? null : v });
  };

  const handleTaskModeChange = (v: TaskMode | '') => {
    setTaskMode(v);
    // Manual pick is sticky — see ref declaration above.
    taskModeManuallySetRef.current = v !== '';
    autoSave({ taskMode: v === '' ? null : v });
  };

  const handleTaskModeAutoClick = useCallback(() => {
    if (!title.trim()) {
      toast.error('Enter a Title Before Auto-Classifying');
      return;
    }
    if (taskModeAutoBusy) return;
    setTaskModeAutoBusy(true);
    setTimeout(() => {
      try {
        const next = classifyTaskMode(title, description.trim() || null);
        if (next === 'UNCATEGORIZED') {
          toast.success("Couldn't Auto-Classify — Keeping Your Current Choice");
          return;
        }
        if (next === taskMode) {
          toast.success('Already a Match');
          return;
        }
        handleTaskModeChange(next);
        // Mirror Android `autoPickTaskMode(force=true)`: explicit Auto
        // press leaves the chip eligible for auto-refresh on the next
        // title edit. handleTaskModeChange sets the flag to true; undo
        // that so the debounced effect can run again.
        taskModeManuallySetRef.current = false;
        toast.success(
          `Set Task Mode: ${next.charAt(0)}${next.slice(1).toLowerCase()}`,
        );
      } finally {
        setTaskModeAutoBusy(false);
      }
    }, 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title, description, taskMode, taskModeAutoBusy]);

  const handleCognitiveLoadAutoClick = useCallback(() => {
    if (!title.trim()) {
      toast.error('Enter a Title Before Auto-Classifying');
      return;
    }
    if (cognitiveLoadAutoBusy) return;
    setCognitiveLoadAutoBusy(true);
    setTimeout(() => {
      try {
        const next = classifyCognitiveLoad(title, description.trim() || null);
        if (next === 'UNCATEGORIZED') {
          toast.success("Couldn't Auto-Classify — Keeping Your Current Choice");
          return;
        }
        if (next === cognitiveLoad) {
          toast.success('Already a Match');
          return;
        }
        handleCognitiveLoadChange(next);
        // Mirror Android `autoPickCognitiveLoad(force=true)`: explicit
        // Auto press leaves the chip eligible for auto-refresh on the
        // next title edit.
        cognitiveLoadManuallySetRef.current = false;
        toast.success(
          `Set Cognitive Load: ${next.charAt(0)}${next.slice(1).toLowerCase()}`,
        );
      } finally {
        setCognitiveLoadAutoBusy(false);
      }
    }, 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title, description, cognitiveLoad, cognitiveLoadAutoBusy]);

  const handleGoodEnoughOverrideToggle = (enabled: boolean) => {
    setGoodEnoughOverrideEnabled(enabled);
    if (!enabled) {
      setGoodEnoughMinutes('');
      autoSave({ good_enough_minutes_override: null });
    }
  };

  const handleGoodEnoughMinutesChange = (raw: string) => {
    setGoodEnoughMinutes(raw);
    if (!goodEnoughOverrideEnabled) return;
    const parsed = parseInt(raw, 10);
    if (Number.isFinite(parsed) && parsed > 0) {
      autoSave({ good_enough_minutes_override: parsed });
    }
  };

  const handleMaxRevisionsOverrideToggle = (enabled: boolean) => {
    setMaxRevisionsOverrideEnabled(enabled);
    if (!enabled) {
      setMaxRevisions('');
      autoSave({ max_revisions_override: null });
    }
  };

  const handleMaxRevisionsChange = (raw: string) => {
    setMaxRevisions(raw);
    if (!maxRevisionsOverrideEnabled) return;
    const parsed = parseInt(raw, 10);
    if (Number.isFinite(parsed) && parsed > 0) {
      autoSave({ max_revisions_override: parsed });
    }
  };

  const handleProjectChange = (id: string | null) => {
    setProjectId(id);
    autoSave({ project_id: id } as TaskUpdate);
  };

  // ---- Create / delete / duplicate orchestration ----
  const handleCreate = async () => {
    if (!title.trim()) {
      toast.error('Title Is Required');
      return;
    }
    const targetProjectId = projectId || projects[0]?.id;
    if (!targetProjectId) {
      toast.error('No Project Available. Create a Project First.');
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
      toast.success('Task Created');
      onUpdate?.();
      onClose();
    } catch {
      toast.error('Failed to Create Task');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!task) return;
    setDeleting(true);
    try {
      await deleteTask(task.id);
      toast.success('Task Deleted');
      onUpdate?.();
      onClose();
    } catch {
      toast.error('Failed to Delete Task');
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

      if (duplicateSubtasks && subtasks.length > 0) {
        for (const subtask of subtasks) {
          await createSubtask(newTask.id, {
            title: subtask.title,
            description: subtask.description || undefined,
            priority: subtask.priority,
          });
        }
      }

      toast.success('Task Duplicated');
      setDuplicateOpen(false);
      onUpdate?.();
    } catch {
      toast.error('Failed to Duplicate Task');
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
      await fetchTask(task.id);
    } catch {
      toast.error('Failed to Add Subtask');
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
      toast.error('Failed to Update Subtask');
    }
  };

  const handleDeleteSubtask = async (subtaskId: string) => {
    try {
      await deleteTask(subtaskId);
      setSubtasks((prev) => prev.filter((s) => s.id !== subtaskId));
    } catch {
      toast.error('Failed to Delete Subtask');
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
      toast.error('Failed to Create Tag');
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

  return (
    <>
      <Drawer
        isOpen
        onClose={onClose}
        title={drawerTitle as unknown as string}
      >
        <div className="flex h-full flex-col">
          <Tabs
            tabs={TABS}
            activeTab={activeTab}
            onChange={setActiveTab}
            className="mb-4 shrink-0"
          />

          <div className="flex-1 overflow-y-auto">
            {activeTab === 'details' && (
              <DetailsTab
                isCreate={isCreate}
                title={title}
                onTitleChange={handleTitleChange}
                description={description}
                onDescriptionChange={handleDescriptionChange}
                priority={priority}
                onPriorityChange={handlePriorityChange}
                status={status}
                onStatusChange={handleStatusChange}
                subtasks={subtasks}
                newSubtaskTitle={newSubtaskTitle}
                onNewSubtaskTitleChange={setNewSubtaskTitle}
                onAddSubtask={handleAddSubtask}
                onToggleSubtask={handleToggleSubtask}
                onDeleteSubtask={handleDeleteSubtask}
              />
            )}

            {activeTab === 'schedule' && (
              <ScheduleTab
                isCreate={isCreate}
                dueDate={dueDate}
                onDueDateChange={handleDueDateChange}
                dueTime={dueTime}
                onDueTimeChange={handleDueTimeChange}
                plannedDate={plannedDate}
                onPlannedDateChange={handlePlannedDateChange}
                reminderOffset={reminderOffset}
                onReminderOffsetChange={handleReminderOffsetChange}
                recurrenceType={recurrenceType}
                onRecurrenceTypeChange={setRecurrenceType}
                recurrenceInterval={recurrenceInterval}
                onRecurrenceIntervalChange={setRecurrenceInterval}
                recurrenceDaysOfWeek={recurrenceDaysOfWeek}
                onRecurrenceDaysOfWeekChange={setRecurrenceDaysOfWeek}
                recurrenceAfterCompletion={recurrenceAfterCompletion}
                onRecurrenceAfterCompletionChange={setRecurrenceAfterCompletion}
                recurrenceEndMode={recurrenceEndMode}
                onRecurrenceEndModeChange={setRecurrenceEndMode}
                recurrenceEndAfter={recurrenceEndAfter}
                onRecurrenceEndAfterChange={setRecurrenceEndAfter}
                recurrenceEndDate={recurrenceEndDate}
                onRecurrenceEndDateChange={setRecurrenceEndDate}
                duration={duration}
                onDurationChange={handleDurationChange}
              />
            )}

            {activeTab === 'organize' && (
              <OrganizeTab
                isCreate={isCreate}
                selectedTask={selectedTask}
                projects={projects}
                projectId={projectId}
                onProjectChange={handleProjectChange}
                tags={tags}
                taskTagIds={taskTagIds}
                onToggleTag={toggleTag}
                showNewTag={showNewTag}
                onToggleNewTag={() => setShowNewTag(!showNewTag)}
                newTagName={newTagName}
                newTagColor={newTagColor}
                onNewTagNameChange={setNewTagName}
                onNewTagColorChange={setNewTagColor}
                onCreateTag={handleCreateTag}
                lifeCategory={lifeCategory}
                onLifeCategoryChange={handleLifeCategoryChange}
                onLifeCategoryAutoClick={() => void handleLifeCategoryAutoClick()}
                lifeCategoryAutoBusy={lifeCategoryAutoBusy}
                aiFeaturesEnabled={aiFeaturesEnabled}
                taskMode={taskMode}
                onTaskModeChange={handleTaskModeChange}
                onTaskModeAutoClick={handleTaskModeAutoClick}
                taskModeAutoBusy={taskModeAutoBusy}
                cognitiveLoad={cognitiveLoad}
                onCognitiveLoadChange={handleCognitiveLoadChange}
                onCognitiveLoadAutoClick={handleCognitiveLoadAutoClick}
                cognitiveLoadAutoBusy={cognitiveLoadAutoBusy}
                goodEnoughOverrideEnabled={goodEnoughOverrideEnabled}
                goodEnoughMinutes={goodEnoughMinutes}
                onGoodEnoughOverrideToggle={handleGoodEnoughOverrideToggle}
                onGoodEnoughMinutesChange={handleGoodEnoughMinutesChange}
                maxRevisionsOverrideEnabled={maxRevisionsOverrideEnabled}
                maxRevisions={maxRevisions}
                onMaxRevisionsOverrideToggle={handleMaxRevisionsOverrideToggle}
                onMaxRevisionsChange={handleMaxRevisionsChange}
                notes={notes}
                onNotesChange={handleNotesChange}
                title={title}
                onRequestDelete={() => setDeleteOpen(true)}
              />
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
                  Save Task
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
                            ? JSON.stringify({
                                type: recurrenceType,
                                interval: recurrenceInterval,
                              })
                            : undefined,
                          estimated_duration: duration
                            ? parseInt(duration)
                            : undefined,
                        })
                      }
                    >
                      <Save className="h-4 w-4" />
                      Save as Template
                    </Button>
                  )}
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

      <ConfirmDialog
        isOpen={duplicateOpen}
        onClose={() => setDuplicateOpen(false)}
        onConfirm={handleDuplicate}
        title="Duplicate Task"
        message={
          <div className="flex flex-col gap-3">
            <p className="text-sm text-[var(--color-text-secondary)]">
              Create a copy of &ldquo;{task?.title}&rdquo; with the same
              description, priority, project, and tags. The due date will be
              cleared.
            </p>
            {subtasks.length > 0 && (
              <label className="flex items-center gap-2 text-sm text-[var(--color-text-primary)]">
                <input
                  type="checkbox"
                  checked={duplicateSubtasks}
                  onChange={(e) => setDuplicateSubtasks(e.target.checked)}
                  className="rounded border-[var(--color-border)]"
                />
                Include {subtasks.length} Subtask
                {subtasks.length === 1 ? '' : 's'}
              </label>
            )}
          </div>
        }
        confirmLabel="Duplicate"
      />
    </>
  );
}
