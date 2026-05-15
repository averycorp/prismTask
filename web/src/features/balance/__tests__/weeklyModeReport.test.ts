import { describe, it, expect } from 'vitest';
import type { Task } from '@/types/task';
import {
  computeWeeklyModeReport,
  modeDeltaPercentPoints,
} from '../weeklyModeReport';

function makeTask(overrides: Partial<Task> & { completed_at: string }): Task {
  // Cast through Partial — the aggregator only reads completed_at +
  // task_mode, so we keep the fixture minimal rather than fabricating
  // 25+ fields. Matches the LifeCategory aggregator test fixture.
  const base = {
    id: `t-${overrides.completed_at}-${overrides.task_mode ?? 'x'}`,
    title: 'task',
    status: 'done',
    task_mode: null,
  };
  return { ...base, ...overrides } as unknown as Task;
}

describe('weeklyModeReport', () => {
  // Reference: Wed May 13, 2026 noon local. Mon..Sun spans May 11..17.
  const REFERENCE = new Date(2026, 4, 13, 12, 0, 0).getTime();

  it('aggregates current-week mode ratios + counts', () => {
    const tasks: Task[] = [
      makeTask({
        id: 'a',
        task_mode: 'WORK',
        completed_at: new Date(2026, 4, 12, 9).toISOString(),
      }),
      makeTask({
        id: 'b',
        task_mode: 'WORK',
        completed_at: new Date(2026, 4, 13, 9).toISOString(),
      }),
      makeTask({
        id: 'c',
        task_mode: 'PLAY',
        completed_at: new Date(2026, 4, 14, 9).toISOString(),
      }),
      makeTask({
        id: 'd',
        task_mode: 'RELAX',
        completed_at: new Date(2026, 4, 15, 9).toISOString(),
      }),
      // Outside window — ignored.
      makeTask({
        id: 'e',
        task_mode: 'WORK',
        completed_at: new Date(2026, 4, 1, 9).toISOString(),
      }),
      // Uncategorized — ignored.
      makeTask({
        id: 'f',
        task_mode: 'UNCATEGORIZED',
        completed_at: new Date(2026, 4, 13, 9).toISOString(),
      }),
      // Incomplete — ignored (no completed_at).
      makeTask({ id: 'g', task_mode: 'WORK', completed_at: '' }),
    ];

    const report = computeWeeklyModeReport(tasks, REFERENCE);
    expect(report.totalTracked).toBe(4);
    expect(report.currentCounts.WORK).toBe(2);
    expect(report.currentCounts.PLAY).toBe(1);
    expect(report.currentCounts.RELAX).toBe(1);
    expect(report.currentRatios.WORK).toBeCloseTo(0.5, 5);
    expect(report.currentRatios.PLAY).toBeCloseTo(0.25, 5);
    expect(report.currentRatios.RELAX).toBeCloseTo(0.25, 5);
    expect(report.dominantMode).toBe('WORK');
  });

  it('returns prior-week counts and delta in percentage points', () => {
    const tasks: Task[] = [
      // Current week: one PLAY
      makeTask({
        task_mode: 'PLAY',
        completed_at: new Date(2026, 4, 13, 9).toISOString(),
      }),
      // Prior week: two RELAX
      makeTask({
        task_mode: 'RELAX',
        completed_at: new Date(2026, 4, 5, 9).toISOString(),
      }),
      makeTask({
        task_mode: 'RELAX',
        completed_at: new Date(2026, 4, 6, 9).toISOString(),
      }),
    ];
    const report = computeWeeklyModeReport(tasks, REFERENCE);
    expect(report.priorCounts.RELAX).toBe(2);
    expect(report.priorCounts.PLAY).toBe(0);
    expect(modeDeltaPercentPoints(report.currentRatios, report.priorRatios, 'RELAX'))
      .toBe(-100);
    expect(modeDeltaPercentPoints(report.currentRatios, report.priorRatios, 'PLAY'))
      .toBe(100);
  });

  it('produces a 7-entry per-day breakdown with per-mode counts', () => {
    const tasks: Task[] = [
      makeTask({
        task_mode: 'WORK',
        completed_at: new Date(2026, 4, 12, 9).toISOString(),
      }),
      makeTask({
        task_mode: 'WORK',
        completed_at: new Date(2026, 4, 12, 10).toISOString(),
      }),
      makeTask({
        task_mode: 'PLAY',
        completed_at: new Date(2026, 4, 14, 11).toISOString(),
      }),
    ];
    const report = computeWeeklyModeReport(tasks, REFERENCE);
    expect(report.perDay).toHaveLength(7);
    const tuesday = report.perDay.find((d) => d.counts.WORK === 2);
    expect(tuesday).toBeDefined();
    expect(tuesday!.total).toBe(2);
    const thursday = report.perDay.find((d) => d.counts.PLAY === 1);
    expect(thursday).toBeDefined();
    expect(thursday!.total).toBe(1);
  });

  it('emits 4-week trend arrays of length 4 per mode', () => {
    const tasks: Task[] = [
      makeTask({
        task_mode: 'WORK',
        completed_at: new Date(2026, 4, 13, 9).toISOString(),
      }),
      makeTask({
        task_mode: 'PLAY',
        completed_at: new Date(2026, 4, 6, 9).toISOString(),
      }),
    ];
    const report = computeWeeklyModeReport(tasks, REFERENCE);
    expect(report.fourWeekTrend.WORK).toHaveLength(4);
    expect(report.fourWeekTrend.PLAY).toHaveLength(4);
    expect(report.fourWeekTrend.RELAX).toHaveLength(4);
    expect(report.fourWeekCounts.WORK[3]).toBe(1);
    expect(report.fourWeekCounts.PLAY[2]).toBe(1);
  });

  it('returns empty ratios and dominant=UNCATEGORIZED when no completions in window', () => {
    const report = computeWeeklyModeReport([], REFERENCE);
    expect(report.totalTracked).toBe(0);
    expect(report.currentRatios.WORK).toBe(0);
    expect(report.currentRatios.PLAY).toBe(0);
    expect(report.currentRatios.RELAX).toBe(0);
    expect(report.dominantMode).toBe('UNCATEGORIZED');
    expect(report.perDay).toHaveLength(7);
  });

  it('uncategorized completions in window do not contribute to totalTracked', () => {
    const tasks: Task[] = [
      makeTask({
        task_mode: 'UNCATEGORIZED',
        completed_at: new Date(2026, 4, 13, 9).toISOString(),
      }),
      makeTask({
        task_mode: null,
        completed_at: new Date(2026, 4, 13, 10).toISOString(),
      }),
    ];
    const report = computeWeeklyModeReport(tasks, REFERENCE);
    expect(report.totalTracked).toBe(0);
  });
});
