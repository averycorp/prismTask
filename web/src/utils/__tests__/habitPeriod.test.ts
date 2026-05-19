import { describe, it, expect } from 'vitest';
import { getPeriodBounds, periodNoun, periodLabel } from '@/utils/habitPeriod';

describe('habitPeriod.getPeriodBounds', () => {
  it('weekly: Mon → Sun containing the reference date', () => {
    // 2026-05-18 is a Monday.
    expect(getPeriodBounds('weekly', '2026-05-18')).toEqual({
      startIso: '2026-05-18',
      endIso: '2026-05-24',
    });
    // 2026-05-24 is the Sunday of the same week.
    expect(getPeriodBounds('weekly', '2026-05-24')).toEqual({
      startIso: '2026-05-18',
      endIso: '2026-05-24',
    });
    // 2026-05-22 is a Friday — same week.
    expect(getPeriodBounds('weekly', '2026-05-22')).toEqual({
      startIso: '2026-05-18',
      endIso: '2026-05-24',
    });
  });

  it('fortnightly: 14-day window anchored on odd ISO weeks', () => {
    // ISO week 21 (odd) starts Mon 2026-05-18 and the fortnight runs to
    // Sun 2026-05-31 (covering even ISO week 22).
    expect(getPeriodBounds('fortnightly', '2026-05-18')).toEqual({
      startIso: '2026-05-18',
      endIso: '2026-05-31',
    });
    // ISO week 22 (even) — the fortnight started a week earlier.
    expect(getPeriodBounds('fortnightly', '2026-05-26')).toEqual({
      startIso: '2026-05-18',
      endIso: '2026-05-31',
    });
  });

  it('monthly: 1st → last day of the calendar month', () => {
    expect(getPeriodBounds('monthly', '2026-05-18')).toEqual({
      startIso: '2026-05-01',
      endIso: '2026-05-31',
    });
    // February in a non-leap year ends on the 28th.
    expect(getPeriodBounds('monthly', '2026-02-15')).toEqual({
      startIso: '2026-02-01',
      endIso: '2026-02-28',
    });
  });

  it('bimonthly: 2-month blocks aligned Jan-Feb, Mar-Apr, …', () => {
    // 2026-05 (May) lives in the May-Jun bimonth.
    expect(getPeriodBounds('bimonthly', '2026-05-18')).toEqual({
      startIso: '2026-05-01',
      endIso: '2026-06-30',
    });
    // 2026-01 lives in the Jan-Feb bimonth.
    expect(getPeriodBounds('bimonthly', '2026-01-20')).toEqual({
      startIso: '2026-01-01',
      endIso: '2026-02-28',
    });
  });

  it('quarterly: Q1/Q2/Q3/Q4 buckets', () => {
    // 2026-05 → Q2 (Apr-Jun).
    expect(getPeriodBounds('quarterly', '2026-05-18')).toEqual({
      startIso: '2026-04-01',
      endIso: '2026-06-30',
    });
    // 2026-11 → Q4 (Oct-Dec).
    expect(getPeriodBounds('quarterly', '2026-11-05')).toEqual({
      startIso: '2026-10-01',
      endIso: '2026-12-31',
    });
  });

  it('daily throws — callers must branch first', () => {
    expect(() => getPeriodBounds('daily', '2026-05-18')).toThrow();
  });
});

describe('habitPeriod.periodNoun / periodLabel', () => {
  it('matches the Android `HabitCard.kt` period vocabulary', () => {
    expect(periodNoun('weekly')).toBe('week');
    expect(periodNoun('fortnightly')).toBe('fortnight');
    expect(periodNoun('monthly')).toBe('month');
    expect(periodNoun('bimonthly')).toBe('period');
    expect(periodNoun('quarterly')).toBe('quarter');
    expect(periodNoun('daily')).toBe('day');

    expect(periodLabel('weekly')).toBe('this week');
    expect(periodLabel('quarterly')).toBe('this quarter');
  });
});
