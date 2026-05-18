import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Archive, ArchiveRestore, FolderKanban, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { useProjectStore } from '@/stores/projectStore';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { Spinner } from '@/components/ui/Spinner';
import { EmptyState } from '@/components/ui/EmptyState';
import type { Project } from '@/types/project';

export function ProjectListScreen() {
  const navigate = useNavigate();
  const {
    projects,
    fetchAllProjects,
    createProject,
    deleteProject,
    archiveProject,
    reopenProject,
  } = useProjectStore();

  const [loading, setLoading] = useState(true);

  const [projectModalOpen, setProjectModalOpen] = useState(false);
  const [projectTitle, setProjectTitle] = useState('');
  const [projectDescription, setProjectDescription] = useState('');

  const [deleteTarget, setDeleteTarget] = useState<Project | null>(null);

  const loadData = useCallback(async () => {
    try {
      await fetchAllProjects();
    } finally {
      setLoading(false);
    }
  }, [fetchAllProjects]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const openNewProject = () => {
    setProjectTitle('');
    setProjectDescription('');
    setProjectModalOpen(true);
  };

  const handleSaveProject = async () => {
    if (!projectTitle.trim()) return;
    try {
      await createProject('', {
        title: projectTitle,
        description: projectDescription || undefined,
      });
      toast.success('Project Created');
      setProjectModalOpen(false);
      setProjectTitle('');
      setProjectDescription('');
    } catch {
      toast.error('Failed to create project');
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteProject(deleteTarget.id);
      toast.success('Project Deleted');
      setDeleteTarget(null);
    } catch {
      toast.error('Failed to delete project');
    }
  };

  const handleArchive = async (project: Project) => {
    try {
      await archiveProject(project.id);
      toast.success('Project Archived');
    } catch {
      toast.error('Failed to archive project');
    }
  };

  const handleReopen = async (project: Project) => {
    try {
      await reopenProject(project.id);
      toast.success('Project Reopened');
    } catch {
      toast.error('Failed to reopen project');
    }
  };

  const activeProjects = projects.filter((p) => p.status !== 'archived');
  const archivedProjects = projects.filter((p) => p.status === 'archived');

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <FolderKanban className="h-7 w-7 text-[var(--color-accent)]" />
          <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
            Projects
          </h1>
        </div>
        <Button size="sm" onClick={openNewProject}>
          <Plus className="h-4 w-4" />
          New Project
        </Button>
      </div>

      {projects.length === 0 ? (
        <EmptyState
          icon={<FolderKanban className="h-8 w-8" />}
          title="No Projects Yet"
          description="Create a project to group related tasks together."
          actionLabel="New Project"
          onAction={openNewProject}
        />
      ) : (
        <div className="flex flex-col gap-6">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {activeProjects.map((project) => (
              <ProjectCard
                key={project.id}
                project={project}
                onClick={() => navigate(`/projects/${project.id}`)}
                onDelete={() => setDeleteTarget(project)}
                onArchive={() => handleArchive(project)}
                onReopen={() => handleReopen(project)}
              />
            ))}
            <button
              onClick={openNewProject}
              className="flex min-h-[120px] items-center justify-center rounded-lg border-2 border-dashed border-[var(--color-border)] text-[var(--color-text-secondary)] transition-colors hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]"
            >
              <div className="flex flex-col items-center gap-1">
                <Plus className="h-6 w-6" />
                <span className="text-xs font-medium">New Project</span>
              </div>
            </button>
          </div>

          {archivedProjects.length > 0 && (
            <details className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)]">
              <summary className="cursor-pointer px-4 py-2 text-sm text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]">
                Archived ({archivedProjects.length})
              </summary>
              <div className="grid grid-cols-1 gap-3 p-4 sm:grid-cols-2 lg:grid-cols-3">
                {archivedProjects.map((project) => (
                  <ProjectCard
                    key={project.id}
                    project={project}
                    onClick={() => navigate(`/projects/${project.id}`)}
                    onDelete={() => setDeleteTarget(project)}
                    onArchive={() => handleArchive(project)}
                    onReopen={() => handleReopen(project)}
                  />
                ))}
              </div>
            </details>
          )}
        </div>
      )}

      <Modal
        isOpen={projectModalOpen}
        onClose={() => setProjectModalOpen(false)}
        title="New Project"
        size="sm"
        footer={
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setProjectModalOpen(false)}>
              Cancel
            </Button>
            <Button
              onClick={handleSaveProject}
              disabled={!projectTitle.trim()}
            >
              Create
            </Button>
          </div>
        }
      >
        <div className="flex flex-col gap-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-[var(--color-text-primary)]">
              Title
            </label>
            <input
              type="text"
              value={projectTitle}
              onChange={(e) => setProjectTitle(e.target.value)}
              placeholder="Project name..."
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
              autoFocus
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-[var(--color-text-primary)]">
              Description
            </label>
            <textarea
              value={projectDescription}
              onChange={(e) => setProjectDescription(e.target.value)}
              placeholder="Optional description..."
              rows={2}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            />
          </div>
        </div>
      </Modal>

      <ConfirmDialog
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleDelete}
        title="Delete Project"
        message={`Are you sure you want to delete "${deleteTarget?.title}"? All tasks in this project will be deleted.`}
        confirmLabel="Delete"
        variant="danger"
      />
    </div>
  );
}

