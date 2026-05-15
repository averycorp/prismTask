/**
 * Start-of-day boundary helpers — web port of Android's `DayBoundary`.
 *
 * Users can configure the hour (0–23) at which the "logical day" rolls
 * over. A task scheduled at 2am when startOfDay = 4 logically belongs
 * to *yesterday*, not today. Defaults to 0 (midnight) for users who
 * haven't explicitly set it.
 *
 * The helpers below are pure and work entirely from a reference
 * `Date` (usually `new Date()`) so they're trivially testable.
 */

const DAY_MS = 86_400_000;

export function clampHour(hour: number): number {
  if (!Number.isFinite(hour)) return 0;
  const n = Math.round(hour);
  if (n < 0) return 0;
  if (n > 23) return 23;
  return n;
}

/**
 * Return the start-of-today-in-millis for the given reference instant
 * and SoD hour. "Today" is the logical day that contains `now`.
 *
 *   now = 2026-04-23 03:15, startOfDay = 4  → 2026-04-22 04:00
 *   now = 2026-04-23 05:30, startOfDay = 4  → 2026-04-23 04:00
 *   now = 2026-04-23 12:00, startOfDay = 0  → 2026-04-23 00:00
 */
export function startOfLogicalDayMs(
  now: Date | number = Date.now(),
  startOfDayHour: number = 0,
): number {
  const hour = clampHour(startOfDayHour);
  const d = typeof now === 'number' ? new Date(now) : new Date(now.getTime());
  const currentHour = d.getHours();
  d.setHours(hour, 0, 0, 0);
  // If the reference instant is earlier than today's SoD, logical day
  // started on the previous calendar day.
  if (currentHour < hour) {
    d.setTime(d.getTime() - DAY_MS);
  }
  return d.getTime();
}

export function endOfLogicalDayMs(
  now: Date | number = Date.now(),
  startOfDayHour: number = 0,
): number {
  return startOfLogicalDayMs(now, startOfDayHour) + DAY_MS;
}

/**
 * Local midnight of the *logical* date — i.e. the calendar date that
 * `logicalToday` resolves to, snapped to 00:00 instead of `startOfDayHour`.
 *
 * This is the right window edge for queries that filter task `dueDate`
 * (Android stores timeless dates at local midnight via the same
 * `dateStrToTimestamp` shape), so a task dated `2026-04-22` is included
 * for a user reading their Today screen at 2 AM on the 23rd with SoD = 4
 * — the user's logical day is still the 22nd, and a midnight-aligned
 * window `[Apr 22 00:00, Apr 23 00:00)` contains it.
 *
 * Mirrors Android's `DayBoundary.calendarMidnightOfCurrentDay`.
 */
export function calendarMidnightOfLogicalDayMs(
  now: Date | number = Date.now(),
  startOfDayHour: number = 0,
): number {
  const d = new Date(startOfLogicalDayMs(now, startOfDayHour));
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** Calendar midnight of the day *after* `calendarMidnightOfLogicalDayMs`. */
export function calendarMidnightOfNextLogicalDayMs(
  now: Date | number = Date.now(),
  startOfDayHour: number = 0,
): number {
  return calendarMidnightOfLogicalDayMs(now, startOfDayHour) + DAY_MS;
}

/** ISO `YYYY-MM-DD` of the logical day containing `now`. */
export function logicalToday(
  now: Date | number = Date.now(),
  startOfDayHour: number = 0,
): string {
  const d = new Date(startOfLogicalDayMs(now, startOfDayHour));
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}
