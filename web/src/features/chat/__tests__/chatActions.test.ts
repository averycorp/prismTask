import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  actionLabel,
  actionSignature,
  executeChatAction,
  resolveDateIsoForChat,
  type ChatActionDispatchDeps,
} from '@/features/chat/chatActions';

function makeDeps(): ChatActionDispatchDeps & {
  updateTask: ReturnType<typeof vi.fn>;
  completeTask: ReturnType<typeof vi.fn>;
  deleteTask: ReturnType<typeof vi.fn>;
  setPendingBatchCommand: ReturnType<typeof vi.fn>;
  navigate: ReturnType<typeof vi.fn>;
} {
  return {
    updateTask: vi.fn().mockResolvedValue({}),
    completeTask: vi.fn().mockResolvedValue({}),
    deleteTask: vi.fn().mockResolvedValue({}),
    setPendingBatchCommand: vi.fn(),
    navigate: vi.fn(),
  };
}

describe('chatActions — labels', () => {
  it('renders Title-Cased labels for each chat action type', () => {
    expect(actionLabel({ type: 'complete' })).toBe('Mark Complete');
    expect(actionLabel({ type: 'reschedule', to: 'today' })).toBe(
      'Move to Today',
    );
    expect(actionLabel({ type: 'reschedule', to: 'tomorrow' })).toBe(
      'Move to Tomorrow',
    );
    expect(actionLabel({ type: 'reschedule', to: 'next_week' })).toBe(
      'Move to Next Week',
    );
    expect(actionLabel({ type: 'reschedule', to: '2026-05-20' })).toBe(
      'Reschedule',
    );
    expect(
      actionLabel({ type: 'reschedule_batch', task_ids: ['a', 'b', 'c'] }),
    ).toBe('Reschedule 3 Tasks');
    expect(actionLabel({ type: 'breakdown' })).toBe('Break It Down');
    expect(actionLabel({ type: 'archive' })).toBe('Just Drop It');
    expect(actionLabel({ type: 'start_timer', minutes: 25 })).toBe(
      'Start a 25-Min Timer',
    );
    expect(actionLabel({ type: 'start_timer' })).toBe('Start a Timer');
    expect(actionLabel({ type: 'start_timer', minutes: 9999 })).toBe(
      'Start a Timer',
    );
    expect(actionLabel({ type: 'create_task', title: 'Buy milk' })).toBe(
      'Add Task: Buy milk',
    );
    expect(actionLabel({ type: 'create_task' })).toBe('Add Task');
    expect(actionLabel({ type: 'batch_command' })).toBe('Preview Batch');
  });
});

describe('chatActions — signatures', () => {
  it('returns null when the chip lacks the minimum fields needed to act', () => {
    expect(actionSignature({ type: 'complete' })).toBeNull();
    expect(actionSignature({ type: 'reschedule_batch', task_ids: [] })).toBeNull();
    expect(
      actionSignature({ type: 'batch_command', command_text: '   ' }),
    ).toBeNull();
    expect(actionSignature({ type: 'breakdown' })).toBeNull();
  });

  it('produces stable, order-insensitive signatures for reschedule_batch', () => {
    const a = actionSignature({
      type: 'reschedule_batch',
      task_ids: ['c', 'a', 'b'],
    });
    const b = actionSignature({
      type: 'reschedule_batch',
      task_ids: ['a', 'b', 'c'],
    });
    expect(a).toBe(b);
    expect(a).toBe('reschedule_batch:a,b,c');
  });

  it('normalizes batch_command signatures by trimmed-lowercase text', () => {
    expect(
      actionSignature({
        type: 'batch_command',
        command_text: '  Move ALL OVERDUE  ',
      }),
    ).toBe('batch_command:move all overdue');
  });
});

