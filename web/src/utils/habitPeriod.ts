import {
  startOfWeek,
  endOfWeek,
  startOfMonth,
  endOfMonth,
  addMonths,
  subDays,
  getISOWeek,
  parseISO,
  format,
} from 'date-fns';
import type { HabitFrequency } from '@/types/habit';

export interface PeriodBounds {
  /** ISO `YYYY-MM-DD` for the first day of the period (inclusive). */
  startIso: string;
  /** ISO `YYYY-MM-DD` for the last day of the period (inclusive). */
  endIso: string;
}

function toIso(d: Date): string {
  return format(d, 'yyyy-MM-dd');
}

/**
 * Period boundaries for a non-daily habit, mirroring Android's
 * `HabitRepository.getWeekStart` / `getFortnightStart` / `getMonthStart`
 * / `getBimonthStart` / `getQuarterStart` (and the matching end helpers).
 *
 * - `weekly`: Monday → Sunday containing the reference date.
 * - `fortnightly`: 14-day window anchored on odd ISO weeks (so Mon of
 *   ISO week N where N is odd; the following Sunday closes the window).
 * - `monthly`: 1st → last day of the calendar month.
 * - `bimonthly`: 2-month blocks aligned to Jan-Feb, Mar-Apr, … so the
 *   start month index is `(month >> 1) << 1` (i.e. clear the low bit).
 * - `quarterly`: standard Q1/Q2/Q3/Q4 — start month index is
 *   `Math.floor(month / 3) * 3`.
 *
 * `daily` is intentionally not handled here — callers should branch on
 * frequency before delegating. The function throws if a daily habit is
 * passed so a regression doesn't silently bucket per-day completions
 * into a week-wide window.
 */
export function getPeriodBounds(
  frequency: HabitFrequency,
  referenceIso: string,
): PeriodBounds {
  const ref = parseISO(referenceIso);
  switch (frequency) {
    case 'weekly': {
      const start = startOfWeek(ref, { weekStartsOn: 1 });
      const end = endOfWeek(ref, { weekStartsOn: 1 });
      return { startIso: toIso(start), endIso: toIso(end) };
    }
    case 'fortnightly': {
      // Anchor on Monday, then back off one more week if the resulting
      // ISO week is even — odd ISO weeks start the fortnight (parity
      // with Android's `weekNum % 2 == 0` branch).
      let start = startOfWeek(ref, { weekStartsOn: 1 });
      if (getISOWeek(start) % 2 === 0) {
        start = subDays(start, 7);
      }
      const end = subDays(addDays(start, 14), 1);
      return { startIso: toIso(start), endIso: toIso(end) };
    }
    case 'monthly': {
      return {
        startIso: toIso(startOfMonth(ref)),
        endIso: toIso(endOfMonth(ref)),
      };
    }
    case 'bimonthly': {
      const month = ref.getMonth();
      const startMonth = month - (month % 2);
      const start = new Date(ref.getFullYear(), startMonth, 1);
      const end = endOfMonth(addMonths(start, 1));
      return { startIso: toIso(start), endIso: toIso(end) };
    }
    case 'quarterly': {
      const month = ref.getMonth();
      const startMonth = Math.floor(month / 3) * 3;
      const start = new Date(ref.getFullYear(), startMonth, 1);
      const end = endOfMonth(addMonths(start, 2));
      return { startIso: toIso(start), endIso: toIso(end) };
    }
    case 'daily':
      throw new Error(
        'getPeriodBounds: daily habits have no multi-day period — branch before calling',
      );
  }
}

function addDays(d: Date, days: number): Date {
  const r = new Date(d);
  r.setDate(r.getDate() + days);
  return r;
}

/** Human-readable noun for the period (`week`, `month`, …). */
export function periodNoun(frequency: HabitFrequency): string {
  switch (frequency) {
    case 'weekly':
      return 'week';
    case 'fortnightly':
      return 'fortnight';
    case 'monthly':
      return 'month';
    case 'bimonthly':
      return 'period';
    case 'quarterly':
      return 'quarter';
    case 'daily':
      return 'day';
  }
}

/** "this {noun}" phrase for use in card subtext. */
export function periodLabel(frequency: HabitFrequency): string {
  return `this ${periodNoun(frequency)}`;
}
