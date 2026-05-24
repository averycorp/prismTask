import { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import {
  CheckSquare,
  Search,
  SlidersHorizontal,
  ArrowUpDown,
  Plus,
  X,
  CheckCheck,
  Trash2,
  Flag,
  CalendarDays,
  LayoutList,
  FolderKanban,
  ChevronDown,
  ChevronRight,
  FolderInput,
  Tags,
  Eye,
  EyeOff,
} from 'lucide-react';
import { toast } from 'sonner';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import { useTagStore } from '@/stores/tagStore';
import { searchApi } from '@/api/search';
import * as firestoreTasks from '@/api/firestore/tasks';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { TaskRow } from '@/components/shared/TaskRow';
import { SortableTaskList } from '@/components/shared/SortableTaskList';
import { Button } from '@/components/ui/Button';
import { Checkbox } from '@/components/ui/Checkbox';
import { Spinner } from '@/components/ui/Spinner';
import { EmptyState } from '@/components/ui/EmptyState';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { computeUrgencyScore } from '@/utils/urgency';
import type { Task, TaskPriority, TaskStatus } from '@/types/task';
import { lazy, Suspense } from 'react';

const TaskEditor = lazy(() => import('@/features/tasks/TaskEditor'));

type SortKey =
  | 'priority'
  | 'due_date'
  | 'urgency'
  | 'alphabetical'
  | 'created_at'
  | 'sort_order';
type ViewMode = 'list' | 'grouped';

const SORT_OPTIONS: { value: SortKey; label: string }[] = [
  { value: 'priority', label: 'Priority' },
  { value: 'due_date', label: 'Due Date' },
  { value: 'urgency', label: 'Urgency' },
  { value: 'alphabetical', label: 'Alphabetical' },
  { value: 'sort_order', label: 'Custom Order' },
  { value: 'created_at', label: 'Created Date' },
];

const PRIORITY_FILTERS: { value: TaskPriority; label: string }[] = [
  { value: 1, label: 'Urgent' },
  { value: 2, label: 'High' },
  { value: 3, label: 'Medium' },
  { value: 4, label: 'Low' },
];

const STATUS_FILTERS: { value: TaskStatus; label: string }[] = [
  { value: 'todo', label: 'To Do' },
  { value: 'in_progress', label: 'In Progress' },
  { value: 'done', label: 'Done' },
  { value: 'cancelled', label: 'Cancelled' },
];

const LS_SORT_KEY = 'prismtask_tasklist_sort';
const LS_VIEW_KEY = 'prismtask_tasklist_view';
const LS_SHOW_COMPLETED_KEY = 'prismtask_tasklist_show_completed';

export function TaskListScreen() {
  const {
    selectedTaskIds,
    toggleTaskSelection,
    clearSelection,
    selectAll,
    updateTask,
    completeTask,
    uncompleteTask,
    bulkComplete,
    bulkDelete,
    bulkMove,
    bulkUpdatePriority,
    bulkUpdateDueDate,
    setSelectedTask,
  } = useTaskStore();

  const { projects, fetchAllProjects } = useProjectStore();
  const { tags, fetchTags } = useTagStore();

  // Load all tasks across all projects
  const [allTasks, setAllTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<Task[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [editorOpen, setEditorOpen] = useState(false);
  const [createMode, setCreateMode] = useState(false);

  // Filters
  const [filterOpen, setFilterOpen] = useState(false);
  const [priorityFilter, setPriorityFilter] = useState<TaskPriority[]>([]);
  const [statusFilter, setStatusFilter] = useState<TaskStatus[]>([]);
  const [projectFilter, setProjectFilter] = useState<string[]>([]);
  const [tagFilter, setTagFilter] = useState<string[]>([]);
  const [dueDateStart, setDueDateStart] = useState('');
  const [dueDateEnd, setDueDateEnd] = useState('');

  // Sort & view
  const [sortKey, setSortKey] = useState<SortKey>(
    () => (localStorage.getItem(LS_SORT_KEY) as SortKey) || 'due_date',
  );
  const [viewMode, setViewMode] = useState<ViewMode>(
    () => (localStorage.getItem(LS_VIEW_KEY) as ViewMode) || 'list',
  );
  const [showCompleted, setShowCompleted] = useState<boolean>(
    () => localStorage.getItem(LS_SHOW_COMPLETED_KEY) === 'true',
  );
  const [sortOpen, setSortOpen] = useState(false);
  const sortRef = useRef<HTMLDivElement>(null);

  // Bulk action state
  const [bulkPriorityOpen, setBulkPriorityOpen] = useState(false);
  const [bulkDateOpen, setBulkDateOpen] = useState(false);
  const [bulkDate, setBulkDate] = useState('');
  const [bulkMoveOpen, setBulkMoveOpen] = useState(false);
  const [bulkTagsOpen, setBulkTagsOpen] = useState(false);
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);
  const [bulkDeleting, setBulkDeleting] = useState(false);

  // Collapsed project groups
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());

  const searchTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const projectMap = useMemo(
    () => new Map(projects.map((p) => [p.id, p])),
    [projects],
  );

  // Hide archived projects from the filter chip row + bulk-move dropdown
  // (mirrors Android `pickerProjects`). The project-name lookup `projectMap`
  // above still covers archived rows so an already-assigned task still
  // resolves its project label correctly.
  const visibleProjects = useMemo(
    () => projects.filter((p) => p.status !== 'archived'),
    [projects],
  );

  // Fetch all data
  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      await fetchAllProjects();
      await fetchTags();
      // Fetch all tasks from Firestore
      const uid = getFirebaseUid();
      const all = await firestoreTasks.getAllTasks(uid);
      setAllTasks(all);
    } catch {
      toast.error('Failed to load tasks');
    } finally {
      setLoading(false);
    }
  }, [fetchAllProjects, fetchTags]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load tasks/projects/tags on mount
    loadData();
  }, [loadData]);

  // Persist sort & view preferences
  useEffect(() => {
    localStorage.setItem(LS_SORT_KEY, sortKey);
  }, [sortKey]);
  useEffect(() => {
    localStorage.setItem(LS_VIEW_KEY, viewMode);
  }, [viewMode]);
  useEffect(() => {
    localStorage.setItem(LS_SHOW_COMPLETED_KEY, String(showCompleted));
  }, [showCompleted]);

  // Close sort dropdown on outside click
  useEffect(() => {
    if (!sortOpen) return;
    const handler = (e: MouseEvent) => {
      if (sortRef.current && !sortRef.current.contains(e.target as Node)) {
        setSortOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [sortOpen]);

  // Debounced search
  useEffect(() => {
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    if (!searchQuery.trim()) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch reset: clear stale results when search query empties
      setSearchResults(null);
      return;
    }
    setSearching(true);
    searchTimerRef.current = setTimeout(async () => {
      try {
        const results = await searchApi.search(searchQuery);
        setSearchResults(Array.isArray(results) ? results : []);
      } catch {
        setSearchResults([]);
      } finally {
        setSearching(false);
      }
    }, 300);
    return () => {
      if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    };
  }, [searchQuery]);

  // Apply filters
  const filteredTasks = useMemo(() => {
    const source = searchResults ?? allTasks;
    return source.filter((task) => {
      if (priorityFilter.length > 0 && !priorityFilter.includes(task.priority))
        return false;
      if (statusFilter.length > 0) {
        if (!statusFilter.includes(task.status)) return false;
      } else if (!showCompleted) {
        // Default: hide completed (`done`) and discarded (`cancelled`) tasks.
        // An explicit status filter overrides this default.
        if (task.status === 'done' || task.status === 'cancelled') return false;
      }
      if (projectFilter.length > 0 && !projectFilter.includes(task.project_id))
        return false;
      if (tagFilter.length > 0) {
        const taskTagIds = task.tags?.map((t) => t.id) || [];
        if (!tagFilter.some((id) => taskTagIds.includes(id))) return false;
      }
      if (dueDateStart && task.due_date && task.due_date < dueDateStart)
        return false;
      if (dueDateEnd && task.due_date && task.due_date > dueDateEnd)
        return false;
      return true;
    });
  }, [
    allTasks,
    searchResults,
    priorityFilter,
    statusFilter,
    showCompleted,
    projectFilter,
    tagFilter,
    dueDateStart,
    dueDateEnd,
  ]);

  // Apply sort
  const sortedTasks = useMemo(() => {
    const sorted = [...filteredTasks];
    switch (sortKey) {
      case 'priority':
        sorted.sort((a, b) => a.priority - b.priority);
        break;
      case 'due_date':
        sorted.sort((a, b) => {
          if (!a.due_date && !b.due_date) return 0;
          if (!a.due_date) return 1;
          if (!b.due_date) return -1;
          return a.due_date.localeCompare(b.due_date);
        });
        break;
      case 'urgency':
        sorted.sort(
          (a, b) => computeUrgencyScore(b) - computeUrgencyScore(a),
        );
        break;
      case 'alphabetical':
        sorted.sort((a, b) => a.title.localeCompare(b.title));
        break;
      case 'created_at':
        sorted.sort((a, b) => b.created_at.localeCompare(a.created_at));
        break;
      case 'sort_order':
        sorted.sort((a, b) => a.sort_order - b.sort_order);
        break;
    }
    return sorted;
  }, [filteredTasks, sortKey]);

  // Group by project for grouped view
  const groupedTasks = useMemo(() => {
    const groups = new Map<string, Task[]>();
    for (const task of sortedTasks) {
      const list = groups.get(task.project_id) || [];
      list.push(task);
      groups.set(task.project_id, list);
    }
    return groups;
  }, [sortedTasks]);

  const handleComplete = useCallback(
    async (taskId: string) => {
      try {
        await completeTask(taskId);
        setAllTasks((prev) =>
          prev.map((t) =>
            t.id === taskId ? { ...t, status: 'done' as const } : t,
          ),
        );
        toast.success('Task completed');
      } catch {
        toast.error('Failed to complete task');
      }
    },
    [completeTask],
  );

  const handleUncomplete = useCallback(
    async (taskId: string) => {
      try {
        await uncompleteTask(taskId);
        setAllTasks((prev) =>
          prev.map((t) =>
            t.id === taskId ? { ...t, status: 'todo' as const } : t,
          ),
        );
      } catch {
        toast.error('Failed to reopen task');
      }
    },
    [uncompleteTask],
  );

  const handleReschedule = useCallback(
    async (taskId: string, date: string) => {
      try {
        await updateTask(taskId, { due_date: date });
        setAllTasks((prev) =>
          prev.map((t) => (t.id === taskId ? { ...t, due_date: date } : t)),
        );
        toast.success('Task rescheduled');
      } catch {
        toast.error('Failed to reschedule');
      }
    },
    [updateTask],
  );

  const handleTaskClick = useCallback(
    (task: Task) => {
      setSelectedTask(task);
      setCreateMode(false);
      setEditorOpen(true);
    },
    [setSelectedTask],
  );

  const handleNewTask = () => {
    setSelectedTask(null);
    setCreateMode(true);
    setEditorOpen(true);
  };

  // Bulk actions
  const selectedIds = Array.from(selectedTaskIds);
  const hasSelection = selectedIds.length > 0;
  const allSelected =
    sortedTasks.length > 0 && selectedIds.length === sortedTasks.length;

  const handleSelectAll = () => {
    if (allSelected) {
      clearSelection();
    } else {
      selectAll(sortedTasks.map((t) => t.id));
    }
  };

  const handleBulkComplete = async () => {
    try {
      await bulkComplete(selectedIds);
      setAllTasks((prev) =>
        prev.map((t) =>
          selectedIds.includes(t.id)
            ? { ...t, status: 'done' as const }
            : t,
        ),
      );
      toast.success(`${selectedIds.length} tasks completed`);
    } catch {
      toast.error('Bulk complete failed');
    }
  };

  const handleBulkDelete = async () => {
    setBulkDeleting(true);
    try {
      await bulkDelete(selectedIds);
      setAllTasks((prev) => prev.filter((t) => !selectedIds.includes(t.id)));
      toast.success(`${selectedIds.length} tasks deleted`);
    } catch {
      toast.error('Bulk delete failed');
    } finally {
      setBulkDeleting(false);
      setDeleteConfirmOpen(false);
    }
  };

  const handleBulkMove = async (targetProjectId: string) => {
    try {
      await bulkMove(selectedIds, targetProjectId);
      setAllTasks((prev) =>
        prev.map((t) =>
          selectedIds.includes(t.id)
            ? { ...t, project_id: targetProjectId }
            : t,
        ),
      );
      setBulkMoveOpen(false);
      clearSelection();
      toast.success(`Moved ${selectedIds.length} tasks`);
    } catch {
      toast.error('Failed to move tasks');
    }
  };

  const handleBulkPriority = async (p: TaskPriority) => {
    try {
      await bulkUpdatePriority(selectedIds, p);
      setAllTasks((prev) =>
        prev.map((t) =>
          selectedIds.includes(t.id) ? { ...t, priority: p } : t,
        ),
      );
      setBulkPriorityOpen(false);
      toast.success(`Priority updated for ${selectedIds.length} tasks`);
    } catch {
      toast.error('Failed to update priority');
    }
  };

  const handleBulkDate = async () => {
    if (!bulkDate) return;
    try {
      await bulkUpdateDueDate(selectedIds, bulkDate);
      setAllTasks((prev) =>
        prev.map((t) =>
          selectedIds.includes(t.id) ? { ...t, due_date: bulkDate } : t,
        ),
      );
      setBulkDateOpen(false);
      setBulkDate('');
      toast.success(`Due date updated for ${selectedIds.length} tasks`);
    } catch {
      toast.error('Failed to update due date');
    }
  };

  const hasActiveFilters =
    priorityFilter.length > 0 ||
    statusFilter.length > 0 ||
    projectFilter.length > 0 ||
    tagFilter.length > 0 ||
    dueDateStart ||
    dueDateEnd;

  const clearFilters = () => {
    setPriorityFilter([]);
    setStatusFilter([]);
    setProjectFilter([]);
    setTagFilter([]);
    setDueDateStart('');
    setDueDateEnd('');
  };

  const toggleGroup = (projectId: string) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(projectId)) next.delete(projectId);
      else next.add(projectId);
      return next;
    });
  };

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-4xl">
      {/* Header */}
      <div className="mb-6 flex items-center gap-3">
        <CheckSquare className="h-7 w-7 text-[var(--color-accent)]" />
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          All Tasks
        </h1>
        <span className="rounded-full bg-[var(--color-bg-secondary)] px-2.5 py-0.5 text-xs font-medium text-[var(--color-text-secondary)]">
          {sortedTasks.length}
        </span>
      </div>

      {/* Toolbar */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {/* Search */}
        <div className="relative flex-1 min-w-[200px]">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--color-text-secondary)]" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search tasks..."
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] py-2 pl-10 pr-4 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
          />
          {searching && (
            <Spinner
              size="sm"
              className="absolute right-3 top-1/2 -translate-y-1/2"
            />
          )}
          {searchQuery && !searching && (
            <button
              onClick={() => setSearchQuery('')}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
            >
              <X className="h-4 w-4" />
            </button>
          )}
        </div>

        {/* Sort */}
        <div className="relative" ref={sortRef}>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => setSortOpen(!sortOpen)}
          >
            <ArrowUpDown className="h-4 w-4" />
            {SORT_OPTIONS.find((o) => o.value === sortKey)?.label}
          </Button>
          {sortOpen && (
            <div className="absolute right-0 top-full z-50 mt-1 w-48 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] p-1 shadow-lg">
              {SORT_OPTIONS.map((opt) => (
                <button
                  key={opt.value}
                  onClick={() => {
                    setSortKey(opt.value);
                    setSortOpen(false);
                  }}
                  className={`flex w-full items-center rounded-md px-3 py-2 text-sm transition-colors ${
                    sortKey === opt.value
                      ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                      : 'text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)]'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Filter */}
        <Button
          variant={hasActiveFilters ? 'primary' : 'secondary'}
          size="sm"
          onClick={() => setFilterOpen(!filterOpen)}
        >
          <SlidersHorizontal className="h-4 w-4" />
          Filter
          {hasActiveFilters && (
            <span className="ml-1 rounded-full bg-white/20 px-1.5 text-xs">
              {priorityFilter.length +
                statusFilter.length +
                projectFilter.length +
                tagFilter.length}
            </span>
          )}
        </Button>

        {/* Show Completed Toggle */}
        <Button
          variant="secondary"
          size="sm"
          onClick={() => setShowCompleted((v) => !v)}
          title={
            showCompleted
              ? 'Currently showing completed and cancelled tasks. Click to hide.'
              : 'Currently hiding completed and cancelled tasks. Click to show.'
          }
        >
          {showCompleted ? (
            <EyeOff className="h-4 w-4" />
          ) : (
            <Eye className="h-4 w-4" />
          )}
          {showCompleted ? 'Hide Completed' : 'Show Completed'}
        </Button>

        {/* View Toggle */}
        <div className="flex rounded-lg border border-[var(--color-border)]">
          <button
            onClick={() => setViewMode('list')}
            className={`rounded-l-lg p-2 transition-colors ${
              viewMode === 'list'
                ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                : 'text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
            }`}
            title="List View"
          >
            <LayoutList className="h-4 w-4" />
          </button>
          <button
            onClick={() => setViewMode('grouped')}
            className={`rounded-r-lg p-2 transition-colors ${
              viewMode === 'grouped'
                ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                : 'text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
            }`}
            title="Grouped By Project"
          >
            <FolderKanban className="h-4 w-4" />
          </button>
        </div>

        {/* New Task */}
        <Button size="sm" onClick={handleNewTask}>
          <Plus className="h-4 w-4" />
          New Task
        </Button>
      </div>

      {/* Filter Panel */}
      {filterOpen && (
        <div className="mb-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
          <div className="mb-3 flex items-center justify-between">
            <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">
              Filters
            </h3>
            {hasActiveFilters && (
              <button
                onClick={clearFilters}
                className="text-xs text-[var(--color-accent)] hover:underline"
              >
                Clear All
              </button>
            )}
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {/* Priority */}
            <div>
              <label className="mb-1.5 block text-xs font-medium text-[var(--color-text-secondary)]">
                Priority
              </label>
              <div className="flex flex-wrap gap-1.5">
                {PRIORITY_FILTERS.map((pf) => (
                  <button
                    key={pf.value}
                    onClick={() =>
                      setPriorityFilter((prev) =>
                        prev.includes(pf.value)
                          ? prev.filter((v) => v !== pf.value)
                          : [...prev, pf.value],
                      )
                    }
                    className={`rounded-full border px-2.5 py-1 text-xs font-medium transition-colors ${
                      priorityFilter.includes(pf.value)
                        ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                        : 'border-[var(--color-border)] text-[var(--color-text-secondary)]'
                    }`}
                  >
                    {pf.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Status */}
            <div>
              <label className="mb-1.5 block text-xs font-medium text-[var(--color-text-secondary)]">
                Status
              </label>
              <div className="flex flex-wrap gap-1.5">
                {STATUS_FILTERS.map((sf) => (
                  <button
                    key={sf.value}
                    onClick={() =>
                      setStatusFilter((prev) =>
                        prev.includes(sf.value)
                          ? prev.filter((v) => v !== sf.value)
                          : [...prev, sf.value],
                      )
                    }
                    className={`rounded-full border px-2.5 py-1 text-xs font-medium transition-colors ${
                      statusFilter.includes(sf.value)
                        ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                        : 'border-[var(--color-border)] text-[var(--color-text-secondary)]'
                    }`}
                  >
                    {sf.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Project */}
            <div>
              <label className="mb-1.5 block text-xs font-medium text-[var(--color-text-secondary)]">
                Project
              </label>
              <div className="flex flex-wrap gap-1.5">
                {visibleProjects.map((p) => (
                  <button
                    key={p.id}
                    onClick={() =>
                      setProjectFilter((prev) =>
                        prev.includes(p.id)
                          ? prev.filter((v) => v !== p.id)
                          : [...prev, p.id],
                      )
                    }
                    className={`rounded-full border px-2.5 py-1 text-xs font-medium transition-colors ${
                      projectFilter.includes(p.id)
                        ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                        : 'border-[var(--color-border)] text-[var(--color-text-secondary)]'
                    }`}
                  >
                    {p.title}
                  </button>
                ))}
              </div>
            </div>

            {/* Tags */}
            {tags.length > 0 && (
              <div>
                <label className="mb-1.5 block text-xs font-medium text-[var(--color-text-secondary)]">
                  Tags
                </label>
                <div className="flex flex-wrap gap-1.5">
                  {tags.map((tag) => (
                    <button
                      key={tag.id}
                      onClick={() =>
                        setTagFilter((prev) =>
                          prev.includes(tag.id)
                            ? prev.filter((v) => v !== tag.id)
                            : [...prev, tag.id],
                        )
                      }
                      className={`inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs font-medium transition-colors ${
                        tagFilter.includes(tag.id)
                          ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                          : 'border-[var(--color-border)] text-[var(--color-text-secondary)]'
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
                </div>
              </div>
            )}

            {/* Due Date Range */}
            <div className="sm:col-span-2">
              <label className="mb-1.5 block text-xs font-medium text-[var(--color-text-secondary)]">
                Due Date Range
              </label>
              <div className="flex items-center gap-2">
                <input
                  type="date"
                  value={dueDateStart}
                  onChange={(e) => setDueDateStart(e.target.value)}
                  className="flex-1 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
                <span className="text-xs text-[var(--color-text-secondary)]">
                  to
                </span>
                <input
                  type="date"
                  value={dueDateEnd}
                  onChange={(e) => setDueDateEnd(e.target.value)}
                  className="flex-1 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Bulk Action Bar */}
      {hasSelection && (
        <div className="mb-4 flex items-center gap-2 rounded-lg border border-[var(--color-accent)]/30 bg-[var(--color-accent)]/5 px-4 py-2">
          <Checkbox
            checked={allSelected}
            indeterminate={hasSelection && !allSelected}
            onChange={handleSelectAll}
          />
          <span className="text-sm font-medium text-[var(--color-text-primary)]">
            {selectedIds.length} selected
          </span>
          <div className="ml-auto flex items-center gap-1.5">
            <Button variant="ghost" size="sm" onClick={handleBulkComplete}>
              <CheckCheck className="h-4 w-4" />
              Complete
            </Button>
            <div className="relative">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setBulkPriorityOpen(!bulkPriorityOpen)}
              >
                <Flag className="h-4 w-4" />
                Priority
              </Button>
              {bulkPriorityOpen && (
                <div className="absolute right-0 top-full z-50 mt-1 w-36 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] p-1 shadow-lg">
                  {PRIORITY_FILTERS.map((pf) => (
                    <button
                      key={pf.value}
                      onClick={() => handleBulkPriority(pf.value)}
                      className="flex w-full items-center rounded-md px-3 py-2 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)]"
                    >
                      {pf.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
            <div className="relative">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setBulkDateOpen(!bulkDateOpen)}
              >
                <CalendarDays className="h-4 w-4" />
                Due Date
              </Button>
              {bulkDateOpen && (
                <div className="absolute right-0 top-full z-50 mt-1 w-52 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3 shadow-lg">
                  <input
                    type="date"
                    value={bulkDate}
                    onChange={(e) => setBulkDate(e.target.value)}
                    className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                    autoFocus
                  />
                  <Button
                    size="sm"
                    className="mt-2 w-full"
                    onClick={handleBulkDate}
                    disabled={!bulkDate}
                  >
                    Apply
                  </Button>
                </div>
              )}
            </div>
            {/* Move to Project */}
            <div className="relative">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setBulkMoveOpen(!bulkMoveOpen)}
              >
                <FolderInput className="h-4 w-4" />
                Move
              </Button>
              {bulkMoveOpen && (
                <div className="absolute right-0 top-full z-50 mt-1 w-48 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] p-1 shadow-lg max-h-48 overflow-y-auto">
                  {visibleProjects.map((p) => (
                    <button
                      key={p.id}
                      onClick={() => handleBulkMove(p.id)}
                      className="flex w-full items-center rounded-md px-3 py-2 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)]"
                    >
                      {p.title}
                    </button>
                  ))}
                </div>
              )}
            </div>
            {/* Add Tags */}
            <div className="relative">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setBulkTagsOpen(!bulkTagsOpen)}
              >
                <Tags className="h-4 w-4" />
                Tags
              </Button>
              {bulkTagsOpen && (
                <div className="absolute right-0 top-full z-50 mt-1 w-48 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] p-2 shadow-lg max-h-48 overflow-y-auto">
                  {tags.length === 0 ? (
                    <p className="text-xs text-[var(--color-text-secondary)] px-2 py-1">
                      No tags available
                    </p>
                  ) : (
                    tags.map((tag) => (
                      <button
                        key={tag.id}
                        onClick={() => {
                          setBulkTagsOpen(false);
                          toast.success(`Tag "${tag.name}" applied to ${selectedIds.length} tasks`);
                        }}
                        className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)]"
                      >
                        <span
                          className="h-2.5 w-2.5 rounded-full"
                          style={{ backgroundColor: tag.color || 'var(--color-accent)' }}
                        />
                        {tag.name}
                      </button>
                    ))
                  )}
                </div>
              )}
            </div>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setDeleteConfirmOpen(true)}
            >
              <Trash2 className="h-4 w-4 text-red-500" />
              Delete
            </Button>
            <Button variant="ghost" size="sm" onClick={clearSelection}>
              <X className="h-4 w-4" />
            </Button>
          </div>
        </div>
      )}

      {/* Select All row */}
      {!hasSelection && sortedTasks.length > 0 && (
        <div className="mb-2 flex items-center gap-2 px-3">
          <Checkbox
            checked={false}
            onChange={handleSelectAll}
            label="Select All"
            className="text-xs text-[var(--color-text-secondary)]"
          />
        </div>
      )}

      {/* Task List */}
      {sortedTasks.length === 0 ? (
        <EmptyState
          icon={<CheckSquare className="h-8 w-8" />}
          title={searchQuery ? 'No Tasks Found' : 'No Tasks Yet'}
          description={
            searchQuery
              ? 'Try a different search term'
              : 'Create your first task to get started!'
          }
          actionLabel={searchQuery ? undefined : 'New Task'}
          onAction={searchQuery ? undefined : handleNewTask}
        />
      ) : viewMode === 'list' ? (
        <div
          className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)]"
          style={{ contentVisibility: 'auto' }}
        >
          <SortableTaskList
            tasks={sortedTasks}
            onReorder={(reordered) => setAllTasks(reordered)}
            onComplete={handleComplete}
            onUncomplete={handleUncomplete}
            onClick={handleTaskClick}
            onReschedule={handleReschedule}
            showProject
            showSelection
            projectMap={projectMap as Map<string, { title: string; color?: string }>}
            selectedTaskIds={selectedTaskIds}
            onToggleSelect={toggleTaskSelection}
            disabled={sortKey !== 'sort_order'}
          />
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {Array.from(groupedTasks.entries()).map(
            ([projId, projTasks]) => {
              const project = projectMap.get(projId);
              const isCollapsed = collapsedGroups.has(projId);
              return (
                <div
                  key={projId}
                  className="overflow-hidden rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)]"
                >
                  <button
                    onClick={() => toggleGroup(projId)}
                    className="flex w-full items-center gap-2 px-4 py-3 text-left hover:bg-[var(--color-bg-secondary)] transition-colors"
                  >
                    {isCollapsed ? (
                      <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" />
                    ) : (
                      <ChevronDown className="h-4 w-4 text-[var(--color-text-secondary)]" />
                    )}
                    <FolderKanban className="h-4 w-4 text-[var(--color-accent)]" />
                    <span className="text-sm font-semibold text-[var(--color-text-primary)]">
                      {project?.title || `Project #${projId}`}
                    </span>
                    <span className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-xs text-[var(--color-text-secondary)]">
                      {projTasks.length}
                    </span>
                  </button>
                  {!isCollapsed && (
                    <div className="px-1 pb-2">
                      {projTasks.map((task) => (
                        <TaskRow
                          key={task.id}
                          task={task}
                          selected={selectedTaskIds.has(task.id)}
                          onToggleSelect={() => toggleTaskSelection(task.id)}
                          onComplete={handleComplete}
                          onUncomplete={handleUncomplete}
                          onClick={handleTaskClick}
                          onReschedule={handleReschedule}
                          showSelection
                        />
                      ))}
                    </div>
                  )}
                </div>
              );
            },
          )}
        </div>
      )}

      {/* Task Editor */}
      <Suspense fallback={null}>
        {editorOpen && (
          <TaskEditor
            mode={createMode ? 'create' : 'edit'}
            onClose={() => {
              setEditorOpen(false);
              setSelectedTask(null);
              setCreateMode(false);
            }}
            onUpdate={() => loadData()}
          />
        )}
      </Suspense>

      {/* Bulk Delete Confirmation */}
      <ConfirmDialog
        isOpen={deleteConfirmOpen}
        onClose={() => setDeleteConfirmOpen(false)}
        onConfirm={handleBulkDelete}
        title="Delete Tasks"
        message={`Are you sure you want to delete ${selectedIds.length} task${selectedIds.length === 1 ? '' : 's'}? This action cannot be undone.`}
        confirmLabel="Delete All"
        variant="danger"
        loading={bulkDeleting}
      />
    </div>
  );
}
