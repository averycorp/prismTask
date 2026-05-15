import { describe, it, expect } from 'vitest';
import {
  bucketUpcoming,
  priorityRank,
  dueRank,
} from '@/features/today/planForTodayBuckets';
import type { Task, TaskPriority } from '@/types/task';

/**
 * Pure-function tests for the Plan For Today bucketing logic. Mirrors
 * the Android `PlanForTodaySheet.kt` window math (line 134-148):
 *  - tomorrow         = [T+1, T+2)
 *  - this week        = [T+2, T+7)
 *  - next week        = [T+7, T+14)
 *  - later            = [T+14, ∞)
 *  - no date          = due_date == null
 *
 * No DOM, no React, no Firestore. Closes Parity Batch 2 § C.1b.
 */

function makeTask(overrides: Partial<Task> & { id: string; due_date?: string | null }): Task {
  const base: Task = {
    id: overrides.id,
    project_id: 'p',
    user_id: 'u',
    parent_id: null,
    title: overrides.title ?? overrides.id,
    description: null,
    notes: null,
    status: 'todo',
    priority: (overrides.priority ?? 3) as TaskPriority,
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
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
  };
  return { ...base, ...overrides };
}

const TODAY = '2026-05-14';

describe('bucketUpcoming', () => {
  it('returns empty buckets for an empty list', () => {
    const out = bucketUpcoming([], TODAY);
    expect(out.tomorrow).toEqual([]);
    expect(out.thisWeek).toEqual([]);
    expect(out.nextWeek).toEqual([]);
    expect(out.later).toEqual([]);
    expect(out.noDate).toEqual([]);
  });

  it('places due_date == today+1 in tomorrow', () => {
    const t = makeTask({ id: 'a', due_date: '2026-05-15' });
    const out = bucketUpcoming([t], TODAY);
    expect(out.tomorrow).toEqual([t]);
    expect(out.thisWeek).toEqual([]);
  });

  it('places due_date in [T+2, T+7) in thisWeek', () => {
    const t1 = makeTask({ id: 'a', due_date: '2026-05-16' }); // T+2
    const t2 = makeTask({ id: 'b', due_date: '2026-05-20' }); // T+6
    const out = bucketUpcoming([t1, t2], TODAY);
    expect(out.thisWeek.map((t) => t.id).sort()).toEqual(['a', 'b']);
  });

  it('places due_date == today+7 in nextWeek (boundary inclusive on left)', () => {
    const t = makeTask({ id: 'a', due_date: '2026-05-21' }); // T+7
    const out = bucketUpcoming([t], TODAY);
    expect(out.nextWeek).toEqual([t]);
    expect(out.thisWeek).toEqual([]);
  });

  it('places due_date in [T+7, T+14) in nextWeek', () => {
    const t1 = makeTask({ id: 'a', due_date: '2026-05-22' });
    const t2 = makeTask({ id: 'b', due_date: '2026-05-27' });
    const out = bucketUpcoming([t1, t2], TODAY);
    expect(out.nextWeek.map((t) => t.id).sort()).toEqual(['a', 'b']);
  });

  it('places due_date >= today+14 in later (boundary inclusive on left)', () => {
    const t1 = makeTask({ id: 'a', due_date: '2026-05-28' }); // T+14
    const t2 = makeTask({ id: 'b', due_date: '2026-08-01' });
    const out = bucketUpcoming([t1, t2], TODAY);
    expect(out.later.map((t) => t.id).sort()).toEqual(['a', 'b']);
    expect(out.nextWeek).toEqual([]);
  });

  it('places null due_date in noDate', () => {
    const t = makeTask({ id: 'a', due_date: null });
    const out = bucketUpcoming([t], TODAY);
    expect(out.noDate).toEqual([t]);
    expect(out.tomorrow).toEqual([]);
  });

  it('safety-nets pre-tomorrow dates into noDate (caller pre-filters overdue)', () => {
    // Pre-filter contract: callers pass tasks they consider "upcoming".
    // If a non-overdue task somehow has a date earlier than today+1 we
    // still want to surface it without dropping it on the floor — push
    // it into noDate so the user can see and re-plan it.
    const t = makeTask({ id: 'a', due_date: '2026-05-13' });
    const out = bucketUpcoming([t], TODAY);
    expect(out.noDate).toEqual([t]);
  });

  it('multi-bucket fan-out preserves task identity across buckets', () => {
    const tasks = [
      makeTask({ id: 'tomorrow', due_date: '2026-05-15' }),
      makeTask({ id: 'thisWeek', due_date: '2026-05-18' }),
      makeTask({ id: 'nextWeek', due_date: '2026-05-25' }),
      makeTask({ id: 'later', due_date: '2026-06-30' }),
      makeTask({ id: 'noDate', due_date: null }),
    ];
    const out = bucketUpcoming(tasks, TODAY);
    expect(out.tomorrow.map((t) => t.id)).toEqual(['tomorrow']);
    expect(out.thisWeek.map((t) => t.id)).toEqual(['thisWeek']);
    expect(out.nextWeek.map((t) => t.id)).toEqual(['nextWeek']);
    expect(out.later.map((t) => t.id)).toEqual(['later']);
    expect(out.noDate.map((t) => t.id)).toEqual(['noDate']);
  });
});

describe('priorityRank', () => {
  it('maps urgent (1) → highest rank, low (4) → lowest', () => {
    expect(priorityRank(1)).toBeGreaterThan(priorityRank(2));
    expect(priorityRank(2)).toBeGreaterThan(priorityRank(3));
    expect(priorityRank(3)).toBeGreaterThan(priorityRank(4));
    expect(priorityRank(4)).toBeGreaterThan(0);
  });

  it('treats unknown / 0 priority as the floor', () => {
    expect(priorityRank(0)).toBe(0);
    expect(priorityRank(99 as unknown as number)).toBe(0);
  });
});

describe('dueRank', () => {
  it('null due_date sorts last (max-safe-int)', () => {
    const noDate = makeTask({ id: 'a', due_date: null });
    const dated = makeTask({ id: 'b', due_date: '2026-05-15' });
    expect(dueRank(dated)).toBeLessThan(dueRank(noDate));
  });

  it('earlier due_date sorts first', () => {
    const a = makeTask({ id: 'a', due_date: '2026-05-15' });
    const b = makeTask({ id: 'b', due_date: '2026-06-15' });
    expect(dueRank(a)).toBeLessThan(dueRank(b));
  });
});