function ProjectCard({
  project,
  onClick,
  onDelete,
  onArchive,
  onReopen,
}: {
  project: Project;
  onClick: () => void;
  onDelete: () => void;
  onArchive: () => void;
  onReopen: () => void;
}) {
  const statusColors: Record<string, string> = {
    active: '#22c55e',
    completed: '#3b82f6',
    on_hold: '#f59e0b',
    archived: '#6b7280',
  };
  const statusLabel =
    project.status.charAt(0).toUpperCase() +
    project.status.slice(1).replace('_', ' ');
  const isArchived = project.status === 'archived';

  return (
    <div
      className="group relative flex cursor-pointer flex-col rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-primary)] p-4 transition-colors hover:border-[var(--color-accent)]/30 hover:bg-[var(--color-bg-secondary)]"
      onClick={onClick}
    >
      <div
        className="absolute left-0 top-0 h-full w-1 rounded-l-lg"
        style={{ backgroundColor: project.color || 'var(--color-accent)' }}
      />

      <div className="flex items-start justify-between gap-2">
        <h4 className="truncate pr-2 text-sm font-semibold text-[var(--color-text-primary)]">
          {project.title}
        </h4>
        <span
          className="shrink-0 rounded-full px-2 py-0.5 text-xs font-medium"
          style={{
            color: statusColors[project.status] || '#6b7280',
            backgroundColor: `${statusColors[project.status] || '#6b7280'}15`,
          }}
        >
          {statusLabel}
        </span>
      </div>

      {project.description && (
        <p className="mt-1 line-clamp-2 text-xs text-[var(--color-text-secondary)]">
          {project.description}
        </p>
      )}

      <div className="mt-auto flex items-center gap-3 pt-3">
        {isArchived ? (
          <button
            onClick={(e) => {
              e.stopPropagation();
              onReopen();
            }}
            aria-label="Reopen project"
            className="flex items-center gap-1 text-xs text-[var(--color-text-secondary)] opacity-0 transition-all hover:text-[var(--color-accent)] group-hover:opacity-100"
          >
            <ArchiveRestore className="h-3 w-3" />
            Reopen
          </button>
        ) : (
          <button
            onClick={(e) => {
              e.stopPropagation();
              onArchive();
            }}
            aria-label="Archive project"
            className="flex items-center gap-1 text-xs text-[var(--color-text-secondary)] opacity-0 transition-all hover:text-[var(--color-accent)] group-hover:opacity-100"
          >
            <Archive className="h-3 w-3" />
            Archive
          </button>
        )}
        <button
          onClick={(e) => {
            e.stopPropagation();
            onDelete();
          }}
          aria-label="Delete project"
          className="flex items-center gap-1 text-xs text-[var(--color-text-secondary)] opacity-0 transition-all hover:text-red-500 group-hover:opacity-100"
        >
          <Trash2 className="h-3 w-3" />
          Delete
        </button>
      </div>
    </div>
  );
}
