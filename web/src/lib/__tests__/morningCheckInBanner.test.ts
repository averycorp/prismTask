import { describe, it, expect } from 'vitest';
import {
  shouldShowMorningCheckInBanner,
  DEFAULT_MORNING_CHECKIN_WINDOW_HOURS,
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
    windowHours: 4, // 4-hour window 6:00 → 10:00
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

  it('hides the banner once the window elapses', () => {
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

  it('handles late SoD that wraps into the next calendar day', () => {
    // SoD = 22:00, 4h window → 22:00 → 02:00 next day. The decider
    // computes the cutoff from `todayStart` directly, so wall-clock
    // midnight is irrelevant.
    const lateSodTodayStart = ts(2026, 5, 15, 22, 0);
    expect(
      shouldShowMorningCheckInBanner({
        todayStart: lateSodTodayStart,
        windowHours: 4,
        featureEnabled: true,
        alreadyCheckedInToday: false,
        dismissedToday: false,
        now: ts(2026, 5, 15, 23, 0), // 23:00 same day
      }),
    ).toBe(true);
    expect(
      shouldShowMorningCheckInBanner({
        todayStart: lateSodTodayStart,
        windowHours: 4,
        featureEnabled: true,
        alreadyCheckedInToday: false,
        dismissedToday: false,
        now: ts(2026, 5, 16, 1, 30), // 01:30 next day
      }),
    ).toBe(true);
    expect(
      shouldShowMorningCheckInBanner({
        todayStart: lateSodTodayStart,
        windowHours: 4,
        featureEnabled: true,
        alreadyCheckedInToday: false,
        dismissedToday: false,
        now: ts(2026, 5, 16, 3, 0), // past the 4h window
      }),
    ).toBe(false);
  });

  it('coerces out-of-range windowHours to the valid 1..24 range', () => {
    expect(
      shouldShowMorningCheckInBanner({
        ...baseInput,
        windowHours: 0,
        now: ts(2026, 5, 15, 6, 30), // 30m into the coerced 1h window
      }),
    ).toBe(true);
  });

  it('exports the default window matching Android (12 hours)', () => {
    expect(DEFAULT_MORNING_CHECKIN_WINDOW_HOURS).toBe(12);
  });
});
