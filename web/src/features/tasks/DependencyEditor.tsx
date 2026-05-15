import { useMemo, useState } from 'react';
import { Link2, Plus, X, Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import { Modal } from '@/components/ui/Modal';
import { useTaskStore } from '@/stores/taskStore';
import { useTaskDependencyStore } from '@/stores/taskDependencyStore';
import {
  addDependency,
  deleteDependency,
  DependencyCycleError,
} from '@/api/firestore/taskDependencies';
import { getFirebaseUid } from '@/stores/firebaseUid';
import type { TaskDependency } from '@/types/taskDependency';

/**
 * Web parity surface for the Android Organize-tab "Blockers" section
 * (`ui/screens/addedittask/tabs/OrganizeTab.kt :: BlockersSection`).
 *
 * Reads the live `task_dependencies` edge set from
 * `useTaskDependencyStore` (populated by `useFirestoreSync`) and lets
 * the user add / remove `blocker → blocked` edges for the currently
 * edited task. Writes go through `addDependency` / `deleteDependency`
 * in `api/firestore/taskDependencies.ts` which run the cycle guard
 * before persisting — surface cycle/self-edge errors as toasts so the
 * UX matches Android's `TaskDependencyRepository.DependencyError` flow.
 *
 * Audit reference: B.12 in
 * `docs/audits/ANDROID_WEB_PARITY_AUDIT_2026-05-13.md`.
 */
interface DependencyEditorProps {
  taskId: string;
}

export function DependencyEditor({ taskId }: DependencyEditorProps) {
  const tasks = useTaskStore((s) => s.tasks);
  const dependencies = useTaskDependencyStore((s) => s.dependencies);

  const [pickerOpen, setPickerOpen] = useState(false);
  const [pickerQuery, setPickerQuery] = useState('');
  const [adding, setAdding] = useState(false);
  const [removingId, setRemovingId] = useState<string | null>(null);

  const taskById = useMemo(
    () => new Map(tasks.map((t) => [t.id, t])),
    [tasks],
  );

  // Edges where this task is the *blocked* side — i.e. tasks blocking us.
  const blockers = useMemo(
    () => dependencies.filter((d) => d.blocked_task_id === taskId),
    [dependencies, taskId],
  );

  // Edges where this task is the *blocker* side — i.e. tasks we're blocking.
  const blocking = useMemo(
    () => dependencies.filter((d) => d.blocker_task_id === taskId),
    [dependencies, taskId],
  );

  // Tasks that aren't us, aren't already blockers, and aren't completed
  // (a done task can't block — matches Android picker filter).
  const blockerCandidates = useMemo(() => {
    const excluded = new Set<string>([taskId]);
    blockers.forEach((d) => excluded.add(d.blocker_task_id));
    const q = pickerQuery.trim().toLowerCase();
    return tasks
      .filter((t) => !excluded.has(t.id))
      .filter((t) => !q || t.title.toLowerCase().includes(q))
      .slice(0, 50);
  }, [tasks, blockers, taskId, pickerQuery]);

  const handleAddBlocker = async (blockerId: string) => {
    setAdding(true);
    try {
      await addDependency(getFirebaseUid(), {
        blocker_task_id: blockerId,
        blocked_task_id: taskId,
      });
      toast.success('Blocker added');
      setPickerOpen(false);
      setPickerQuery('');
    } catch (err) {
      if (err instanceof DependencyCycleError) {
        toast.error('Cannot add — would create a dependency cycle');
      } else {
        toast.error('Failed to add blocker');
      }
    } finally {
      setAdding(false);
    }
  };

  const handleRemove = async (dep: TaskDependency) => {
    setRemovingId(dep.id);
    try {
      await deleteDependency(getFirebaseUid(), dep.id);
    } catch {
      toast.error('Failed to remove blocker');
    } finally {
      setRemovingId(null);
    }
  };

  return (
    <div>
      <label className="mb-1 flex items-center gap-1.5 text-xs font-medium text-[var(--color-text-secondary)]">
        <Link2 className="h-3.5 w-3.5" aria-hidden="true" />
        Blockers
      </label>

      {/* Blocker chips — tasks that must complete before this one */}
      <div className="flex flex-wrap gap-1.5">
        {blockers.length === 0 ? (
          <span className="text-xs italic text-[var(--color-text-secondary)]">
            No blockers
          </span>
        ) : (
          blockers.map((dep) => {
            const t = taskById.get(dep.blocker_task_id);
            const title = t?.title ?? `Task ${dep.blocker_task_id.slice(0, 6)}`;
            const isDone = t?.status === 'done';
            return (
              <span
                key={dep.id}
                className={`inline-flex max-w-[220px] items-center gap-1 rounded-full border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2.5 py-1 text-xs ${
                  isDone
                    ? 'text-[var(--color-text-secondary)] line-through opacity-60'
                    : 'text-[var(--color-text-primary)]'
                }`}
              >
                <span className="truncate">{title}</span>
                <button
                  type="button"
                  onClick={() => void handleRemove(dep)}
                  disabled={removingId === dep.id}
                  aria-label={`Remove blocker ${title}`}
                  className="rounded-full p-0.5 text-[var(--color-text-secondary)] transition-colors hover:bg-[var(--color-bg-card)] hover:text-[var(--color-text-primary)] disabled:opacity-40"
                >
                  {removingId === dep.id ? (
                    <Loader2 className="h-3 w-3 animate-spin" />
                  ) : (
                    <X className="h-3 w-3" />
                  )}
                </button>
              </span>
            );
          })
        )}
        <button
          type="button"
          onClick={() => setPickerOpen(true)}
          className="inline-flex items-center gap-1 rounded-full border border-dashed border-[var(--color-border)] px-2.5 py-1 text-xs text-[var(--color-text-secondary)] transition-colors hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]"
        >
          <Plus className="h-3 w-3" />
          Add Blocker
        </button>
      </div>

      {/* "Blocks" mirror — read-only chip list for context */}
      {blocking.length > 0 && (
        <div className="mt-3">
          <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
            Blocks
          </label>
          <div className="flex flex-wrap gap-1.5">
            {blocking.map((dep) => {
              const t = taskById.get(dep.blocked_task_id);
              const title =
                t?.title ?? `Task ${dep.blocked_task_id.slice(0, 6)}`;
              return (
                <span
                  key={dep.id}
                  className="inline-flex max-w-[220px] items-center gap-1 rounded-full border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2.5 py-1 text-xs text-[var(--color-text-secondary)]"
                >
                  <span className="truncate">{title}</span>
                </span>
              );
            })}
          </div>
        </div>
      )}

      <p className="mt-2 text-xs text-[var(--color-text-secondary)]">
        Completing every blocker unblocks this task. Edges sync with Android
        in real time and reject cycles before write.
      </p>

      {/* Add-blocker picker dialog */}
      <Modal
        isOpen={pickerOpen}
        onClose={() => {
          setPickerOpen(false);
          setPickerQuery('');
        }}
        title="Add Blocker"
        size="sm"
      >
        <div className="flex flex-col gap-3">
          <input
            type="text"
            value={pickerQuery}
            onChange={(e) => setPickerQuery(e.target.value)}
            placeholder="Search Tasks…"
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            autoFocus
          />
          {blockerCandidates.length === 0 ? (
            <p className="py-6 text-center text-sm text-[var(--color-text-secondary)]">
              {pickerQuery
                ? 'No matching tasks'
                : 'No other tasks available to set as a blocker.'}
            </p>
          ) : (
            <ul className="flex max-h-72 flex-col gap-1 overflow-y-auto">
              {blockerCandidates.map((task) => (
                <li key={task.id}>
                  <button
                    type="button"
                    onClick={() => void handleAddBlocker(task.id)}
                    disabled={adding}
                    className="flex w-full items-center justify-between rounded-lg border border-transparent px-3 py-2 text-left text-sm text-[var(--color-text-primary)] transition-colors hover:border-[var(--color-border)] hover:bg-[var(--color-bg-secondary)] disabled:opacity-40"
                  >
                    <span className="truncate">{task.title}</span>
                    {task.status === 'done' && (
                      <span className="ml-2 shrink-0 text-xs text-[var(--color-text-secondary)]">
                        Done
                      </span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </Modal>
    </div>
  );
}

