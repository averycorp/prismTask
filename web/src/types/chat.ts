/**
 * Types matching `backend/app/schemas/ai.py:714-848` (ChatActionPayload,
 * ChatRequest, ChatResponse, ChatMessageRecord, ChatHistoryResponse) and
 * `backend/app/schemas/ai.py:70-86` for the life-category text endpoint.
 *
 * The action payload is intentionally a union of optional fields — different
 * `type` values use different subsets. See `ChatViewModel.executeAction`
 * (Android) for the per-type contract; the web `ChatScreen` dispatcher
 * mirrors that switch.
 *
 * Conversation IDs follow the `chat_${ISO_DATE}_${UUID8}` pattern minted
 * client-side. Daily rollover is a UI filter, not a destructive event.
 */

export type ChatRole = 'user' | 'assistant';

export type ChatActionType =
  | 'complete'
  | 'reschedule'
  | 'reschedule_batch'
  | 'breakdown'
  | 'archive'
  | 'start_timer'
  | 'create_task'
  | 'batch_command';

export interface ChatActionPayload {
  type: ChatActionType | string;
  task_id?: string | null;
  task_ids?: string[] | null;
  to?: string | null; // "today" | "tomorrow" | "next_week" | ISO date
  subtasks?: string[] | null;
  minutes?: number | null;
  title?: string | null;
  due?: string | null;
  priority?: 'low' | 'medium' | 'high' | 'urgent' | null;
  description?: string | null;
  tags?: string[] | null;
  project?: string | null;
  /** Free-form natural-language phrasing for `batch_command`. */
  command_text?: string | null;
}

export interface ChatTokensUsed {
  input: number;
  output: number;
}

export interface ChatHistoryEntry {
  role: ChatRole;
  content: string;
}

export interface ChatTaskContext {
  title: string;
  description?: string | null;
  due_date?: string | null;
  priority?: number | null;
  project_name?: string | null;
  is_completed?: boolean | null;
}

export interface ChatRequest {
  message: string;
  conversation_id: string;
  task_context_id?: number | null;
  task_context?: ChatTaskContext | null;
  history?: ChatHistoryEntry[];
  tier?: string | null;
}

export interface UserAiPreferenceRecord {
  id: string;
  preference_text: string;
  created_at: string;
  updated_at: string;
}

export interface ChatResponse {
  message: string;
  actions: ChatActionPayload[];
  conversation_id: string;
  tokens_used?: ChatTokensUsed | null;
  user_message_id?: string | null;
  assistant_message_id?: string | null;
  user_preferences: UserAiPreferenceRecord[];
}

export interface ChatMessageRecord {
  id: string;
  conversation_id: string;
  role: ChatRole;
  content: string;
  actions: ChatActionPayload[];
  task_context_snapshot?: ChatTaskContext | null;
  tokens_used?: ChatTokensUsed | null;
  created_at: string;
}

export interface ChatHistoryResponse {
  messages: ChatMessageRecord[];
  next_before?: string | null;
}

// --- Life-category classify (D.1c) ---

export type LifeCategory =
  | 'WORK'
  | 'PERSONAL'
  | 'SELF_CARE'
  | 'HEALTH'
  | 'UNCATEGORIZED';

export interface LifeCategoryClassifyTextRequest {
  title: string;
  description?: string | null;
}

export interface LifeCategoryClassifyTextResponse {
  category: LifeCategory | string;
  reason: string;
}
