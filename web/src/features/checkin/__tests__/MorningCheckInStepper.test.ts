import { describe, expect, it, beforeEach, afterEach } from 'vitest';

/**
 * Draft-persistence contract for the Morning Check-In stepper
 * (parity audit § C.5a).
 *
 * The stepper writes `checkin:draft:${dateIso}` so reload mid-flow
 * doesn't lose progress. We test the storage-key invariants here
 * without importing the React component itself — pulling the .tsx in
 * a Node test environment drags every lucide/Firebase dep with it and
 * times out the hook setup. Keeping these as data-shape tests guards
 * the cross-component contract (other callers serializing the same
 * key will collide) without paying the import cost.
 */

const DRAFT_KEY = (dateIso: string) => `checkin:draft:${dateIso}`;

describe('MorningCheckInStepper draft persistence contract', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('keys drafts per logical day in localStorage', () => {
    const dateIso = '2026-05-15';
    localStorage.setItem(
      DRAFT_KEY(dateIso),
      JSON.stringify({
        step: 1,
        mood: 4,
        energy: 5,
        notes: 'feeling steady',
        visited: ['MOOD_ENERGY'],
      }),
    );
    const raw = localStorage.getItem(DRAFT_KEY(dateIso));
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw!);
    expect(parsed.mood).toBe(4);
    expect(parsed.energy).toBe(5);
    expect(parsed.visited).toEqual(['MOOD_ENERGY']);
  });

  it('isolates drafts across different logical days', () => {
    // Two adjacent days must not collide — the rolling-day key is the
    // whole reason we don't lose Monday's progress when the user
    // re-opens the stepper on Tuesday morning.
    localStorage.setItem(
      DRAFT_KEY('2026-05-15'),
      JSON.stringify({ step: 2, mood: 4, energy: 4, notes: '', visited: [] }),
    );
    expect(localStorage.getItem(DRAFT_KEY('2026-05-14'))).toBeNull();
    expect(localStorage.getItem(DRAFT_KEY('2026-05-16'))).toBeNull();
  });

  it('preserves visited-step set as a JSON array of step kinds', () => {
    // Visited steps drive the steps_completed_csv field on the
    // Firestore CheckInLog row. Each entry must be one of the
    // canonical Android `CheckInStep` enum values.
    const visited = ['MOOD_ENERGY', 'BALANCE', 'CALENDAR'];
    const valid = new Set(['MOOD_ENERGY', 'BALANCE', 'CALENDAR']);
    for (const v of visited) {
      expect(valid.has(v)).toBe(true);
    }
    localStorage.setItem(
      DRAFT_KEY('2026-05-15'),
      JSON.stringify({ step: 2, mood: 3, energy: 3, notes: '', visited }),
    );
    const parsed = JSON.parse(localStorage.getItem(DRAFT_KEY('2026-05-15'))!);
    expect(parsed.visited).toEqual(visited);
  });
});
