import { describe, it, expect } from 'vitest';
import {
  deriveVirtualSlots,
  mergeVirtualWithMaterialized,
} from '@/features/medication/virtualSlots';
import type { MedicationDoc } from '@/api/firestore/medications';
import type { MedicationSlot } from '@/types/dailyEssentials';

function med(overrides: Partial<MedicationDoc>): MedicationDoc {
  return {
    id: 'm1',
    name: 'Test Med',
    display_label: null,
    notes: '',
    tier: 'essential',
    is_archived: false,
    sort_order: 0,
    schedule_mode: 'TIMES_OF_DAY',
    times_of_day: 'morning',
    specific_times: null,
    interval_millis: null,
    doses_per_day: 1,
    pill_count: null,
    pills_per_dose: 1,
    last_refill_date: null,
    pharmacy_name: null,
    pharmacy_phone: null,
    reminder_days_before: 3,
    reminder_mode: null,
    reminder_interval_minutes: null,
    prompt_dose_at_log: false,
    created_at: 0,
    updated_at: 0,
    ...overrides,
  };
}

describe('deriveVirtualSlots', () => {
  it('derives one slot per times_of_day bucket', () => {
    const slots = deriveVirtualSlots([
      med({ id: 'a', times_of_day: 'morning,evening' }),
    ]);
    expect(slots.map((s) => s.slotKey).sort()).toEqual(['evening', 'morning']);
    const morning = slots.find((s) => s.slotKey === 'morning')!;
    expect(morning.medIds).toEqual(['med:a']);
    expect(morning.takenAt).toBeNull();
  });

  it('groups multiple meds into the same bucket', () => {
    const slots = deriveVirtualSlots([
      med({ id: 'a', name: 'Med A', times_of_day: 'morning' }),
      med({ id: 'b', name: 'Med B', times_of_day: 'morning' }),
    ]);
    expect(slots).toHaveLength(1);
    expect(slots[0].medIds).toEqual(['med:a', 'med:b']);
    expect(slots[0].medLabels).toEqual(['Med A', 'Med B']);
  });

  it('produces HH:mm slots for SPECIFIC_TIMES schedules', () => {
    const slots = deriveVirtualSlots([
      med({
        id: 'a',
        schedule_mode: 'SPECIFIC_TIMES',
        times_of_day: null,
        specific_times: '08:00,20:00',
      }),
    ]);
    expect(slots.map((s) => s.slotKey).sort()).toEqual(['08:00', '20:00']);
  });

  it('produces a single anytime slot for INTERVAL and AS_NEEDED', () => {
    const intervalSlots = deriveVirtualSlots([
      med({
        id: 'a',
        schedule_mode: 'INTERVAL',
        times_of_day: null,
        interval_millis: 8 * 60 * 60 * 1000,
      }),
    ]);
    expect(intervalSlots).toHaveLength(1);
    expect(intervalSlots[0].slotKey).toBe('anytime');

    const asNeededSlots = deriveVirtualSlots([
      med({
        id: 'b',
        schedule_mode: 'AS_NEEDED',
        times_of_day: null,
      }),
    ]);
    expect(asNeededSlots).toHaveLength(1);
    expect(asNeededSlots[0].slotKey).toBe('anytime');
  });

  it('skips archived and empty-name medications', () => {
    const slots = deriveVirtualSlots([
      med({ id: 'a', is_archived: true }),
      med({ id: 'b', name: '' }),
    ]);
    expect(slots).toEqual([]);
  });

  it('falls back to anytime when TIMES_OF_DAY has no buckets configured', () => {
    const slots = deriveVirtualSlots([
      med({ id: 'a', times_of_day: '' }),
    ]);
    expect(slots).toHaveLength(1);
    expect(slots[0].slotKey).toBe('anytime');
  });

  it('sorts anytime to the bottom', () => {
    const slots = deriveVirtualSlots([
      med({ id: 'a', schedule_mode: 'AS_NEEDED', times_of_day: null }),
      med({ id: 'b', times_of_day: 'morning' }),
      med({ id: 'c', times_of_day: 'evening' }),
    ]);
    expect(slots.map((s) => s.slotKey)).toEqual([
      'evening',
      'morning',
      'anytime',
    ]);
  });
});

describe('mergeVirtualWithMaterialized', () => {
  function slot(slotKey: string, takenAt: string | null): MedicationSlot {
    return {
      slotKey,
      displayTime: slotKey,
      medLabels: [],
      medIds: [],
      takenAt,
    };
  }

  it('lets materialized rows win on (slotKey) collision', () => {
    const materialized = [slot('morning', '2026-05-13T08:00:00Z')];
    const virtual = [slot('morning', null), slot('evening', null)];
    const merged = mergeVirtualWithMaterialized(materialized, virtual);
    expect(merged).toHaveLength(2);
    const morning = merged.find((s) => s.slotKey === 'morning')!;
    expect(morning.takenAt).toBe('2026-05-13T08:00:00Z');
  });

  it('returns materialized-only when virtual is empty', () => {
    const materialized = [slot('morning', null)];
    expect(mergeVirtualWithMaterialized(materialized, [])).toEqual(materialized);
  });
});
