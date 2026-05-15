import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  endOfTodayMs,
  startOfDaysFromNowMs,
  startOfTodayMs,
} from '@/api/firestore/converters';

describe('today-window helpers honor Start-of-Day', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('SoD = 0: today window is local midnight → tomorrow midnight', () => {
    vi.setSystemTime(new Date(2026, 3, 23, 14, 30));
    expect(startOfTodayMs(0)).toBe(new Date(2026, 3, 23, 0, 0, 0, 0).getTime());
    expect(endOfTodayMs(0)).toBe(new Date(2026, 3, 24, 0, 0, 0, 0).getTime());
    expect(startOfDaysFromNowMs(7, 0)).toBe(
      new Date(2026, 3, 30, 0, 0, 0, 0).getTime(),
    );
  });

  it('pre-SoD window: window is yesterday-midnight → today-midnight (regression: web was showing tomorrow)', () => {
    // 2026-04-23 02:15 with SoD = 4 → logical day = 2026-04-22.
    // BEFORE THIS FIX: startOfTodayMs returned 2026-04-23 00:00, so the
    // Today screen showed tasks dated 2026-04-23 (tomorrow) and hid
    // tasks dated 2026-04-22 (the user's current logical day).
    vi.setSystemTime(new Date(2026, 3, 23, 2, 15));
    expect(startOfTodayMs(4)).toBe(new Date(2026, 3, 22, 0, 0, 0, 0).getTime());
    expect(endOfTodayMs(4)).toBe(new Date(2026, 3, 23, 0, 0, 0, 0).getTime());
    // Upcoming should start at the end of the *logical* today.
    expect(startOfDaysFromNowMs(1, 4)).toBe(
      new Date(2026, 3, 23, 0, 0, 0, 0).getTime(),
    );
  });

  it('post-SoD window: same calendar date as wall clock', () => {
    vi.setSystemTime(new Date(2026, 3, 23, 5, 30));
    expect(startOfTodayMs(4)).toBe(new Date(2026, 3, 23, 0, 0, 0, 0).getTime());
    expect(endOfTodayMs(4)).toBe(new Date(2026, 3, 24, 0, 0, 0, 0).getTime());
  });
});
