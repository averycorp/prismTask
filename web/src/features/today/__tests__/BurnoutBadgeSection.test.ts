import { describe, it, expect } from 'vitest';
import { scoreBurnout } from '@/utils/burnoutScorer';
import type { Breach } from '@/utils/boundaryEnforcer';
import type { MoodEnergyLog } from '@/api/firestore/moodEnergyLogs';

/**
 * Unit-level checks for the burnout-score visibility logic the
 * `<BurnoutBadgeSection />` relies on. Asserts the four buckets
 * (`calm` / `moderate` / `risky` / `burning`) align with the
 * thresholds the component uses to gate visibility — the badge stays
 * silent on `calm` and surfaces from `moderate` upward.
 *
 * Pure-function tests deliberately — the React side just maps the
 * bucket to an icon + label, so any bug in the score → bucket mapping
 * shows up here without DOM mounts.
 */
function breach(id: string, severity: Breach['severity']): Breach {
  return {
    rule_id: id,
    rule_type: 'daily_task_cap',
    label: 'Cap',
    message: 'Cap',
    severity,
  };
}

function moodLog(date: string, mood: number, energy: number): MoodEnergyLog {
  return {
    id: `m-${date}`,
    date_iso: date,
    mood,
    energy,
    notes: '',
    time_of_day: 'morning',
    created_at: 0,
    updated_at: 0,
  };
}

describe('BurnoutBadgeSection — score → bucket gating', () => {
  it('is silent when there are zero signals (bucket = calm)', () => {
    const result = scoreBurnout({
      breaches: [],
      recent_mood_logs: [],
      active_tasks_today: 0,
      task_soft_cap: 10,
    });
    expect(result.bucket).toBe('calm');
    expect(result.score).toBeLessThan(25);
  });

  it('surfaces "moderate" when warn-level breaches accumulate', () => {
    const breaches: Breach[] = [
      breach('r1', 'warn'),
      breach('r2', 'warn'),
      breach('r3', 'warn'),
    ];
    const result = scoreBurnout({
      breaches,
      recent_mood_logs: [],
      active_tasks_today: 0,
      task_soft_cap: 10,
    });
    // 3 × warn = 30 points → moderate band (25..50).
    expect(result.bucket).toBe('moderate');
    expect(result.score).toBeGreaterThanOrEqual(25);
    expect(result.score).toBeLessThan(50);
  });

  it('crosses into "burning" with stacked alerts + task overload', () => {
    const breaches: Breach[] = [breach('r1', 'alert'), breach('r2', 'alert')];
    const result = scoreBurnout({
      breaches,
      recent_mood_logs: [moodLog('2026-05-14', 1, 1), moodLog('2026-05-15', 1, 1)],
      active_tasks_today: 30,
      task_soft_cap: 10,
    });
    expect(result.bucket).toBe('burning');
    expect(result.score).toBeGreaterThanOrEqual(75);
  });

  it('clamps the breach component at 40 — five warns saturate but still moderate', () => {
    const breaches: Breach[] = Array.from({ length: 5 }, (_, i) =>
      breach(`r${i}`, 'warn'),
    );
    const result = scoreBurnout({
      breaches,
      recent_mood_logs: [],
      active_tasks_today: 0,
      task_soft_cap: 10,
    });
    expect(result.bucket).toBe('moderate');
    expect(result.score).toBeLessThan(50);
  });
});
