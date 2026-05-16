import { useState, useRef, useEffect } from 'react';
import { Plus } from 'lucide-react';
import { toast } from 'sonner';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { parseQuickAdd } from '@/utils/nlp';
import { parseApi } from '@/api/parse';
import { TaskConfirmModal, type TaskConfirmDraft } from '@/components/shared/TaskConfirmModal';
import type { TaskPriority } from '@/types/task';

interface QuickCreateInputProps {
  date: string; // ISO date string (YYYY-MM-DD)
  time?: string; // HH:MM format (optional, for timeline)
  onCreated: () => void;
  onCancel: () => void;
  autoFocus?: boolean;
  className?: string;
}

export function QuickCreateInput({
  date,
  onCreated,
  onCancel,
  autoFocus = true,
  className = '',
}: QuickCreateInputProps) {
  const [title, setTitle] = useState('');
  const [isCreating, setIsCreating] = useState(false);
  const [pending, setPending] = useState<TaskConfirmDraft | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const { createTask } = useTaskStore();
  const { projects } = useProjectStore();
  const confirmTaskBeforeSave = useSettingsStore((s) => s.confirmTaskBeforeSave);

  useEffect(() => {
    if (autoFocus && inputRef.current) {
      inputRef.current.focus();
    }
  }, [autoFocus]);

  const buildDraft = async (raw: string): Promise<TaskConfirmDraft> => {
    // Always run the local regex parser — it extracts tags + recurrence
    // hints the backend response doesn't currently include. Then layer
    // the backend NLP result on top for the fields it owns (better date
    // / time / project / priority via Haiku). On any backend failure
    // (offline / AI off / 4xx / 5xx) the local result stands alone so
    // the user is never blocked. Matches Android's `parseRemote` shape.
    const local = parseQuickAdd(raw);
    try {
      const remote = await parseApi.parse({ text: raw });
      return {
        title: remote.title || local.title || raw,
        due_date: remote.due_date ?? local.dueDate ?? date,
        due_time: remote.due_time ?? local.dueTime ?? null,
        priority: remote.priority ?? local.priority ?? null,
        project_suggestion: remote.project_suggestion ?? local.project ?? null,
        tags: remote.tag_suggestions ?? local.tags ?? [],
        recurrence_hint: remote.recurrence_hint ?? local.recurrenceHint ?? null,
      };
    } catch {
      return {
        title: local.title || raw,
        due_date: local.dueDate ?? date,
        due_time: local.dueTime ?? null,
        priority: local.priority ?? null,
        project_suggestion: local.project ?? null,
        tags: local.tags ?? [],
        recurrence_hint: local.recurrenceHint ?? null,
      };
    }
  };

  const insertTask = async (draft: TaskConfirmDraft) => {
    const projectId = projects[0]?.id;
    if (!projectId) {
      toast.error('No project available. Create a project first.');
      return;
    }
    setIsCreating(true);
    try {
      // `TaskCreate.priority` is `TaskPriority | undefined` (1..4). The
      // confirm-modal carries a wider number (0..4 with 0 meaning "none"),
      // so coerce 0 → undefined before forwarding to the store.
      const priorityForStore =
        draft.priority && draft.priority >= 1 && draft.priority <= 4
          ? (draft.priority as TaskPriority)
          : undefined;
      await createTask(projectId, {
        title: draft.title,
        // QuickCreateInput is anchored to a specific calendar slot — if
        // the parse stripped the date we still want the click target to
        // win. Falling back to `date` here preserves the v1.9 behavior.
        due_date: draft.due_date ?? date,
        due_time: draft.due_time ?? undefined,
        priority: priorityForStore,
      });
      toast.success('Task created');
      setTitle('');
      onCreated();
    } catch {
      toast.error('Failed to create task');
    } finally {
      setIsCreating(false);
    }
  };

  const handleSubmit = async () => {
    const raw = title.trim();
    if (!raw || isCreating) return;
    const draft = await buildDraft(raw);
    if (confirmTaskBeforeSave) {
      setPending(draft);
    } else {
      await insertTask(draft);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleSubmit();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      onCancel();
    }
  };

  return (
    <>
      <div
        className={`flex items-center gap-1.5 rounded-md border border-[var(--color-accent)]/40 bg-[var(--color-bg-card)] px-2 py-1 ${className}`}
      >
        <Plus className="h-3 w-3 shrink-0 text-[var(--color-accent)]" />
        <input
          ref={inputRef}
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          onKeyDown={handleKeyDown}
          onBlur={() => {
            if (!title.trim() && !pending) onCancel();
          }}
          placeholder="New task..."
          disabled={isCreating}
          className="flex-1 border-none bg-transparent text-xs text-[var(--color-text-primary)] outline-none placeholder-[var(--color-text-secondary)]"
        />
      </div>

      {pending && (
        <TaskConfirmModal
          initial={pending}
          onCancel={() => setPending(null)}
          onSave={async (draft) => {
            setPending(null);
            await insertTask(draft);
          }}
        />
      )}
    </>
  );
}
