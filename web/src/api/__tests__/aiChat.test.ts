import { describe, it, expect, vi, beforeEach } from 'vitest';

const { postMock, getMock } = vi.hoisted(() => ({
  postMock: vi.fn(),
  getMock: vi.fn(),
}));
vi.mock('@/api/client', () => ({
  default: { post: postMock, get: getMock },
}));

import {
  aiChat,
  aiChatHistory,
  aiLifeCategoryClassifyText,
} from '@/api/ai/chat';

describe('aiChat', () => {
  beforeEach(() => {
    postMock.mockReset();
    getMock.mockReset();
  });

  it('POSTs the request body and unwraps .data', async () => {
    postMock.mockResolvedValueOnce({
      data: {
        message: 'Sure, let me help.',
        actions: [],
        conversation_id: 'chat_2026-05-13_abc12345',
        user_preferences: [],
      },
    });
    const res = await aiChat({
      message: 'What should I focus on?',
      conversation_id: 'chat_2026-05-13_abc12345',
    });
    expect(postMock).toHaveBeenCalledWith('/ai/chat', {
      message: 'What should I focus on?',
      conversation_id: 'chat_2026-05-13_abc12345',
    });
    expect(res.message).toBe('Sure, let me help.');
    expect(res.actions).toEqual([]);
  });

  it('forwards history + task_context for multi-turn / context-task chats', async () => {
    postMock.mockResolvedValueOnce({
      data: {
        message: 'Hi',
        actions: [],
        conversation_id: 'chat_2026-05-13_xxxxxxxx',
        user_preferences: [],
      },
    });
    await aiChat({
      message: 'Tell me more',
      conversation_id: 'chat_2026-05-13_xxxxxxxx',
      task_context_id: 42,
      task_context: { title: 'Write report' },
      history: [
        { role: 'user', content: 'hi' },
        { role: 'assistant', content: 'hello' },
      ],
    });
    const body = postMock.mock.calls[0][1];
    expect(body.history).toHaveLength(2);
    expect(body.task_context.title).toBe('Write report');
    expect(body.task_context_id).toBe(42);
  });
});

describe('aiChatHistory', () => {
  beforeEach(() => {
    getMock.mockReset();
  });

  it('returns an empty page on cold start', async () => {
    getMock.mockResolvedValueOnce({
      data: { messages: [], next_before: null },
    });
    const res = await aiChatHistory();
    expect(getMock).toHaveBeenCalledWith('/ai/chat/history', { params: {} });
    expect(res.messages).toEqual([]);
    expect(res.next_before).toBeNull();
  });

  it('passes through filter params when provided', async () => {
    getMock.mockResolvedValueOnce({
      data: { messages: [], next_before: null },
    });
    await aiChatHistory({
      conversation_id: 'chat_2026-05-13_zzzzzzzz',
      before: '2026-05-12T00:00:00Z',
      limit: 100,
    });
    expect(getMock).toHaveBeenCalledWith('/ai/chat/history', {
      params: {
        conversation_id: 'chat_2026-05-13_zzzzzzzz',
        before: '2026-05-12T00:00:00Z',
        limit: 100,
      },
    });
  });
});

describe('aiLifeCategoryClassifyText', () => {
  beforeEach(() => {
    postMock.mockReset();
  });

  it('POSTs title + description and unwraps .data', async () => {
    postMock.mockResolvedValueOnce({
      data: { category: 'WORK', reason: 'mentions client deliverable' },
    });
    const res = await aiLifeCategoryClassifyText({
      title: 'Send Q3 deck to client',
      description: 'Needs sales numbers',
    });
    expect(postMock).toHaveBeenCalledWith('/ai/life-category/classify_text', {
      title: 'Send Q3 deck to client',
      description: 'Needs sales numbers',
    });
    expect(res.category).toBe('WORK');
  });

  it('accepts a minimal payload', async () => {
    postMock.mockResolvedValueOnce({
      data: { category: 'UNCATEGORIZED', reason: '' },
    });
    await aiLifeCategoryClassifyText({ title: 'Buy milk' });
    expect(postMock).toHaveBeenCalledWith('/ai/life-category/classify_text', {
      title: 'Buy milk',
    });
  });
});
