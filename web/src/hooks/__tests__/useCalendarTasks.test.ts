import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import type { Task } from '@/types/task';

const { getAllTasksMock } = vi.hoisted(() => ({
  getAllTasksMock: vi.fn(),
}));

vi.mock('@/api/firestore/tasks', () => ({
  getAllTasks: getAllTasksMock,
}));

vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: () => 'uid-123',
}));

import { useCalendarTasks } from '../useCalendarTasks';

function makeTask(overrides: Partial<Task>): Task {
  return {
    id: overrides.id ?? 't1',
    project_id: 'p1',
    user_id: 'u1',
    parent_id: null,
    title: overrides.title ?? 'Test',
    description: null,
    notes: null,
    status: overrides.status ?? 'todo',
    priority: overrides.priority ?? 3,
    due_date: overrides.due_date ?? null,
    due_time: null,
    planned_date: null,
    completed_at: null,
    urgency_score: 0,
    recurrence_json: null,
    eisenhower_quadrant: null,
    eisenhower_updated_at: null,
    estimated_duration: null,
    actual_duration: null,
    sort_order: 0,
    depth: 0,
    created_at: '2026-05-15T00:00:00.000Z',
    updated_at: '2026-05-15T00:00:00.000Z',
    ...overrides,
  } as Task;
}

describe('useCalendarTasks (B-05 reads Firestore, groups by due date)', () => {
  beforeEach(() => {
    getAllTasksMock.mockReset();
  });

  it('places a dated task on its due-date cell', async () => {
    const due = '2026-06-15';
    getAllTasksMock.mockResolvedValue([
      makeTask({ id: 'a', title: 'Pay rent', due_date: due }),
    ]);

    const start = new Date(2026, 5, 1);
    const end = new Date(2026, 5, 30);
    const { result } = renderHook(() => useCalendarTasks(start, end));

    await waitFor(() =>
      expect(result.current.getTasksForDate(new Date(2026, 5, 15))).toHaveLength(1),
    );
    expect(result.current.getTasksForDate(new Date(2026, 5, 15))[0].id).toBe('a');
    // A different day in range has none.
    expect(result.current.getTasksForDate(new Date(2026, 5, 16))).toHaveLength(0);
  });

  it('reads from Firestore (not the FastAPI endpoint)', async () => {
    getAllTasksMock.mockResolvedValue([]);
    renderHook(() =>
      useCalendarTasks(new Date(2026, 5, 1), new Date(2026, 5, 30)),
    );
    await waitFor(() => expect(getAllTasksMock).toHaveBeenCalledWith('uid-123'));
  });

  it('omits completed and cancelled tasks', async () => {
    getAllTasksMock.mockResolvedValue([
      makeTask({ id: 'done', due_date: '2026-06-10', status: 'done' }),
      makeTask({ id: 'open', due_date: '2026-06-10', status: 'todo' }),
    ]);
    const { result } = renderHook(() =>
      useCalendarTasks(new Date(2026, 5, 1), new Date(2026, 5, 30)),
    );
    await waitFor(() =>
      expect(result.current.getTasksForDate(new Date(2026, 5, 10))).toHaveLength(1),
    );
    expect(result.current.getTasksForDate(new Date(2026, 5, 10))[0].id).toBe('open');
  });
});
