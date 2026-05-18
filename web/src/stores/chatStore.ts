import { create } from 'zustand';
import { toast } from 'sonner';
import { aiChat, aiChatHistory } from '@/api/ai/chat';
import type {
  ChatActionPayload,
  ChatHistoryEntry,
  ChatMessageRecord,
  ChatRole,
  ChatTaskContext,
} from '@/types/chat';
import { useProjectStore } from '@/stores/projectStore';
import type { Task } from '@/types/task';

const MAX_HISTORY_PAIRS = 6;
const DISCLOSURE_FLAG_KEY = 'prismtask.chat.disclosureShownV3';
const CLEAR_SKIP_FLAG_KEY = 'prismtask.chat.clearSkipConfirmation';
const DEFAULT_HISTORY_LIMIT = 200;

/**
 * Local-rendered chat message. Mirrors Android `ChatMessage` (Repository).
 * `id` is the server-assigned PK when available; otherwise a fresh UUID
 * (for the brief pre-response window).
 */
export interface ChatUiMessage {
  id: string;
  conversationId: string;
  role: ChatRole;
  text: string;
  actions: ChatActionPayload[];
  createdAt: string;
}

interface ChatState {
  conversationId: string;
  conversationDate: string; // YYYY-MM-DD of current conversation
  messages: ChatUiMessage[];
  isSending: boolean;
  /** Set of action signatures currently in-flight; second-tap is no-op. */
  actionsInFlight: Set<string>;
  /** First-run AI chat disclosure (V3 retention copy). */
  showDisclosure: boolean;
  /** Clear-conversation confirmation modal. */
  showClearConfirm: boolean;
  /** Latest error to surface as a toast (consumed once). */
  error: string | null;
  /**
   * Optional task the user opened chat from. When set, every `sendMessage`
   * forwards a `task_context` snapshot to the backend so the AI can
   * reference the task's title / due date / priority / project name.
   * Mirrors Android `ChatViewModel._contextTask`. Held imperatively (never
   * subscribed by selectors that drive UI re-renders) so chat doesn't
   * re-render on every task mutation.
   */
  contextTask: Task | null;

  initialize: (startOfDayHour: number) => Promise<void>;
  sendMessage: (text: string) => Promise<void>;
  /** Pin (or clear) the task forwarded as `task_context` on each send. */
  setContextTask: (task: Task | null) => void;
  dismissDisclosure: () => void;
  requestClearConversation: () => void;
  dismissClearConfirm: () => void;
  confirmClearAndPersistSkip: (skipFutureConfirmations: boolean) => void;
  clearConversation: () => void;
  setActionInFlight: (signature: string, inFlight: boolean) => void;
  clearError: () => void;
  /** Test-only reset. */
  _reset: () => void;
}

function uuid8(): string {
  return Math.random().toString(36).slice(2, 10);
}

function isoDateForStartOfDay(now: Date, startOfDayHour: number): string {
  const effective = new Date(now);
  if (effective.getHours() < startOfDayHour) {
    effective.setDate(effective.getDate() - 1);
  }
  const y = effective.getFullYear();
  const m = String(effective.getMonth() + 1).padStart(2, '0');
  const d = String(effective.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function newConversationId(dateIso: string): string {
  return `chat_${dateIso}_${uuid8()}`;
}

function recordToUi(record: ChatMessageRecord): ChatUiMessage {
  return {
    id: record.id,
    conversationId: record.conversation_id,
    role: record.role,
    text: record.content,
    actions: record.actions ?? [],
    createdAt: record.created_at,
  };
}

function buildHistoryPayload(messages: ChatUiMessage[]): ChatHistoryEntry[] {
  // Last N=6 user+assistant pairs (max 12 entries; backend caps at 12).
  return messages.slice(-MAX_HISTORY_PAIRS * 2).map((m) => ({
    role: m.role,
    content: m.text,
  }));
}

/**
 * Build the `task_context` snapshot the backend forwards to Anthropic.
 * Mirrors Android `ChatViewModel.buildTaskContextSnapshot` — same field
 * set, same omit-when-blank semantics, same priority-when-nonzero rule.
 *
 * `due_date` is the YYYY-MM-DD prefix of `task.due_date` (which is already
 * stored that way on the web `Task` model). The project name is looked
 * up imperatively against the project store so we don't subscribe and
 * re-render on unrelated project edits.
 */
function buildTaskContextSnapshot(task: Task | null): ChatTaskContext | null {
  if (!task) return null;
  const project = task.project_id
    ? useProjectStore.getState().projects.find((p) => p.id === task.project_id)
    : undefined;
  const description = task.description?.trim();
  return {
    title: task.title,
    description: description && description.length > 0 ? description : null,
    due_date: task.due_date ?? null,
    priority: task.priority && task.priority > 0 ? task.priority : null,
    project_name: project?.title ?? null,
    is_completed: task.status === 'done',
  };
}

function readBoolFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === 'true';
  } catch {
    return false;
  }
}

function writeBoolFlag(key: string, value: boolean): void {
  try {
    if (value) {
      localStorage.setItem(key, 'true');
    } else {
      localStorage.removeItem(key);
    }
  } catch {
    // localStorage may throw in private mode / SSR; ignore.
  }
}