describe('chatActions — resolveDateIsoForChat', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-05-13T10:00:00Z'));
  });

  it('resolves the relative keywords against system clock', () => {
    expect(resolveDateIsoForChat('today')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(resolveDateIsoForChat('tomorrow')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(resolveDateIsoForChat('next_week')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('passes ISO YYYY-MM-DD through unchanged', () => {
    expect(resolveDateIsoForChat('2026-05-20')).toBe('2026-05-20');
  });

  it('returns null for garbage / null / undefined input', () => {
    expect(resolveDateIsoForChat(null)).toBeNull();
    expect(resolveDateIsoForChat(undefined)).toBeNull();
    expect(resolveDateIsoForChat('next-monday')).toBeNull();
    expect(resolveDateIsoForChat('')).toBeNull();
  });
});

describe('chatActions — executeChatAction', () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  it('completes a single task and returns the success message', async () => {
    const deps = makeDeps();
    const res = await executeChatAction(
      { type: 'complete', task_id: 't1' },
      deps,
    );
    expect(deps.completeTask).toHaveBeenCalledWith('t1');
    expect(res?.message).toBe('Task Completed');
  });

  it('reschedules a single task by mapping `to` → due_date', async () => {
    const deps = makeDeps();
    await executeChatAction(
      { type: 'reschedule', task_id: 't2', to: '2026-06-01' },
      deps,
    );
    expect(deps.updateTask).toHaveBeenCalledWith('t2', {
      due_date: '2026-06-01',
    });
  });

  it('reschedule_batch tallies failures and reports a mixed-success message', async () => {
    const deps = makeDeps();
    let callCount = 0;
    deps.updateTask.mockImplementation(async () => {
      callCount += 1;
      if (callCount === 2) {
        throw new Error('boom');
      }
      return {};
    });
    const res = await executeChatAction(
      {
        type: 'reschedule_batch',
        task_ids: ['a', 'b', 'c'],
        to: 'tomorrow',
      },
      deps,
    );
    expect(deps.updateTask).toHaveBeenCalledTimes(3);
    expect(res?.message).toBe('Rescheduled 2 of 3 Tasks (1 Failed)');
  });

  it('archive routes through deleteTask on web (no archived_at flag)', async () => {
    const deps = makeDeps();
    await executeChatAction({ type: 'archive', task_id: 't3' }, deps);
    expect(deps.deleteTask).toHaveBeenCalledWith('t3');
  });

  it('batch_command pushes the phrase into batchStore and navigates to preview', async () => {
    const deps = makeDeps();
    const res = await executeChatAction(
      {
        type: 'batch_command',
        command_text: 'reschedule overdue work to next monday',
      },
      deps,
    );
    expect(deps.setPendingBatchCommand).toHaveBeenCalledWith(
      'reschedule overdue work to next monday',
    );
    expect(deps.navigate).toHaveBeenCalledWith('/batch/preview');
    // null = chat surface should not toast; BatchPreviewScreen takes over.
    expect(res).toBeNull();
  });

  it('batch_command no-ops on blank command_text', async () => {
    const deps = makeDeps();
    const res = await executeChatAction(
      { type: 'batch_command', command_text: '   ' },
      deps,
    );
    expect(deps.setPendingBatchCommand).not.toHaveBeenCalled();
    expect(deps.navigate).not.toHaveBeenCalled();
    expect(res).toBeNull();
  });

  it('start_timer navigates and surfaces the minutes in the toast', async () => {
    const deps = makeDeps();
    const res = await executeChatAction(
      { type: 'start_timer', minutes: 25 },
      deps,
    );
    expect(deps.navigate).toHaveBeenCalledWith('/pomodoro');
    expect(res?.message).toBe('Starting Timer (25 min)');
  });

  it('create_task without a project surfaces a fall-back toast', async () => {
    const deps = makeDeps();
    const res = await executeChatAction(
      { type: 'create_task', title: 'Email Avery' },
      deps,
    );
    expect(res?.message).toContain('Email Avery');
  });

  it('unknown action types are no-op', async () => {
    const deps = makeDeps();
    const res = await executeChatAction(
      { type: 'mystery_action' as unknown as 'complete' },
      deps,
    );
    expect(res).toBeNull();
  });
});
