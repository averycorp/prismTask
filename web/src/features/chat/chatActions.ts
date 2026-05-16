/**
 * Action-chip dispatcher for AI Coach chat. Mirrors the Android
 * `ChatViewModel.executeAction` switch (`app/.../ChatViewModel.kt:418-447`)
 * for the 8 action types the backend can return.
 *
 * Extracted from `ChatScreen.tsx` (parity Batch 3 PR-3) so the dispatch
 * logic is unit-testable in isolation and reusable from any future chat
 * surface (e.g. coach-from-task in PR-5).
 */
import type { ChatActionPayload } from '@/types/chat';

export interface ChatActionDispatchDeps {
  updateTask: (
    taskId: string,
    data: Record<string, unknown>,
  ) => Promise<unknown>;
  completeTask: (taskId: string) => Promise<unknown>;
  uncompleteTask?: (taskId: string) => Promise<unknown>;
  deleteTask: (taskId: string) => Promise<unknown>;
  /** Read the current task to snapshot pre-mutation state for undo. */
  getTaskById?: (taskId: string) => { due_date?: string | null } | null;
  setPendingBatchCommand: (cmd: string | null) => void;
  navigate: (to: string) => void;
}

/**
 * Snackbar metadata for destructive chip actions. When `undoLabel` is
 * non-null, ChatScreen renders an Undo button on the success toast that
 * calls `undoAction`. Non-destructive actions (`start_timer`,
 * `create_task`, `breakdown`) leave it null. Mirrors Android
 * `ChatActionResult` (`ChatViewModel.kt:120-124`).
 */
export interface ChatActionResult {
  message: string;
  undoLabel?: string;
  undoAction?: () => Promise<void> | void;
}

/** User-visible label rendered on the chip. */
export function actionLabel(action: ChatActionPayload): string {
  switch (action.type) {
    case 'complete':
      return 'Mark Complete';
    case 'reschedule':
      switch (action.to) {
        case 'today':
          return 'Move to Today';
        case 'tomorrow':
          return 'Move to Tomorrow';
        case 'next_week':
          return 'Move to Next Week';
        default:
          return 'Reschedule';
      }
    case 'reschedule_batch':
      return `Reschedule ${action.task_ids?.length ?? ''} Tasks`.trim();
    case 'breakdown':
      return 'Break It Down';
    case 'archive':
      return 'Just Drop It';
    case 'start_timer': {
      const m = action.minutes;
      if (m != null && m >= 1 && m <= 480) {
        return `Start a ${m}-Min Timer`;
      }
      return 'Start a Timer';
    }
    case 'create_task': {
      const title = action.title?.trim();
      return title ? `Add Task: ${title}` : 'Add Task';
    }
    case 'batch_command':
      return 'Preview Batch';
    default:
      return (
        action.type.charAt(0).toUpperCase() + action.type.slice(1)
      ).replace(/_/g, ' ');
  }
}

/**
 * Stable signature for in-flight dedup. Returns null when the payload is
 * missing the minimum fields needed to act (e.g. a `complete` chip with no
 * `task_id`).
 */
export function actionSignature(action: ChatActionPayload): string | null {
  switch (action.type) {
    case 'reschedule_batch': {
      const ids = action.task_ids?.filter(Boolean) ?? [];
      if (ids.length === 0) return null;
      return `reschedule_batch:${[...ids].sort().join(',')}`;
    }
    case 'create_task':
      return `create_task:${action.title ?? ''}:${action.due ?? ''}`;
    case 'batch_command': {
      const cmd = action.command_text?.trim();
      if (!cmd) return null;
      return `batch_command:${cmd.toLowerCase()}`;
    }
    case 'breakdown': {
      if (!action.task_id) return null;
      return `breakdown:${action.task_id}`;
    }
    case 'complete':
    case 'reschedule':
    case 'archive': {
      if (!action.task_id) return null;
      return `${action.type}:${action.task_id}`;
    }
    case 'start_timer':
      return `start_timer:${action.minutes ?? ''}`;
    default:
      return `${action.type}:${action.task_id ?? ''}`;
  }
}

/**
 * Convert the backend's relative date keyword (`today` / `tomorrow` /
 * `next_week`) or absolute ISO date into a YYYY-MM-DD string suitable
 * for `task.due_date`. Returns null for unknown inputs.
 */
