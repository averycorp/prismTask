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
    expect(conversationId).toContain(conversationDate);
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

describe('chatStore — rolling N=6 history payload', () => {
  it('forwards at most the last 6 user/assistant pairs (12 entries) as history', () => {
    const long: { role: 'user' | 'assistant'; text: string }[] = [];
    for (let i = 0; i < 20; i += 1) {
      long.push({ role: 'user', text: `u${i}` });
      long.push({ role: 'assistant', text: `a${i}` });
    }
    const messages = long.map((m, idx) => ({
      id: `m${idx}`,
      conversationId: 'chat_x',
      role: m.role,
      text: m.text,
      actions: [],
      createdAt: new Date().toISOString(),
    }));
    const payload = __internals.buildHistoryPayload(messages);
    expect(payload).toHaveLength(12);
    // Last entries are preserved.
    expect(payload[payload.length - 1]).toEqual({
      role: 'assistant',
      content: 'a19',
    });
    expect(payload[0]).toEqual({ role: 'user', content: 'u14' });
  });

  it('returns fewer entries when there is less history', () => {
    const messages = [
      {
        id: 'm0',
        conversationId: 'chat_x',
        role: 'user' as const,
        text: 'hi',
        actions: [],
        createdAt: new Date().toISOString(),
      },
    ];
    expect(__internals.buildHistoryPayload(messages)).toHaveLength(1);
  });

  it('forwards history payload in sendMessage', async () => {
    aiChatHistoryMock.mockResolvedValue({ messages: [], next_before: null });
    await useChatStore.getState().initialize(0);

    aiChatMock.mockResolvedValue({
      message: 'ok',
      actions: [],
      conversation_id: useChatStore.getState().conversationId,
      user_preferences: [],
    });

    await useChatStore.getState().sendMessage('first');
    await useChatStore.getState().sendMessage('second');

    expect(aiChatMock).toHaveBeenLastCalledWith(
      expect.objectContaining({
        message: 'second',
        history: expect.arrayContaining([
          expect.objectContaining({ role: 'user', content: 'first' }),
          expect.objectContaining({ role: 'assistant', content: 'ok' }),
        ]),
      }),
    );
  });
});

describe('chatStore — conversation id pattern', () => {
  it('matches chat_{ISO_DATE}_{UUID8}', () => {
    const id = __internals.newConversationId('2026-05-15');
    expect(id).toMatch(/^chat_2026-05-15_[a-z0-9]{8}$/);
  });

  it('isoDateForStartOfDay rolls back to the previous day when before SoD', () => {
    const t = new Date('2026-05-15T04:00:00');
    // SoD hour 6 means 4am is still "yesterday".
    expect(__internals.isoDateForStartOfDay(t, 6)).toBe('2026-05-14');
    // SoD hour 0 means today.
    const out = __internals.isoDateForStartOfDay(t, 0);
    expect(out).toMatch(/^2026-05-15$/);
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
