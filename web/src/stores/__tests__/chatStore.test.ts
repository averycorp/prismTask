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
import { useProjectStore } from '@/stores/projectStore';
import type { Task } from '@/types/task';
import type { Project } from '@/types/project';

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

describe('chatStore — task_context forwarding', () => {
  const makeTask = (overrides: Partial<Task> = {}): Task => ({
    id: 't1',
    project_id: 'p1',
    user_id: 'u1',
    parent_id: null,
    title: 'Write Spec',
    description: 'Outline the proposal.',
    notes: null,
    status: 'todo',
    priority: 3,
    due_date: '2026-05-20',
    due_time: null,
    planned_date: null,
    completed_at: null,
    urgency_score: 0.5,
    recurrence_json: null,
    eisenhower_quadrant: null,
    eisenhower_updated_at: null,
    estimated_duration: null,
    actual_duration: null,
    sort_order: 0,
    depth: 0,
    created_at: '2026-05-15T00:00:00Z',
    updated_at: '2026-05-15T00:00:00Z',
    ...overrides,
  });

  const seedProject = (project: Project): void => {
    useProjectStore.setState({ projects: [project] });
  };

  beforeEach(() => {
    useProjectStore.setState({ projects: [] });
  });

  it('buildTaskContextSnapshot returns null for null input', () => {
    expect(__internals.buildTaskContextSnapshot(null)).toBeNull();
  });

  it('buildTaskContextSnapshot resolves project_name from the project store', () => {
    seedProject({
      id: 'p1',
      goal_id: 'g1',
      user_id: 'u1',
      title: 'Q3 Goals',
      description: null,
      status: 'active',
      due_date: null,
      color: '#fff',
      icon: 'flag',
      sort_order: 0,
      created_at: 'x',
      updated_at: 'x',
    });
    const snap = __internals.buildTaskContextSnapshot(makeTask());
    expect(snap).toEqual({
      title: 'Write Spec',
      description: 'Outline the proposal.',
      due_date: '2026-05-20',
      priority: 3,
      project_name: 'Q3 Goals',
      is_completed: false,
    });
  });

  it('omits priority when zero and description when blank', () => {
    const snap = __internals.buildTaskContextSnapshot(
      makeTask({
        priority: 0 as unknown as Task['priority'],
        description: '   ',
        project_id: '',
      }),
    );
    expect(snap?.priority).toBeNull();
    expect(snap?.description).toBeNull();
    expect(snap?.project_name).toBeNull();
  });

  it('marks is_completed true when status is done', () => {
    const snap = __internals.buildTaskContextSnapshot(
      makeTask({ status: 'done' }),
    );
    expect(snap?.is_completed).toBe(true);
  });

  it('forwards task_context in sendMessage when a context task is pinned', async () => {
    aiChatHistoryMock.mockResolvedValue({ messages: [], next_before: null });
    await useChatStore.getState().initialize(0);

    seedProject({
      id: 'p1',
      goal_id: 'g1',
      user_id: 'u1',
      title: 'Q3 Goals',
      description: null,
      status: 'active',
      due_date: null,
      color: '#fff',
      icon: 'flag',
      sort_order: 0,
      created_at: 'x',
      updated_at: 'x',
    });
    useChatStore.getState().setContextTask(makeTask());

    aiChatMock.mockResolvedValue({
      message: 'ok',
      actions: [],
      conversation_id: useChatStore.getState().conversationId,
      user_preferences: [],
    });

    await useChatStore.getState().sendMessage('what now?');

    expect(aiChatMock).toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'what now?',
        task_context: {
          title: 'Write Spec',
          description: 'Outline the proposal.',
          due_date: '2026-05-20',
          priority: 3,
          project_name: 'Q3 Goals',
          is_completed: false,
        },
      }),
    );
  });

  it('omits task_context entirely when no context task is set', async () => {
    aiChatHistoryMock.mockResolvedValue({ messages: [], next_before: null });
    await useChatStore.getState().initialize(0);

    aiChatMock.mockResolvedValue({
      message: 'ok',
      actions: [],
      conversation_id: useChatStore.getState().conversationId,
      user_preferences: [],
    });

    await useChatStore.getState().sendMessage('hi');

    const payload = aiChatMock.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(payload).not.toHaveProperty('task_context');
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