export function resolveDateIsoForChat(
  to: string | null | undefined,
): string | null {
  if (!to) return null;
  const now = new Date();
  const fmt = (d: Date): string => {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const da = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${da}`;
  };
  if (to === 'today') return fmt(now);
  if (to === 'tomorrow') {
    const tm = new Date(now);
    tm.setDate(now.getDate() + 1);
    return fmt(tm);
  }
  if (to === 'next_week') {
    const nw = new Date(now);
    nw.setDate(now.getDate() + 7);
    return fmt(nw);
  }
  if (/^\d{4}-\d{2}-\d{2}$/.test(to)) return to;
  return null;
}

/**
 * Execute the action. Returns a `ChatActionResult` for the chip toast or
 * `null` when no toast should be surfaced (e.g. `batch_command` navigates
 * away — BatchPreviewScreen owns the next user-visible signal).
 *
 * Destructive ops (`complete`, `reschedule`, `reschedule_batch`) carry a
 * non-null `undoAction` so the ChatScreen toast renders an Undo button —
 * parity with Android `ChatViewModel.handle*` (`ChatViewModel.kt:535-620`).
 * `archive` maps to `deleteTask` on web (no archive flag yet) which is not
 * reversible by the task store, so we omit Undo there.
 */
export async function executeChatAction(
  action: ChatActionPayload,
  deps: ChatActionDispatchDeps,
): Promise<ChatActionResult | null> {
  switch (action.type) {
    case 'complete': {
      if (!action.task_id) return null;
      const taskId = action.task_id;
      await deps.completeTask(taskId);
      return {
        message: 'Task Completed',
        undoLabel: deps.uncompleteTask ? 'Undo' : undefined,
        undoAction: deps.uncompleteTask
          ? () => {
              void deps.uncompleteTask!(taskId);
            }
          : undefined,
      };
    }
    case 'reschedule': {
      if (!action.task_id) return null;
      const taskId = action.task_id;
      const originalDue = deps.getTaskById?.(taskId)?.due_date ?? null;
      const due = resolveDateIsoForChat(action.to);
      await deps.updateTask(taskId, { due_date: due });
      return {
        message: 'Task Rescheduled',
        undoLabel: 'Undo',
        undoAction: () => {
          void deps.updateTask(taskId, { due_date: originalDue });
        },
      };
    }
    case 'reschedule_batch': {
      const ids = action.task_ids?.filter(Boolean) ?? [];
      if (ids.length === 0) return null;
      const due = resolveDateIsoForChat(action.to);
      // Snapshot pre-mutation due dates BEFORE we mutate so Undo can
      // restore each task even if some succeed and some fail. Mirrors
      // Android `handleRescheduleBatch` (`ChatViewModel.kt:557-601`).
      const originalDues = new Map<string, string | null>();
      for (const id of ids) {
        originalDues.set(id, deps.getTaskById?.(id)?.due_date ?? null);
      }
      let succeeded = 0;
      let failed = 0;
      for (const id of ids) {
        try {
          await deps.updateTask(id, { due_date: due });
          succeeded += 1;
        } catch {
          failed += 1;
        }
      }
      const message =
        failed === 0
          ? `${succeeded} Tasks Rescheduled`
          : succeeded === 0
          ? 'Reschedule Failed'
          : `Rescheduled ${succeeded} of ${ids.length} Tasks (${failed} Failed)`;
      // Only offer Undo when at least one task moved.
      if (succeeded === 0) return { message };
      return {
        message,
        undoLabel: 'Undo',
        undoAction: async () => {
          for (const id of ids) {
            try {
              await deps.updateTask(id, { due_date: originalDues.get(id) ?? null });
            } catch {
              // Swallow per-task failures so one bad row doesn't abort the
              // rest of the rollback.
            }
          }
        },
      };
    }
    case 'archive': {
      if (!action.task_id) return null;
      // Web has no explicit archive flag; deletion is the equivalent
      // user-visible outcome. deleteTask is not undoable through the task
      // store on web (Firestore delete is permanent), so no Undo button.
      await deps.deleteTask(action.task_id);
      return { message: 'Task Archived' };
    }
    case 'batch_command': {
      const cmd = action.command_text?.trim();
      if (!cmd) return null;
      // Mirrors Android `ChatViewModel.handleBatchCommand` —
      // `app/.../ChatViewModel.kt:577-581`. Hand the natural-language
      // phrasing off to BatchPreviewScreen, which calls
      // `/api/v1/ai/batch-parse` to resolve a previewable plan.
      deps.setPendingBatchCommand(cmd);
      deps.navigate('/batch/preview');
      return null;
    }
    case 'start_timer': {
      const minutes = action.minutes;
      deps.navigate('/pomodoro');
      const label =
        minutes != null && minutes >= 1 && minutes <= 480
          ? `Starting Timer (${minutes} min)`
          : 'Timer Started';
      return { message: label };
    }
    case 'create_task': {
      const title = action.title?.trim();
      if (!title) return null;
      // Wiring against `taskStore.createTask` requires a `project_id`
      // anchor — full wiring is deferred to PR-5. Surface a toast so the
      // user still sees the AI's suggestion.
      return {
        message: `AI suggested a new task: ${title}. Add it manually for now.`,
      };
    }
    case 'breakdown': {
      const subs = action.subtasks?.filter((s) => s.trim()) ?? [];
      if (subs.length === 0) return null;
      return {
        message: `AI suggested ${subs.length} subtasks. Open the task to add them.`,
      };
    }
    default:
      return null;
  }
}
