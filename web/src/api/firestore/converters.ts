/**
 * Conversion utilities between Firestore document format (Android)
 * and web app types.
 *
 * Firestore field names are camelCase (matching Android SyncMapper).
 * Timestamps are stored as milliseconds (Long) in Firestore.
 *
 * Priority mapping:
 *   Android: 0=None, 1=Low, 2=Medium, 3=High, 4=Urgent
 *   Web:     1=Urgent, 2=High, 3=Medium, 4=Low
 */

import { format } from 'date-fns';

import {
  calendarMidnightOfLogicalDayMs,
  calendarMidnightOfNextLogicalDayMs,
} from '@/utils/dayBoundary';

// ── Timestamp helpers ──────────────────────────────────────────

export function timestampToDateStr(millis: number | null | undefined): string | null {
  if (millis == null) return null;
  return format(new Date(millis), 'yyyy-MM-dd');
}

export function timestampToTimeStr(millis: number | null | undefined): string | null {
  if (millis == null) return null;
  return format(new Date(millis), 'HH:mm');
}

export function timestampToIso(millis: number | null | undefined): string | null {
  if (millis == null) return null;
  return new Date(millis).toISOString();
}

export function dateStrToTimestamp(dateStr: string | null | undefined): number | null {
  if (!dateStr) return null;
  return new Date(dateStr + 'T00:00:00').getTime();
}

export function isoToTimestamp(iso: string | null | undefined): number | null {
  if (!iso) return null;
  return new Date(iso).getTime();
}

/**
 * Calendar midnight of the user's *logical* today.
 *
 * Honors `startOfDayHour` so a user opening Today at 02:00 with SoD = 4
 * sees their previous calendar day's tasks (the day they're still in,
 * logically), not the next day's. Mirrors Android's
 * `DayBoundary.calendarMidnightOfCurrentDay`. Required parameter — every
 * caller has access to the SoD pref via `useSettingsStore`, and a silent
 * default would re-introduce the bug it was added to fix.
 */
export function startOfTodayMs(startOfDayHour: number): number {
  return calendarMidnightOfLogicalDayMs(Date.now(), startOfDayHour);
}

/** Calendar midnight of the day after the logical today. */
export function endOfTodayMs(startOfDayHour: number): number {
  return calendarMidnightOfNextLogicalDayMs(Date.now(), startOfDayHour);
}

/**
 * Calendar midnight `days` days after the logical today, used as the
 * upper bound of the "upcoming" window. `days = 1` aligns with
 * `endOfTodayMs(...)`.
 */
export function startOfDaysFromNowMs(
  days: number,
  startOfDayHour: number,
): number {
  return startOfTodayMs(startOfDayHour) + days * 86_400_000;
}

// ── Priority helpers ───────────────────────────────────────────

export function androidToWebPriority(androidPri: number): 1 | 2 | 3 | 4 {
  // Android 4→Web 1, 3→2, 2→3, 1→4, 0→4
  if (androidPri >= 4) return 1;
  if (androidPri === 3) return 2;
  if (androidPri === 2) return 3;
  return 4;
}

export function webToAndroidPriority(webPri: number): number {
  // Web 1→Android 4, 2→3, 3→2, 4→1
  if (webPri <= 1) return 4;
  if (webPri === 2) return 3;
  if (webPri === 3) return 2;
  return 1;
}
