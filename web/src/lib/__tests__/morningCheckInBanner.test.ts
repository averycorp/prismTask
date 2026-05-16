import { describe, it, expect } from 'vitest';
import {
  shouldShowMorningCheckInBanner,
  DEFAULT_MORNING_CHECKIN_CUTOFF_HOUR,
} from '@/lib/morningCheckInBanner';

// Helper: build a millis timestamp for an absolute wall-clock time. We
// use UTC to avoid the host-timezone hazard — the decider does pure
// arithmetic on epoch millis so wall-clock choice doesn't matter, only
// the relative offset between `now` and `todayStart` does.
function ts(year: number, month: number, day: number, hour: number, minute = 0): number {
  return Date.UTC(year, month - 1, day, hour, minute);
}

describe('shouldShowMorningCheckInBanner', () => {
  const todayStart = ts(2026, 5, 15, 6, 0); // SoD = 6:00

  const baseInput = {
    todayStart,
    sodHour: 6,
    sodMinute: 0,
    cutoffHour: 10, // 4-hour window 6:00 → 10:00
    featureEnabled: true,
    alreadyCheckedInToday: false,
    dismissedToday: false,
  };

  it('hides the banner before Start-of-Day (now < todayStart)', () => {
    expect(
      shouldShowMorningCheckInBanner({
        ...baseInput,
        now: ts(2026, 5, 15, 5, 30),
      }),
    ).toBe(false);
  });

  it('shows the banner just after Start-of-Day', () => {
    expect(
      shouldShowMorningCheckInBanner({
        ...baseInput,
        now: ts(2026, 5, 15, 6, 5),
      }),
    ).toBe(true);
  });

  it('hides the banner once the cutoff hour passes', () => {
    expect(
      shouldShowMorningCheckInBanner({
        ...baseInput,
        now: ts(2026, 5, 15, 10, 1),
      }),
    ).toBe(false);
  });

  it('shows the banner inside the window (mid-morning)', () => {
    expect(
      shouldShowMorningCheckInBanner({
        ...baseInput,
        now: ts(2026, 5, 15, 8, 0),
      }),
    ).toBe(true);
  });

  it('hides the banner when the user already checked in today', () => {
    expect(
      shouldShowMorningCheckInBanner({
        ...baseInput,
        now: ts(2026, 5, 15, 7, 0),
        alreadyCheckedInToday: true,
      }),
    ).toBe(false);
  });

  it('hides the banner when the user dismissed it today', () => {
    expect(
      shouldShowMorningCheckInBanner({
        ...baseInput,
        now: ts(2026, 5, 15, 7, 0),
        dismissedToday: true,
      }),
    ).toBe(false);
  });

  it('hides the banner when the feature is disabled', () => {
    expect(
      shouldShowMorningCheckInBanner({
        ...baseInput,
        now: ts(2026, 5, 15, 7, 0),
        featureEnabled: false,
      }),
    ).toBe(false);
  });

  it('handles late SoD wrapping into the next calendar day', () => {
    // SoD = 22:00, cutoff = 02:00 → 4-hour window 22:00 → 02:00 next day.
    const lateSodTodayStart = ts(2026, 5, 15, 22, 0);
    // Inside window: 23:00 same day.
    expect(
      shouldShowMorningCheckInBanner({
        todayStart: lateSodTodayStart,
        sodHour: 22,
        sodMinute: 0,
        cutoffHour: 2,
        featureEnabled: true,
        alreadyCheckedInToday: false,
        dismissedToday: false,
        now: ts(2026, 5, 15, 23, 0),
      }),
    ).toBe(true);
    // Inside window: 01:30 next day.
    expect(
      shouldShowMorningCheckInBanner({
        todayStart: lateSodTodayStart,
        sodHour: 22,
        sodMinute: 0,
        cutoffHour: 2,
        featureEnabled: true,
        alreadyCheckedInToday: false,
        dismissedToday: false,
        now: ts(2026, 5, 16, 1, 30),
      }),
    ).toBe(true);
    // Outside window: 03:00 next day.
    expect(
      shouldShowMorningCheckInBanner({
        todayStart: lateSodTodayStart,
        sodHour: 22,
        sodMinute: 0,
        cutoffHour: 2,
        featureEnabled: true,
        alreadyCheckedInToday: false,
        dismissedToday: false,
        now: ts(2026, 5, 16, 3, 0),
      }),
    ).toBe(false);
  });

  it('uses sane defaults when sodMinute is omitted', () => {
    expect(
      shouldShowMorningCheckInBanner({
        todayStart,
        sodHour: 6,
        cutoffHour: 10,
        featureEnabled: true,
        alreadyCheckedInToday: false,
        dismissedToday: false,
        now: ts(2026, 5, 15, 9, 0),
      }),
    ).toBe(true);
  });

  it('exports a sane default cutoff matching Android (11)', () => {
    expect(DEFAULT_MORNING_CHECKIN_CUTOFF_HOUR).toBe(11);
  });
});
