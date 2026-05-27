import { describe, it, expect } from 'vitest';
import { isDormant, readyToResume, effectiveThresholdDays } from '../dormancy';
import type { Task } from '@/types/task';

const DAY = 24 * 60 * 60 * 1000;
const now = 1_800_000_000_000;

function task(overrides: Partial<Task> = {}): Task {
  return {
    id: 'a',
    project_id: 'p',
    user_id: 'u',
    parent_id: null,
    title: 't',
    description: null,
    notes: null,
    status: 'todo',
    priority: 3,
    due_date: null,
    due_time: null,
    planned_date: null,
    completed_at: null,
    urgency_score: 0,
    recurrence_json: 'FREQ=DAILY',
    eisenhower_quadrant: null,
    eisenhower_updated_at: null,
    estimated_duration: null,
    actual_duration: null,
    sort_order: 0,
    depth: 0,
    created_at: '',
    updated_at: '',
    last_engagement_at: now - 20 * DAY,
    ...overrides,
  } as Task;
}

describe('dormancy helper', () => {
  it('effectiveThresholdDays prefers the per-task override', () => {
    expect(effectiveThresholdDays(30, 7)).toBe(30);
    expect(effectiveThresholdDays(null, 7)).toBe(7);
    expect(effectiveThresholdDays(undefined, 7)).toBe(7);
  });

  it('never-engaged tasks are not dormant', () => {
    expect(isDormant(task({ last_engagement_at: null }), 7, now)).toBe(false);
  });

  it('is dormant past the threshold but not at/under it', () => {
    expect(isDormant(task({ last_engagement_at: now - 8 * DAY }), 7, now)).toBe(true);
    expect(isDormant(task({ last_engagement_at: now - 7 * DAY }), 7, now)).toBe(false);
    expect(isDormant(task({ last_engagement_at: now - 6 * DAY }), 7, now)).toBe(false);
  });

  it('per-task override suppresses dormancy below its threshold', () => {
    expect(
      isDormant(task({ last_engagement_at: now - 10 * DAY, dormancy_threshold_days_override: 30 }), 7, now),
    ).toBe(false);
  });

  it('readyToResume filters, sorts longest-dormant first, and caps at 5', () => {
    const tasks: Task[] = [
      task({ id: '1', last_engagement_at: now - 10 * DAY }),
      task({ id: '2', last_engagement_at: now - 40 * DAY }),
      task({ id: '3', recurrence_json: null }), // not recurring → out
      task({ id: '4', status: 'done', last_engagement_at: now - 30 * DAY }), // done → out
      task({ id: '5', last_engagement_at: null }), // never engaged → out
      task({ id: '6', last_engagement_at: now - 25 * DAY }),
    ];
    const result = readyToResume(tasks, 7, now);
    expect(result.map((d) => d.task.id)).toEqual(['2', '6', '1']);
    expect(result[0].daysDormant).toBe(40);
  });
});