export const useChatStore = create<ChatState>((set, get) => ({
  conversationId: '',
  conversationDate: '',
  messages: [],
  isSending: false,
  actionsInFlight: new Set(),
  showDisclosure: false,
  showClearConfirm: false,
  error: null,
  contextTask: null,

  initialize: async (startOfDayHour: number) => {
    const dateIso = isoDateForStartOfDay(new Date(), startOfDayHour);
    let { conversationId, conversationDate } = get();
    if (!conversationId || conversationDate !== dateIso) {
      // Either first init in this session, or day rollover.
      conversationId = newConversationId(dateIso);
      conversationDate = dateIso;
      set({ conversationId, conversationDate, messages: [] });
    }
    // Show first-run disclosure (V3 retention copy) if not yet acknowledged.
    if (!readBoolFlag(DISCLOSURE_FLAG_KEY)) {
      set({ showDisclosure: true });
    }
    // Reconcile any history written from another device. Errors are
    // swallowed — a failed pull just leaves the local cache as the
    // source-of-truth for this session.
    try {
      const history = await aiChatHistory({
        conversation_id: conversationId,
        limit: DEFAULT_HISTORY_LIMIT,
      });
      const rows = history.messages.map(recordToUi).sort((a, b) =>
        a.createdAt.localeCompare(b.createdAt),
      );
      if (rows.length > 0) {
        set({ messages: rows });
      }
    } catch {
      // No-op; next visit retries.
    }
  },

  sendMessage: async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed) return;
    const state = get();
    if (state.isSending) return;
    if (!state.conversationId) {
      set({ error: 'Chat not ready — try again' });
      return;
    }

    // Optimistic user bubble. id is a placeholder; the next history pull
    // will reconcile against the server-assigned PK.
    const optimisticUserId = `local_${uuid8()}`;
    const optimisticUser: ChatUiMessage = {
      id: optimisticUserId,
      conversationId: state.conversationId,
      role: 'user',
      text: trimmed,
      actions: [],
      createdAt: new Date().toISOString(),
    };
    set({
      messages: [...state.messages, optimisticUser],
      isSending: true,
      error: null,
    });

    try {
      const taskContext = buildTaskContextSnapshot(state.contextTask);
      const response = await aiChat({
        message: trimmed,
        conversation_id: state.conversationId,
        history: buildHistoryPayload(state.messages),
        ...(taskContext ? { task_context: taskContext } : {}),
      });
      // Replace the optimistic placeholder with the server-confirmed user
      // turn (so a subsequent history pull doesn't double-render it), then
      // append the assistant turn.
      const userId = response.user_message_id ?? optimisticUserId;
      const assistantId = response.assistant_message_id ?? `local_${uuid8()}`;
      const nowIso = new Date().toISOString();
      const next = get().messages.map((m) =>
        m.id === optimisticUserId ? { ...m, id: userId } : m,
      );
      next.push({
        id: assistantId,
        conversationId: response.conversation_id,
        role: 'assistant',
        text: response.message,
        actions: response.actions ?? [],
        createdAt: nowIso,
      });
      set({ messages: next, isSending: false });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Chat request failed';
      // Roll back optimistic user bubble so the user can retry.
      set({
        messages: get().messages.filter((m) => m.id !== optimisticUserId),
        isSending: false,
        error: message,
      });
    }
  },

  setContextTask: (task: Task | null) => {
    set({ contextTask: task });
  },

  dismissDisclosure: () => {
    writeBoolFlag(DISCLOSURE_FLAG_KEY, true);
    set({ showDisclosure: false });
  },

  requestClearConversation: () => {
    if (readBoolFlag(CLEAR_SKIP_FLAG_KEY)) {
      get().clearConversation();
    } else {
      set({ showClearConfirm: true });
    }
  },

  dismissClearConfirm: () => set({ showClearConfirm: false }),

  confirmClearAndPersistSkip: (skipFutureConfirmations: boolean) => {
    if (skipFutureConfirmations) {
      writeBoolFlag(CLEAR_SKIP_FLAG_KEY, true);
    }
    get().clearConversation();
  },

  clearConversation: () => {
    // New conversation_id matches Android: old conversation rows stay on
    // backend under their original id; the UI just flips to a fresh thread.
    const { conversationDate } = get();
    const dateIso =
      conversationDate || isoDateForStartOfDay(new Date(), 0);
    set({
      conversationId: newConversationId(dateIso),
      conversationDate: dateIso,
      messages: [],
      showClearConfirm: false,
    });
    toast.success('Chat Cleared');
  },

  setActionInFlight: (signature, inFlight) => {
    const current = new Set(get().actionsInFlight);
    if (inFlight) {
      current.add(signature);
    } else {
      current.delete(signature);
    }
    set({ actionsInFlight: current });
  },

  clearError: () => set({ error: null }),

  _reset: () =>
    set({
      conversationId: '',
      conversationDate: '',
      messages: [],
      isSending: false,
      actionsInFlight: new Set(),
      showDisclosure: false,
      showClearConfirm: false,
      error: null,
      contextTask: null,
    }),
}));

export const __internals = {
  isoDateForStartOfDay,
  newConversationId,
  buildHistoryPayload,
  buildTaskContextSnapshot,
  recordToUi,
  DISCLOSURE_FLAG_KEY,
  CLEAR_SKIP_FLAG_KEY,
};
