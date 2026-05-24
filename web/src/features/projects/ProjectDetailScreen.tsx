import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  FolderKanban,
  ArrowLeft,
  Plus,
  Edit2,
  Trash2,
  CheckSquare,
  Map as MapIcon,
} from 'lucide-react';
import { toast } from 'sonner';
import { useProjectStore } from '@/stores/projectStore';
import { useTaskStore } from '@/stores/taskStore';
import * as firestoreTasks from '@/api/firestore/tasks';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { TaskRow } from '@/components/shared/TaskRow';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { Spinner } from '@/components/ui/Spinner';
import { EmptyState } from '@/components/ui/EmptyState';
import { ProgressBar } from '@/components/ui/ProgressBar';
import type { Task } from '@/types/task';
import { lazy, Suspense } from 'react';

const TaskEditor = lazy(() => import('@/features/tasks/TaskEditor'));

export function ProjectDetailScreen() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const projectId = id!;

  const {
    selectedProject,
    fetchProject,
    updateProject,
    deleteProject,
  } = useProjectStore();

  const {
    completeTask,
    uncompleteTask,
    updateTask,
    setSelectedTask,
  } = useTaskStore();

  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [editorOpen, setEditorOpen] = useState(false);
  const [createMode, setCreateMode] = useState(false);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);

  // Edit form
  const [editTitle, setEditTitle] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [editStatus, setEditStatus] = useState('active');

  const project = selectedProject;

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [, projTasks] = await Promise.all([
        fetchProject(projectId),
        firestoreTasks.getTasksByProject(getFirebaseUid(), projectId),
      ]);
      setTasks(projTasks);
    } catch {
      toast.error('Failed to load project');
    } finally {
      setLoading(false);
    }
  }, [projectId, fetchProject]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load project detail on mount and when projectId changes
    loadData();
  }, [loadData]);

  useEffect(() => {
    if (project) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- form-init: seed editor buffer from loaded project (fetched async, can't initialize useState)
      setEditTitle(project.title);
      setEditDescription(project.description || '');
      setEditStatus(project.status);
    }
  }, [project]);

  const handleComplete = useCallback(
    async (taskId: string) => {
      try {
        await completeTask(taskId);
        setTasks((prev) =>
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
        setTasks((prev) =>
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
        setTasks((prev) =>
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

  const handleSaveProject = async () => {
    try {
      await updateProject(projectId, {
        title: editTitle,
        description: editDescription || undefined,
        status: editStatus as 'active' | 'completed' | 'on_hold' | 'archived',
      });
      toast.success('Project updated');
      setEditModalOpen(false);
      loadData();
    } catch {
      toast.error('Failed to update project');
    }
  };

  const handleDeleteProject = async () => {
    setDeleting(true);
    try {
      await deleteProject(projectId);
      toast.success('Project deleted');
      navigate('/projects');
    } catch {
      toast.error('Failed to delete project');
    } finally {
      setDeleting(false);
    }
  };

  const totalTasks = tasks.length;
  const completedTasks = tasks.filter((t) => t.status === 'done').length;
  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-4xl">
      {/* Back button */}
      <button
        onClick={() => navigate('/projects')}
        className="mb-4 flex items-center gap-1 text-sm text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)] transition-colors"
      >
        <ArrowLeft className="h-4 w-4" />
        All Projects
      </button>

      {/* Project Header */}
      {project && (
        <div className="mb-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-6">
          <div className="flex items-start justify-between">
            <div className="flex items-center gap-3">
              <FolderKanban className="h-8 w-8 text-[var(--color-accent)]" />
              <div>
                <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
                  {project.title}
                </h1>
                {project.description && (
                  <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
                    {project.description}
                  </p>
                )}
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => navigate(`/projects/${projectId}/roadmap`)}
              >
                <MapIcon className="h-4 w-4" />
                Roadmap
              </Button>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setEditModalOpen(true)}
              >
                <Edit2 className="h-4 w-4" />
                Edit
              </Button>
              <Button
                variant="danger"
                size="sm"
                onClick={() => setDeleteOpen(true)}
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
          </div>

          {/* Progress */}
          <div className="mt-4">
            <ProgressBar
              value={completedTasks}
              max={totalTasks || 1}
              label={`${completedTasks} of ${totalTasks} tasks completed`}
              showPercentage
            />
          </div>
        </div>
      )}

      {/* Task List Header */}
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-lg font-semibold text-[var(--color-text-primary)]">
          Tasks
        </h2>
        <Button size="sm" onClick={handleNewTask}>
          <Plus className="h-4 w-4" />
          Add Task
        </Button>
      </div>

      {/* Tasks */}
      {tasks.length === 0 ? (
        <EmptyState
          icon={<CheckSquare className="h-8 w-8" />}
          title="No Tasks Yet"
          description="Add your first task to this project."
          actionLabel="Add Task"
          onAction={handleNewTask}
        />
      ) : (
        <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)]">
          {tasks
            .sort((a, b) => {
              if (a.due_date && b.due_date) {
                const cmp = a.due_date.localeCompare(b.due_date);
                if (cmp !== 0) return cmp;
              } else if (a.due_date) {
                return -1;
              } else if (b.due_date) {
                return 1;
              }
              return (b.urgency_score ?? 0) - (a.urgency_score ?? 0);
            })
            .map((task) => (
              <TaskRow
                key={task.id}
                task={task}
                onComplete={handleComplete}
                onUncomplete={handleUncomplete}
                onClick={handleTaskClick}
                onReschedule={handleReschedule}
              />
            ))}
        </div>
      )}

      {/* Task Editor */}
      <Suspense fallback={null}>
        {editorOpen && (
          <TaskEditor
            mode={createMode ? 'create' : 'edit'}
            defaultProjectId={projectId}
            onClose={() => {
              setEditorOpen(false);
              setSelectedTask(null);
              setCreateMode(false);
            }}
            onUpdate={() => loadData()}
          />
        )}
      </Suspense>

      {/* Edit Project Modal */}
      <Modal
        isOpen={editModalOpen}
        onClose={() => setEditModalOpen(false)}
        title="Edit Project"
        size="sm"
        footer={
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setEditModalOpen(false)}>
              Cancel
            </Button>
            <Button onClick={handleSaveProject} disabled={!editTitle.trim()}>
              Save
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
              value={editTitle}
              onChange={(e) => setEditTitle(e.target.value)}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-[var(--color-text-primary)]">
              Description
            </label>
            <textarea
              value={editDescription}
              onChange={(e) => setEditDescription(e.target.value)}
              rows={2}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-[var(--color-text-primary)]">
              Status
            </label>
            <select
              value={editStatus}
              onChange={(e) => setEditStatus(e.target.value)}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            >
              <option value="active">Active</option>
              <option value="completed">Completed</option>
              <option value="on_hold">On Hold</option>
              <option value="archived">Archived</option>
            </select>
          </div>
        </div>
      </Modal>

      {/* Delete Confirm */}
      <ConfirmDialog
        isOpen={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        onConfirm={handleDeleteProject}
        title="Delete Project"
        message="Are you sure? All tasks in this project will be permanently deleted."
        confirmLabel="Delete"
        variant="danger"
        loading={deleting}
      />
    </div>
  );
}
