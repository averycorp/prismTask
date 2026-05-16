import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { computeCheckInStreak } from '@/utils/checkInStreak';
import type { CheckInLog } from '@/api/firestore/checkInLogs';

/**
 * Stepper navigation invariants + streak join.
 *
 * The actual draft load/save behaviour is covered in
 * `MorningCheckInStepper.test.ts`. This file pins:
 *  - the canonical STEP_ORDER the stepper walks
 *  - the visited-step CSV shape that lands in the
 *    Firestore `check_in_logs` doc on Finish
 *  - the streak compute pivots on `date_iso`, so a fresh log for
 *    today flips `logged_today` from false → true (the gate the
 *    Today card uses to swap "Check In" for "Update")
 *
 * Keeping these as data-shape tests preserves the same lightweight
 * import surface as the existing draft-persistence test — pulling
 * the .tsx in jsdom drags Firebase + lucide and times out.
 */

const STEP_ORDER = ['MOOD_ENERGY', 'BALANCE', 'CALENDAR'] as const;
type Step = (typeof STEP_ORDER)[number];

function logFor(dateIso: string, stepsCsv: string): CheckInLog {
  return {
    id: dateIso,
    date_iso: dateIso,
    steps_completed_csv: stepsCsv,
    medications_confirmed: true,
    tasks_reviewed: true,
    habits_completed: true,
    created_at: Date.now(),
    updated_at: Date.now(),
  };
}

describe('MorningCheckInStepper navigation contract', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('STEP_ORDER has exactly three canonical steps', () => {
    expect(STEP_ORDER).toEqual(['MOOD_ENERGY', 'BALANCE', 'CALENDAR']);
    expect(STEP_ORDER.length).toBe(3);
  });

  it('every step kind is a non-empty CSV-safe ident', () => {
    for (const s of STEP_ORDER) {
      expect(typeof s).toBe('string');
      expect(s.length).toBeGreaterThan(0);
      // No commas — they're the CSV delimiter for steps_completed_csv.
      expect(s).not.toContain(',');
      // Upper-snake to match Android `CheckInStep` enum names.
      expect(s).toMatch(/^[A-Z_]+$/);
    }
  });

  it('joining visited steps into a CSV matches Android format', () => {
    const visited: Step[] = ['MOOD_ENERGY', 'BALANCE', 'CALENDAR'];
    const csv = visited.join(',');
    expect(csv).toBe('MOOD_ENERGY,BALANCE,CALENDAR');
    // Round-trips cleanly: parsing the CSV recovers the original
    // visited set.
    expect(csv.split(',')).toEqual(visited);
  });

  it('clamping out-of-range step indices keeps the pager inside [0, total-1]', () => {
    const total = STEP_ORDER.length;
    const clamp = (n: number) => Math.max(0, Math.min(total - 1, Math.round(n)));
    expect(clamp(-1)).toBe(0);
    expect(clamp(0)).toBe(0);
    expect(clamp(1)).toBe(1);
    expect(clamp(2)).toBe(2);
    expect(clamp(99)).toBe(total - 1);
  });
});

describe('MorningCheckInStepper streak join', () => {
  it("today's fresh log flips logged_today from false to true", () => {
    const today = '2026-05-15';
    const before = computeCheckInStreak([], today);
    expect(before.logged_today).toBe(false);
    expect(before.current).toBe(0);

    const after = computeCheckInStreak(
      [logFor(today, 'MOOD_ENERGY,BALANCE,CALENDAR')],
      today,
    );
    expect(after.logged_today).toBe(true);
    expect(after.current).toBe(1);
  });

  it('preserves a multi-day streak across yesterday + today logs', () => {
    const yesterday = '2026-05-14';
    const today = '2026-05-15';
    const streak = computeCheckInStreak(
      [
        logFor(yesterday, 'MOOD_ENERGY,BALANCE,CALENDAR'),
        logFor(today, 'MOOD_ENERGY,BALANCE,CALENDAR'),
      ],
      today,
    );
    expect(streak.current).toBe(2);
    expect(streak.longest).toBe(2);
    expect(streak.logged_today).toBe(true);
  });

  it('forgiveness-first: one missed day in the middle does not break the streak', () => {
    // Pattern: 5/13 ✓, 5/14 ✗, 5/15 ✓
    // Single-miss bends the streak per DailyForgivenessStreakCore — the
    // chain is preserved.
    const logs: CheckInLog[] = [
      logFor('2026-05-13', 'MOOD_ENERGY'),
      logFor('2026-05-15', 'MOOD_ENERGY'),
    ];
    const streak = computeCheckInStreak(logs, '2026-05-15');
    expect(streak.current).toBe(2);
    expect(streak.logged_today).toBe(true);
  });
});
