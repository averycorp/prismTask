/**
 * Pure-function decider for the Today-screen morning check-in banner.
 *
 * Web port of Android's
 * `app/src/main/java/com/averycorp/prismtask/domain/usecase/MorningCheckInBannerDecider.kt`.
 *
 * Replaces a naive `now.getHours() < 11` predicate that ignored both
 * ends of the user's Start-of-Day window: the banner appeared between
 * calendar midnight and SoD (when the user's day hadn't started yet),
 * and the `MorningCheckInPromptCutoff` Settings slider had no effect on
 * the banner.
 *
 * See `docs/audits/MORNING_CHECKIN_SOD_BOUNDARY_AUDIT.md` for context.
 *
 * @file Web pillar parity port of `MorningCheckInBannerDecider`.
 */

const MINUTES_PER_HOUR = 60;
const MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;
const MILLIS_PER_MINUTE = 60_000;

export interface MorningCheckInBannerInput {
  /** Wall-clock now in epoch millis. */
  now: number;
  /** SoD-anchored start of the user's logical today, in epoch millis. */
  todayStart: number;
  /** User's configured Start-of-Day hour (0..23). */
  sodHour: number;
  /** User's configured Start-of-Day minute (0..59). Defaults to 0 on web. */
  sodMinute?: number;
  /** Wall-clock cutoff hour (0..23) from advanced-tuning prefs. */
  cutoffHour: number;
  /** Whether the morning check-in feature is enabled. */
  featureEnabled: boolean;
  /** True if a check-in log already exists for today. */
  alreadyCheckedInToday: boolean;
  /** True if the user dismissed the banner today. */
  dismissedToday: boolean;
}

/**
 * Returns true when the morning check-in banner should be visible.
 *
 * The visible window is `[todayStart, todayStart + offset)` where
 * `offset` is the wall-clock distance from SoD to `cutoffHour`. The
 * cutoff is interpreted as a wall-clock hour: if it's less than or
 * equal to the SoD hour, it's treated as falling on the wall-clock day
 * after SoD (so SoD = 22, cutoff = 2 means a 4-hour window 22:00–02:00).
 * A cutoff of exactly the SoD hour collapses to a 24-hour window —
 * uncommon but consistent with the "morning starts at SoD" mental
 * model.
 */
export function shouldShowMorningCheckInBanner(
  input: MorningCheckInBannerInput,
): boolean {
  const {
    now,
    todayStart,
    sodHour,
    sodMinute = 0,
    cutoffHour,
    featureEnabled,
    alreadyCheckedInToday,
    dismissedToday,
  } = input;
  if (!featureEnabled) return false;
  if (alreadyCheckedInToday) return false;
  if (dismissedToday) return false;
  if (now < todayStart) return false;
  const offsetMinutes = windowOffsetMinutes(sodHour, sodMinute, cutoffHour);
  const cutoffMillis = todayStart + offsetMinutes * MILLIS_PER_MINUTE;
  return now < cutoffMillis;
}

function windowOffsetMinutes(
  sodHour: number,
  sodMinute: number,
  cutoffHour: number,
): number {
  const sodMinutes = sodHour * MINUTES_PER_HOUR + sodMinute;
  const cutoffMinutes = cutoffHour * MINUTES_PER_HOUR;
  const raw = cutoffMinutes - sodMinutes;
  return raw <= 0 ? raw + MINUTES_PER_DAY : raw;
}

/** Default cutoff hour matches Android's `MorningCheckInPromptCutoff(11)`. */
export const DEFAULT_MORNING_CHECKIN_CUTOFF_HOUR = 11;
