import apiClient from '../client';
import type {
  ChatHistoryResponse,
  ChatRequest,
  ChatResponse,
  LifeCategoryClassifyTextRequest,
  LifeCategoryClassifyTextResponse,
} from '@/types/chat';

/**
 * REST helpers for the AI Coach chat surface (D11 E.3 + Batch 3 parity).
 *
 * The backend is the source of truth (Postgres `chat_messages`). Web does
 * NOT mirror to a local store — `pullHistory()` re-fetches on screen mount,
 * matching the "server-authoritative, no Firestore" architecture documented
 * in CLAUDE.md § "Chat Persistence (D11 E.3)".
 *
 * Backend endpoints live in `backend/app/routers/ai/chat.py`:
 *   - POST /api/v1/ai/chat            (single-shot; full reply + actions)
 *   - GET  /api/v1/ai/chat/history    (paginated, user-scoped)
 *
 * Both are gated by the master AI features toggle via `AI_PATH_PREFIXES`
 * in `web/src/api/client.ts`.
 */

/**
 * Send one chat turn. Backend persists both the user and assistant rows
 * inside the handler, so on success the next `aiChatHistory()` call will
 * include this turn even on another device.
 */
export function aiChat(req: ChatRequest): Promise<ChatResponse> {
  return apiClient.post<ChatResponse>('/ai/chat', req).then((r) => r.data);
}

export interface ChatHistoryQuery {
  /** Filter to one conversation. Omit for "all conversations". */
  conversation_id?: string;
  /** ISO-8601 cursor; returns rows older than this. */
  before?: string;
  /** Server caps at 200; defaults vary. */
  limit?: number;
}

/** Fetch persisted chat history for the current user. */
export function aiChatHistory(
  query: ChatHistoryQuery = {},
): Promise<ChatHistoryResponse> {
  const params: Record<string, string | number> = {};
  if (query.conversation_id) params.conversation_id = query.conversation_id;
  if (query.before) params.before = query.before;
  if (query.limit != null) params.limit = query.limit;
  return apiClient
    .get<ChatHistoryResponse>('/ai/chat/history', { params })
    .then((r) => r.data);
}

/**
 * Single-task Work-Life Balance category classification. Mirrors the
 * Android OrganizeTab "Auto" button — see
 * `app/.../AddEditTaskViewModel.kt::tryUpgradeLifeCategoryWithClaude`.
 * Backend: `backend/app/routers/ai/eisenhower.py:187`.
 */
export function aiLifeCategoryClassifyText(
  req: LifeCategoryClassifyTextRequest,
): Promise<LifeCategoryClassifyTextResponse> {
  return apiClient
    .post<LifeCategoryClassifyTextResponse>(
      '/ai/life-category/classify_text',
      req,
    )
    .then((r) => r.data);
}
