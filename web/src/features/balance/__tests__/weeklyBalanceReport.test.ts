import { describe, it, expect } from 'vitest';
import type { Task } from '@/types/task';
import { DEFAULT_BALANCE_CONFIG } from '@/utils/balanceTracker';
import {
  computeWeeklyBalanceReport,
  computeWeekWindow,
  deltaPercentPoints,
  formatWeekLabel,
  shiftWeek,
} from '../weeklyBalanceReport';

function makeTask(overrides: Partial<Task> & { completed_at: string }): Task {
  // Cast through Partial — the aggregator only reads completed_at +
  // life_category, so we keep the fixture minimal rather than fabricating
  // 25+ fields.
  const base = {
    id: `t-${overrides.completed_at}-${overrides.life_category ?? 'x'}`,
    title: 'task',
    status: 'done',
    life_category: null,
  };
  return { ...base, ...overrides } as unknown as Task;
}

describe('weeklyBalanceReport', () => {
  // Reference: Wed May 13, 2026 noon local. Mon..Sun spans May 11..17.
  const REFERENCE = new Date(2026, 4, 13, 12, 0, 0).getTime();

  it('computes window starting Monday', () => {
    const w = computeWeekWindow(REFERENCE);
    expect(new Date(w.startMs).getDay()).toBe(1); // Monday
    expect(new Date(w.endMs).getDay()).toBe(0); // Sunday
  });

  it('shiftWeek navigates by 7 days', () => {
    const w = computeWeekWindow(REFERENCE);
    const prev = shiftWeek(w, -1);
    const next = shiftWeek(w, 1);
    expect(prev.startMs).toBe(w.startMs - 7 * 86_400_000);
    expect(next.startMs).toBe(w.startMs + 7 * 86_400_000);
  });

  it('formatWeekLabel renders a sensible label', () => {
    const w = computeWeekWindow(REFERENCE);
    const label = formatWeekLabel(w);
    expect(label).toContain('2026');
    expect(label).toContain('–');
  });

  it('aggregates current-week ratios + counts', () => {
    const tasks: Task[] = [
      makeTask({ id: 'a', life_category: 'WORK', completed_at: new Date(2026, 4, 12, 9).toISOString() }),
      makeTask({ id: 'b', life_category: 'WORK', completed_at: new Date(2026, 4, 13, 9).toISOString() }),
      makeTask({ id: 'c', life_category: 'PERSONAL', completed_at: new Date(2026, 4, 14, 9).toISOString() }),
      makeTask({ id: 'd', life_category: 'HEALTH', completed_at: new Date(2026, 4, 15, 9).toISOString() }),
      // Outside window — should be ignored.
      makeTask({ id: 'e', life_category: 'WORK', completed_at: new Date(2026, 4, 1, 9).toISOString() }),
      // Uncategorized — should be ignored.
      makeTask({ id: 'f', life_category: 'UNCATEGORIZED', completed_at: new Date(2026, 4, 13, 9).toISOString() }),
      // Incomplete — should be ignored.
      makeTask({ id: 'g', life_category: 'WORK', completed_at: '' }),
    ];

    const report = computeWeeklyBalanceReport(tasks, DEFAULT_BALANCE_CONFIG, REFERENCE);
    expect(report.totalTracked).toBe(4);
    expect(report.currentCounts.WORK).toBe(2);
    expect(report.currentCounts.PERSONAL).toBe(1);
    expect(report.currentCounts.HEALTH).toBe(1);
    expect(report.currentCounts.SELF_CARE).toBe(0);
    expect(report.currentRatios.WORK).toBeCloseTo(0.5, 5);
    expect(report.currentRatios.PERSONAL).toBeCloseTo(0.25, 5);
  });

  it('returns prior-week counts and delta', () => {
    const tasks: Task[] = [
      // Current week (May 11–17): one WORK
      makeTask({ life_category: 'WORK', completed_at: new Date(2026, 4, 13, 9).toISOString() }),
      // Prior week (May 4–10): two PERSONAL
      makeTask({ life_category: 'PERSONAL', completed_at: new Date(2026, 4, 5, 9).toISOString() }),
      makeTask({ life_category: 'PERSONAL', completed_at: new Date(2026, 4, 6, 9).toISOString() }),
    ];
    const report = computeWeeklyBalanceReport(tasks, DEFAULT_BALANCE_CONFIG, REFERENCE);
    expect(report.priorCounts.PERSONAL).toBe(2);
    expect(report.priorCounts.WORK).toBe(0);
    expect(deltaPercentPoints(report.currentRatios, report.priorRatios, 'PERSONAL'))
      .toBe(-100);
    expect(deltaPercentPoints(report.currentRatios, report.priorRatios, 'WORK'))
      .toBe(100);
  });

  it('flags overload days when WORK exceeds work+threshold target', () => {
    // Default: workTarget 0.4 + overloadThreshold 0.1 → overload above 0.5.
    // Tuesday May 12: 3 work, 1 personal → work ratio 0.75 → overload.
    // Thursday May 14: 1 work, 1 personal → 0.5 → not over (must be strictly above).
    const tasks: Task[] = [
      makeTask({ life_category: 'WORK', completed_at: new Date(2026, 4, 12, 9).toISOString() }),
      makeTask({ life_category: 'WORK', completed_at: new Date(2026, 4, 12, 10).toISOString() }),
      makeTask({ life_category: 'WORK', completed_at: new Date(2026, 4, 12, 11).toISOString() }),
      makeTask({ life_category: 'PERSONAL', completed_at: new Date(2026, 4, 12, 12).toISOString() }),
      makeTask({ life_category: 'WORK', completed_at: new Date(2026, 4, 14, 9).toISOString() }),
      makeTask({ life_category: 'PERSONAL', completed_at: new Date(2026, 4, 14, 10).toISOString() }),
    ];
    const report = computeWeeklyBalanceReport(tasks, DEFAULT_BALANCE_CONFIG, REFERENCE);
    expect(report.overloadDays).toBe(1);
    expect(report.perDay.some((d) => d.isOverloaded && d.counts.WORK === 3)).toBe(true);
  });

  it('emits 4-week trend arrays of length 4 per category', () => {
    const tasks: Task[] = [
      makeTask({ life_category: 'WORK', completed_at: new Date(2026, 4, 13, 9).toISOString() }),
    ];
    const report = computeWeeklyBalanceReport(tasks, DEFAULT_BALANCE_CONFIG, REFERENCE);
    expect(report.fourWeekTrend.WORK).toHaveLength(4);
    expect(report.fourWeekCounts.WORK).toHaveLength(4);
    expect(report.fourWeekCounts.WORK[3]).toBe(1);
  });

  it('returns empty ratios when no completions in window', () => {
    const report = computeWeeklyBalanceReport([], DEFAULT_BALANCE_CONFIG, REFERENCE);
    expect(report.totalTracked).toBe(0);
    expect(report.currentRatios.WORK).toBe(0);
    expect(report.overloadDays).toBe(0);
    expect(report.perDay).toHaveLength(7);
  });
});
