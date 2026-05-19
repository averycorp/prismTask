/**
 * Pure-function decider for the Today-screen morning check-in surface.
 *
 * Web port of Android's
 * `app/src/main/java/com/averycorp/prismtask/domain/usecase/MorningCheckInBannerDecider.kt`.
 *
 * The visible window is `[todayStart, todayStart + windowHours)` — the
 * morning check-in banner and card are available for exactly
 * `windowHours` after the user's Start-of-Day and disappear once that
 * span has elapsed.
 */

const MILLIS_PER_HOUR = 60 * 60_000;

export interface MorningCheckInBannerInput {
  /** Wall-clock now in epoch millis. */
  now: number;
  /** SoD-anchored start of the user's logical today, in epoch millis. */
  todayStart: number;
  /** Length of the availability window, in hours after SoD (1..24). */
  windowHours: number;
  /** Whether the morning check-in feature is enabled. */
  featureEnabled: boolean;
  /** True if a check-in log already exists for today. */
  alreadyCheckedInToday: boolean;
  /** True if the user dismissed the banner today. */
  dismissedToday: boolean;
}

/**
 * Returns true when the morning check-in surface should be visible.
 */
export function shouldShowMorningCheckInBanner(
  input: MorningCheckInBannerInput,
): boolean {
  const {
    now,
    todayStart,
    windowHours,
    featureEnabled,
    alreadyCheckedInToday,
    dismissedToday,
  } = input;
  if (!featureEnabled) return false;
  if (alreadyCheckedInToday) return false;
  if (dismissedToday) return false;
  if (now < todayStart) return false;
  const clamped = Math.max(1, Math.min(24, Math.trunc(windowHours)));
  const cutoffMillis = todayStart + clamped * MILLIS_PER_HOUR;
  return now < cutoffMillis;
}

/** Default availability window matches Android's `MorningCheckInPromptCutoff(windowHours = 12)`. */
export const DEFAULT_MORNING_CHECKIN_WINDOW_HOURS = 12;
