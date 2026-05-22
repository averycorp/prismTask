import { useEffect, useState, useMemo } from 'react';
import {
  FileText,
  Plus,
  Search,
  Play,
  Edit3,
  Trash2,
  Clock,
} from 'lucide-react';
import { toast } from 'sonner';
import { useTemplateStore } from '@/stores/templateStore';
import { useProjectStore } from '@/stores/projectStore';
import { useTagStore } from '@/stores/tagStore';
import { useTaskStore } from '@/stores/taskStore';
import { Spinner } from '@/components/ui/Spinner';
import { EmptyState } from '@/components/ui/EmptyState';
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { TemplateEditorModal } from './TemplateEditorModal';
import {
  HabitStarterList,
  ProjectStarterList,
} from './StarterTemplatesPanel';
import { Tabs } from '@/components/ui/Tabs';
import { PRIORITY_CONFIG } from '@/utils/priority';
import { useIsMobile } from '@/hooks/useMediaQuery';
import type { TaskTemplate } from '@/types/template';
import type { TaskPriority } from '@/types/task';
import { lazy, Suspense } from 'react';

const TaskEditor = lazy(() => import('@/features/tasks/TaskEditor'));

export function TemplateListScreen() {
  const { templates, isLoading, fetch, use: applyTemplate, remove } = useTemplateStore();
  const { fetchAllProjects } = useProjectStore();
  const { fetchTags } = useTagStore();
  const { setSelectedTask } = useTaskStore();
  const isMobile = useIsMobile();

  const [searchQuery, setSearchQuery] = useState('');
  const [activeTab, setActiveTab] = useState<'tasks' | 'habits' | 'projects'>(
    'tasks',
  );
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingTemplate, setEditingTemplate] = useState<TaskTemplate | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<TaskTemplate | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [usingTemplate, setUsingTemplate] = useState<string | null>(null);
  const [taskEditorOpen, setTaskEditorOpen] = useState(false);

  useEffect(() => {
    fetch();
    fetchAllProjects();
    fetchTags();
  }, [fetch, fetchAllProjects, fetchTags]);

  const filtered = useMemo(() => {
    if (!searchQuery.trim()) return templates;
    const q = searchQuery.toLowerCase();
    return templates.filter(
      (t) =>
        t.name.toLowerCase().includes(q) ||
        t.category?.toLowerCase().includes(q) ||
        t.description?.toLowerCase().includes(q),
    );
  }, [templates, searchQuery]);

  const builtIn = useMemo(() => filtered.filter((t) => t.is_built_in), [filtered]);
  const custom = useMemo(() => filtered.filter((t) => !t.is_built_in), [filtered]);

  const handleUse = async (template: TaskTemplate) => {
    setUsingTemplate(template.id);
    try {
      const result = await applyTemplate(template.id);
      toast.success(result.message || 'Task created from template');
      // Open the created task for editing
      const { fetchTask } = useTaskStore.getState();
      const task = await fetchTask(result.task_id);
      setSelectedTask(task);
      setTaskEditorOpen(true);
    } catch {
      toast.error('Failed to use template');
    } finally {
      setUsingTemplate(null);
    }
  };

  const handleEdit = (template: TaskTemplate) => {
    setEditingTemplate(template);
    setEditorOpen(true);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await remove(deleteTarget.id);
      toast.success('Template deleted');
    } catch {
      toast.error('Failed to delete template');
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
    }
  };

  const handleCreateNew = () => {
    setEditingTemplate(null);
    setEditorOpen(true);
  };

  const formatLastUsed = (dateStr: string | null): string => {
    if (!dateStr) return 'Never used';
    const d = new Date(dateStr);
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    const diffDays = Math.floor(diffMs / 86400000);
    if (diffDays === 0) return 'Used today';
    if (diffDays === 1) return 'Used yesterday';
    if (diffDays < 7) return `Used ${diffDays}d ago`;
    if (diffDays < 30) return `Used ${Math.floor(diffDays / 7)}w ago`;
    return `Used ${Math.floor(diffDays / 30)}mo ago`;
  };

  const renderCard = (template: TaskTemplate) => (
    <div
      key={template.id}
      className="group flex flex-col rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4 transition-all hover:border-[var(--color-accent)]/40 hover:shadow-md"
    >
      <div className="mb-2 flex items-start justify-between">
        <div className="flex items-center gap-2">
          <span className="text-2xl">{template.icon || '📋'}</span>
          <div>
            <h3 className="font-semibold text-[var(--color-text-primary)]">
              {template.name}
            </h3>
            {template.category && (
              <span className="text-xs text-[var(--color-text-secondary)]">
                {template.category}
              </span>
            )}
          </div>
        </div>
        {template.template_priority && (
          <span
            className="rounded-md px-1.5 py-0.5 text-xs font-medium"
            style={{
              color: PRIORITY_CONFIG[template.template_priority as TaskPriority]?.color,
              backgroundColor: PRIORITY_CONFIG[template.template_priority as TaskPriority]?.bgColor,
            }}
          >
            {PRIORITY_CONFIG[template.template_priority as TaskPriority]?.label}
          </span>
        )}
      </div>

      {template.description && (
        <p className="mb-3 line-clamp-2 text-xs text-[var(--color-text-secondary)]">
          {template.description}
        </p>
      )}

      <div className="mt-auto flex items-center justify-between pt-2">
        <div className="flex items-center gap-2 text-xs text-[var(--color-text-secondary)]">
          <Clock className="h-3 w-3" />
          <span>{formatLastUsed(template.last_used_at)}</span>
          {template.usage_count > 0 && (
            <span className="rounded-full bg-[var(--color-bg-secondary)] px-1.5 py-0.5">
              Used {template.usage_count}x
            </span>
          )}
        </div>
        <div className="flex gap-1 opacity-0 transition-opacity group-hover:opacity-100">
          <button
            onClick={() => handleEdit(template)}
            className="rounded-md p-1.5 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
            title="Edit"
          >
            <Edit3 className="h-3.5 w-3.5" />
          </button>
          {!template.is_built_in && (
            <button
              onClick={() => setDeleteTarget(template)}
              className="rounded-md p-1.5 text-[var(--color-text-secondary)] hover:bg-red-50 hover:text-red-500"
              title="Delete"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      </div>

      <Button
        size="sm"
        className="mt-3 w-full"
        onClick={() => handleUse(template)}
        loading={usingTemplate === template.id}
      >
        <Play className="h-3.5 w-3.5" />
        Use Template
      </Button>
    </div>
  );

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-20">
        <Spinner size="lg" text="Loading templates..." />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-5xl">
      {/* Header */}
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <FileText className="h-7 w-7 text-[var(--color-accent)]" />
          <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
            Templates
          </h1>
          {activeTab === 'tasks' && (
            <span className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-xs font-medium text-[var(--color-text-secondary)]">
              {templates.length}
            </span>
          )}
        </div>
        {activeTab === 'tasks' && (
          <Button onClick={handleCreateNew}>
            <Plus className="h-4 w-4" />
            Create Template
          </Button>
        )}
      </div>

      <Tabs
        tabs={[
          { key: 'tasks', label: 'Tasks' },
          { key: 'habits', label: 'Habits' },
          { key: 'projects', label: 'Projects' },
        ]}
        activeTab={activeTab}
        onChange={(k) => setActiveTab(k as 'tasks' | 'habits' | 'projects')}
        className="mb-5"
      />

      {activeTab === 'habits' && (
        <div className="mb-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3 text-xs text-[var(--color-text-secondary)]">
          Starter habits from the PrismTask library plus any templates
          you've saved. Tap Use to create a live habit on your account,
          or New habit template to author your own — stored per-user in
          Firestore.
        </div>
      )}
      {activeTab === 'projects' && (
        <div className="mb-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3 text-xs text-[var(--color-text-secondary)]">
          Starter project blueprints plus your own saved templates. Use
          scaffolds a project with the listed tasks. New project template
          opens an inline editor — stored per-user in Firestore.
        </div>
      )}
      {activeTab === 'habits' && <HabitStarterList />}
      {activeTab === 'projects' && <ProjectStarterList />}

      {activeTab === 'tasks' && (
      <>
      {/* Search */}
      <div className="relative mb-6">
        <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--color-text-secondary)]" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="Search templates..."
          className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] py-2 pl-10 pr-4 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        />
      </div>

      {templates.length === 0 && !searchQuery ? (
        <EmptyState
          icon={<FileText className="h-8 w-8" />}
          title="No Templates Yet"
          description="Create reusable task templates to save time on repetitive tasks."
          actionLabel="Create Template"
          onAction={handleCreateNew}
        />
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={<Search className="h-8 w-8" />}
          title="No Matching Templates"
          description="Try a different search term."
        />
      ) : (
        <>
          {/* Built-in Templates */}
          {builtIn.length > 0 && (
            <div className="mb-8">
              <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-[var(--color-text-secondary)]">
                Built-In Templates
              </h2>
              <div
                className={`grid gap-4 ${
                  isMobile ? 'grid-cols-1' : 'grid-cols-2 lg:grid-cols-3'
                }`}
              >
                {builtIn.map(renderCard)}
              </div>
            </div>
          )}

          {/* Custom Templates */}
          {custom.length > 0 && (
            <div>
              <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-[var(--color-text-secondary)]">
                Custom Templates
              </h2>
              <div
                className={`grid gap-4 ${
                  isMobile ? 'grid-cols-1' : 'grid-cols-2 lg:grid-cols-3'
                }`}
              >
                {custom.map(renderCard)}
              </div>
            </div>
          )}

          {/* Create New Card */}
          <div className={`mt-4 grid gap-4 ${isMobile ? 'grid-cols-1' : 'grid-cols-2 lg:grid-cols-3'}`}>
            <button
              onClick={handleCreateNew}
              className="flex flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed border-[var(--color-border)] p-8 text-[var(--color-text-secondary)] transition-colors hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]"
            >
              <Plus className="h-8 w-8" />
              <span className="text-sm font-medium">Create Template</span>
            </button>
          </div>
        </>
      )}
      </>
      )}

      {/* Template Editor Modal */}
      <TemplateEditorModal
        isOpen={editorOpen}
        onClose={() => {
          setEditorOpen(false);
          setEditingTemplate(null);
        }}
        template={editingTemplate}
      />

      {/* Task Editor (opened after using template) */}
      <Suspense fallback={null}>
        {taskEditorOpen && (
          <TaskEditor
            onClose={() => {
              setTaskEditorOpen(false);
              setSelectedTask(null);
            }}
            mode="edit"
          />
        )}
      </Suspense>

      {/* Delete Confirmation */}
      <ConfirmDialog
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleDelete}
        title="Delete Template"
        message={`Are you sure you want to delete "${deleteTarget?.name}"? This cannot be undone.`}
        confirmLabel="Delete"
        variant="danger"
        loading={deleting}
      />
    </div>
  );
}
