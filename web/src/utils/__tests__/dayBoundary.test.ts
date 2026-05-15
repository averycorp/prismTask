import { describe, it, expect } from 'vitest';
import {
  calendarMidnightOfLogicalDayMs,
  calendarMidnightOfNextLogicalDayMs,
  clampHour,
  endOfLogicalDayMs,
  logicalToday,
  startOfLogicalDayMs,
} from '@/utils/dayBoundary';

describe('dayBoundary', () => {
  it('clampHour keeps 0–23 and guards invalid input', () => {
    expect(clampHour(0)).toBe(0);
    expect(clampHour(23)).toBe(23);
    expect(clampHour(-1)).toBe(0);
    expect(clampHour(24)).toBe(23);
    expect(clampHour(Number.NaN)).toBe(0);
    expect(clampHour(5.4)).toBe(5);
    expect(clampHour(5.6)).toBe(6);
  });

  it('uses calendar midnight when SoD = 0 (default)', () => {
    // 2026-04-23 03:15 local.
    const now = new Date(2026, 3, 23, 3, 15);
    const start = startOfLogicalDayMs(now, 0);
    const expected = new Date(2026, 3, 23, 0, 0, 0, 0).getTime();
    expect(start).toBe(expected);
    expect(logicalToday(now, 0)).toBe('2026-04-23');
  });

  it('rolls back one calendar day when the moment is before SoD', () => {
    // 2026-04-23 03:15 local, SoD = 4 → logical day started 2026-04-22 04:00.
    const now = new Date(2026, 3, 23, 3, 15);
    const start = startOfLogicalDayMs(now, 4);
    const expected = new Date(2026, 3, 22, 4, 0, 0, 0).getTime();
    expect(start).toBe(expected);
    expect(logicalToday(now, 4)).toBe('2026-04-22');
  });

  it('uses today when the moment is after SoD', () => {
    // 2026-04-23 05:30 local, SoD = 4 → logical day started 2026-04-23 04:00.
    const now = new Date(2026, 3, 23, 5, 30);
    const start = startOfLogicalDayMs(now, 4);
    const expected = new Date(2026, 3, 23, 4, 0, 0, 0).getTime();
    expect(start).toBe(expected);
    expect(logicalToday(now, 4)).toBe('2026-04-23');
  });

  it('endOfLogicalDayMs is start + 24h', () => {
    const now = new Date(2026, 3, 23, 12, 0);
    expect(endOfLogicalDayMs(now, 0) - startOfLogicalDayMs(now, 0)).toBe(
      86_400_000,
    );
    expect(endOfLogicalDayMs(now, 4) - startOfLogicalDayMs(now, 4)).toBe(
      86_400_000,
    );
  });

  describe('calendarMidnightOfLogicalDayMs', () => {
    it('returns local midnight of the calendar date when SoD = 0', () => {
      const now = new Date(2026, 3, 23, 14, 30);
      expect(calendarMidnightOfLogicalDayMs(now, 0)).toBe(
        new Date(2026, 3, 23, 0, 0, 0, 0).getTime(),
      );
    });

    it('snaps to midnight of the *previous* calendar date in the pre-SoD window', () => {
      // 2026-04-23 02:15 with SoD = 4 → logical day is 2026-04-22, so the
      // calendar-midnight window starts at 2026-04-22 00:00, NOT 2026-04-23 00:00.
      // This is the bug shape: web was showing tomorrow's tasks before SoD.
      const now = new Date(2026, 3, 23, 2, 15);
      expect(calendarMidnightOfLogicalDayMs(now, 4)).toBe(
        new Date(2026, 3, 22, 0, 0, 0, 0).getTime(),
      );
    });

    it('returns midnight of the same calendar date once SoD has passed', () => {
      const now = new Date(2026, 3, 23, 5, 30);
      expect(calendarMidnightOfLogicalDayMs(now, 4)).toBe(
        new Date(2026, 3, 23, 0, 0, 0, 0).getTime(),
      );
    });

    it('next-day helper is +24h from current-day helper', () => {
      const now = new Date(2026, 3, 23, 2, 15);
      expect(
        calendarMidnightOfNextLogicalDayMs(now, 4) -
          calendarMidnightOfLogicalDayMs(now, 4),
      ).toBe(86_400_000);
    });
  });
});
