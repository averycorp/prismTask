import { describe, it, expect, vi, beforeEach } from 'vitest';

const { aiChatMock, aiChatHistoryMock } = vi.hoisted(() => ({
  aiChatMock: vi.fn(),
  aiChatHistoryMock: vi.fn(),
}));
vi.mock('@/api/ai/chat', () => ({
  aiChat: aiChatMock,
  aiChatHistory: aiChatHistoryMock,
}));
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

import { useChatStore, __internals } from '@/stores/chatStore';

beforeEach(() => {
  aiChatMock.mockReset();
  aiChatHistoryMock.mockReset();
  localStorage.clear();
  useChatStore.getState()._reset();
});

describe('chatStore — initialize', () => {
  it('mints a conversation id with the chat_ prefix and current day', async () => {
    aiChatHistoryMock.mockResolvedValueOnce({ messages: [], next_before: null });
    await useChatStore.getState().initialize(0);
    const { conversationId, conversationDate } = useChatStore.getState();
    expect(conversationId).toMatch(/^chat_\d{4}-\d{2}-\d{2}_[a-z0-9]{8}$/);
    expect(conversationId.endsWith(conversationDate.slice(0, 0))).toBe(false);
    expect(aiChatHistoryMock).toHaveBeenCalledWith({
      conversation_id: conversationId,
      limit: 200,
    });
  });

  it('shows the disclosure once and persists the V3 ack flag', async () => {
    aiChatHistoryMock.mockResolvedValue({ messages: [], next_before: null });
    await useChatStore.getState().initialize(0);
    expect(useChatStore.getState().showDisclosure).toBe(true);
    useChatStore.getState().dismissDisclosure();
    expect(useChatStore.getState().showDisclosure).toBe(false);
    expect(localStorage.getItem(__internals.DISCLOSURE_FLAG_KEY)).toBe('true');
    // Second initialize must not re-open the dialog.
    useChatStore.getState()._reset();
    await useChatStore.getState().initialize(0);
    expect(useChatStore.getState().showDisclosure).toBe(false);
  });

  it('falls through gracefully when history fetch fails', async () => {
    aiChatHistoryMock.mockRejectedValueOnce(new Error('network'));
    await useChatStore.getState().initialize(0);
    expect(useChatStore.getState().messages).toEqual([]);
  });
});

describe('chatStore — sendMessage', () => {
  beforeEach(async () => {
    aiChatHistoryMock.mockResolvedValue({ messages: [], next_before: null });
    await useChatStore.getState().initialize(0);
  });

  it('appends the user bubble optimistically then the assistant reply', async () => {
    aiChatMock.mockResolvedValueOnce({
      message: 'Sure thing!',
      actions: [{ type: 'complete', task_id: 'abc' }],
      conversation_id: useChatStore.getState().conversationId,
      user_message_id: 'srv_user',
      assistant_message_id: 'srv_asst',
      user_preferences: [],
    });
    await useChatStore.getState().sendMessage('hi');
    const msgs = useChatStore.getState().messages;
    expect(msgs).toHaveLength(2);
    expect(msgs[0]).toMatchObject({ role: 'user', text: 'hi', id: 'srv_user' });
    expect(msgs[1]).toMatchObject({
      role: 'assistant',
      text: 'Sure thing!',
      id: 'srv_asst',
    });
    expect(msgs[1].actions).toHaveLength(1);
  });

  it('rolls back the optimistic user bubble on failure', async () => {
    aiChatMock.mockRejectedValueOnce(new Error('boom'));
    await useChatStore.getState().sendMessage('hello');
    expect(useChatStore.getState().messages).toEqual([]);
    expect(useChatStore.getState().error).toBe('boom');
  });

  it('no-ops on blank input', async () => {
    await useChatStore.getState().sendMessage('   ');
    expect(aiChatMock).not.toHaveBeenCalled();
  });
});

describe('chatStore — clearConversation', () => {
  it('mints a fresh conversation id and empties the message list', async () => {
    aiChatHistoryMock.mockResolvedValue({ messages: [], next_before: null });
    await useChatStore.getState().initialize(0);
    const firstId = useChatStore.getState().conversationId;
    useChatStore.getState().clearConversation();
    expect(useChatStore.getState().messages).toEqual([]);
    expect(useChatStore.getState().conversationId).not.toBe(firstId);
    expect(useChatStore.getState().conversationId).toMatch(/^chat_/);
  });

  it('honors the don-ask-again pref', async () => {
    aiChatHistoryMock.mockResolvedValue({ messages: [], next_before: null });
    await useChatStore.getState().initialize(0);
    // First time, show the confirm.
    useChatStore.getState().requestClearConversation();
    expect(useChatStore.getState().showClearConfirm).toBe(true);
    useChatStore.getState().confirmClearAndPersistSkip(true);
    expect(localStorage.getItem(__internals.CLEAR_SKIP_FLAG_KEY)).toBe('true');
    // Second time, skip.
    useChatStore.getState().requestClearConversation();
    expect(useChatStore.getState().showClearConfirm).toBe(false);
  });
});
