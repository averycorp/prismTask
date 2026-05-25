import { describe, it, expect } from 'vitest';
import { addDays, startOfWeek, format } from 'date-fns';
import { weekOffsetFromMonday } from '../calendarDay';

describe('weekOffsetFromMonday (B-11 mobile day defaults to today)', () => {
  it('maps Monday→0 … Sunday→6', () => {
    // 2026-05-18 is a Monday, 2026-05-24 is a Sunday.
    expect(weekOffsetFromMonday(new Date(2026, 4, 18))).toBe(0);
    expect(weekOffsetFromMonday(new Date(2026, 4, 24))).toBe(6);
  });

  it('round-trips: startOfWeek + offset returns the same calendar day', () => {
    for (let i = 0; i < 7; i++) {
      const day = new Date(2026, 4, 18 + i); // Mon … Sun
      const reconstructed = addDays(
        startOfWeek(day, { weekStartsOn: 1 }),
        weekOffsetFromMonday(day),
      );
      expect(format(reconstructed, 'yyyy-MM-dd')).toBe(format(day, 'yyyy-MM-dd'));
    }
  });

  it('selects today when the week is the current week', () => {
    const today = new Date();
    const selected = addDays(
      startOfWeek(today, { weekStartsOn: 1 }),
      weekOffsetFromMonday(today),
    );
    expect(format(selected, 'yyyy-MM-dd')).toBe(format(today, 'yyyy-MM-dd'));
  });
});
