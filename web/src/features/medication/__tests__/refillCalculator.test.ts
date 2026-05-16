import { describe, it, expect } from 'vitest';
import {
  forecastRefill,
  type MedicationRefillDoc,
} from '@/api/firestore/medicationRefills';

/**
 * Mirrors `app/src/test/.../RefillCalculatorTest.kt` so the web port
 * stays observationally identical to Android's pure-function reference.
 * Note: web exposes the forecast function via `medicationRefills.ts`
 * to keep the firestore + math in one module; the math itself is the
 * same one Android ships.
 */

const DAY_MS = 24 * 60 * 60 * 1000;

function refill(overrides: Partial<MedicationRefillDoc>): MedicationRefillDoc {
  return {
    id: 'r1',
    medication_name: 'Test Med',
    pill_count: 30,
    pills_per_dose: 1,
    doses_per_day: 1,
    last_refill_date: null,
    pharmacy_name: null,
    pharmacy_phone: null,
    reminder_days_before: 3,
    created_at: 0,
    updated_at: 0,
    ...overrides,
  };
}

describe('forecastRefill', () => {
  it('computes days remaining from pill count / daily usage', () => {
    const r = refill({ pill_count: 60, pills_per_dose: 2, doses_per_day: 2 });
    // 60 / (2 * 2) = 15 days
    const f = forecastRefill(r, 0);
    expect(f.daysRemaining).toBe(15);
  });

  it('flags OUT_OF_STOCK when pill count is zero', () => {
    const r = refill({ pill_count: 0 });
    const f = forecastRefill(r, 0);
    expect(f.daysRemaining).toBe(0);
    expect(f.urgency).toBe('OUT_OF_STOCK');
  });

  it('flags URGENT below the urgent threshold (default 3)', () => {
    const r = refill({ pill_count: 2 });
    const f = forecastRefill(r, 0);
    expect(f.daysRemaining).toBe(2);
    expect(f.urgency).toBe('URGENT');
  });

  it('flags UPCOMING between urgent and upcoming thresholds', () => {
    const r = refill({ pill_count: 5 });
    const f = forecastRefill(r, 0);
    expect(f.daysRemaining).toBe(5);
    expect(f.urgency).toBe('UPCOMING');
  });

  it('flags HEALTHY above the upcoming threshold (default 7)', () => {
    const r = refill({ pill_count: 30 });
    const f = forecastRefill(r, 0);
    expect(f.daysRemaining).toBe(30);
    expect(f.urgency).toBe('HEALTHY');
  });

  it('anchors refill date to last_refill_date when set', () => {
    const lastRefill = Date.UTC(2026, 0, 1); // Jan 1 2026
    const r = refill({ pill_count: 10, last_refill_date: lastRefill });
    const f = forecastRefill(r, lastRefill + 2 * DAY_MS);
    // 10 days from Jan 1 → Jan 11
    expect(f.refillDateMillis).toBe(lastRefill + 10 * DAY_MS);
  });

  it('anchors refill date to `now` when last_refill_date is null', () => {
    const now = Date.UTC(2026, 5, 15);
    const r = refill({ pill_count: 7, last_refill_date: null });
    const f = forecastRefill(r, now);
    expect(f.refillDateMillis).toBe(now + 7 * DAY_MS);
  });

  it('places reminder N days before refill', () => {
    const now = Date.UTC(2026, 5, 1);
    const r = refill({
      pill_count: 10,
      reminder_days_before: 2,
      last_refill_date: now,
    });
    const f = forecastRefill(r, now);
    expect(f.reminderDateMillis).toBe(f.refillDateMillis - 2 * DAY_MS);
  });

  it('floors daily usage at 1 to avoid divide-by-zero', () => {
    // pills_per_dose=0, doses_per_day=0 — would crash if not floored.
    const r = refill({
      pill_count: 5,
      pills_per_dose: 0,
      doses_per_day: 0,
    });
    const f = forecastRefill(r, 0);
    expect(f.daysRemaining).toBe(5);
    expect(f.urgency).toBe('UPCOMING');
  });

  it('respects a custom urgency config', () => {
    const r = refill({ pill_count: 4 });
    const f = forecastRefill(r, 0, { urgentDays: 5, upcomingDays: 10 });
    expect(f.urgency).toBe('URGENT');
  });
});
