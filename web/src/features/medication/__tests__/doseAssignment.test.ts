import { describe, it, expect } from 'vitest';
import {
  assignmentKey,
  buildDoseAssignment,
} from '@/features/medication/doseAssignment';
import type { MedicationDoseDoc } from '@/api/firestore/medicationDoses';
import type { MedicationSlot } from '@/types/dailyEssentials';

function slot(
  slotKey: string,
  medCloudIds: readonly string[],
): MedicationSlot {
  return {
    slotKey,
    displayTime: slotKey,
    medLabels: medCloudIds.map((id) => id),
    medIds: medCloudIds.map((id) => `med:${id}`),
    takenAt: null,
  };
}

// `2026-05-21` chosen so `Date` math is deterministic and DST-stable.
function doseAt(
  slotKey: string,
  medCloudId: string,
  hour: number,
  minute: number = 0,
): MedicationDoseDoc {
  const takenAt = new Date(2026, 4, 21, hour, minute).getTime();
  return {
    id: `${medCloudId}__${slotKey}__${hour}${minute}`,
    medication_cloud_id: medCloudId,
    custom_medication_name: null,
    slot_key: slotKey,
    taken_at: takenAt,
    taken_date_local: '2026-05-21',
    note: '',
    is_synthetic_skip: false,
    dose_amount: null,
    created_at: takenAt,
    updated_at: takenAt,
  };
}

describe('buildDoseAssignment', () => {
  it('claims doses by exact slot_key match (web-logged path)', () => {
    const slots = [slot('morning', ['adderall']), slot('evening', ['adderall'])];
    const dosesByMed = {
      adderall: [doseAt('morning', 'adderall', 8), doseAt('evening', 'adderall', 20)],
    };
    const result = buildDoseAssignment(slots, dosesByMed);
    expect(result.get(assignmentKey('morning', 'adderall'))?.taken_at).toBe(
      new Date(2026, 4, 21, 8).getTime(),
    );
    expect(result.get(assignmentKey('evening', 'adderall'))?.taken_at).toBe(
      new Date(2026, 4, 21, 20).getTime(),
    );
  });

  it('routes Android-logged doses (opaque slot_key) to the closest slot by time', () => {
    const slots = [slot('morning', ['adderall']), slot('evening', ['adderall'])];
    const dosesByMed = {
      // Android writes slot.id.toString() — "5" / "9" are opaque to web.
      adderall: [doseAt('5', 'adderall', 8), doseAt('9', 'adderall', 20)],
    };
    const result = buildDoseAssignment(slots, dosesByMed);
    expect(result.get(assignmentKey('morning', 'adderall'))?.taken_at).toBe(
      new Date(2026, 4, 21, 8).getTime(),
    );
    expect(result.get(assignmentKey('evening', 'adderall'))?.taken_at).toBe(
      new Date(2026, 4, 21, 20).getTime(),
    );
  });

  it('only assigns one Android dose when only one was taken — other slot stays empty', () => {
    const slots = [slot('morning', ['adderall']), slot('evening', ['adderall'])];
    const dosesByMed = {
      adderall: [doseAt('5', 'adderall', 8)],
    };
    const result = buildDoseAssignment(slots, dosesByMed);
    expect(result.get(assignmentKey('morning', 'adderall'))?.taken_at).toBe(
      new Date(2026, 4, 21, 8).getTime(),
    );
    expect(result.get(assignmentKey('evening', 'adderall'))).toBeUndefined();
  });

  it('matches HH:mm specific-time slots to their wall-clock proximity', () => {
    const slots = [slot('08:00', ['vitaminD']), slot('14:30', ['vitaminD'])];
    const dosesByMed = {
      vitaminD: [doseAt('99', 'vitaminD', 8, 5), doseAt('99', 'vitaminD', 14, 25)],
    };
    const result = buildDoseAssignment(slots, dosesByMed);
    expect(result.get(assignmentKey('08:00', 'vitaminD'))?.taken_at).toBe(
      new Date(2026, 4, 21, 8, 5).getTime(),
    );
    expect(result.get(assignmentKey('14:30', 'vitaminD'))?.taken_at).toBe(
      new Date(2026, 4, 21, 14, 25).getTime(),
    );
  });

  it('suppresses proximity assignment when no dose is within 6 hours of the slot', () => {
    const slots = [slot('morning', ['adderall']), slot('evening', ['adderall'])];
    // Both doses are in the morning — evening should NOT be assigned the
    // 8:30am dose just because nothing else is closer.
    const dosesByMed = {
      adderall: [doseAt('5', 'adderall', 8), doseAt('5', 'adderall', 8, 30)],
    };
    const result = buildDoseAssignment(slots, dosesByMed);
    expect(result.get(assignmentKey('morning', 'adderall'))?.taken_at).toBe(
      new Date(2026, 4, 21, 8).getTime(),
    );
    expect(result.get(assignmentKey('evening', 'adderall'))).toBeUndefined();
  });

  it('exact-match wins over proximity even when proximity would route differently', () => {
    const slots = [slot('morning', ['adderall']), slot('evening', ['adderall'])];
    // Evening dose explicitly logged via web with slot_key='evening' —
    // but timestamp is morning-ish. Exact match must still win.
    const dosesByMed = {
      adderall: [
        doseAt('morning', 'adderall', 9),
        doseAt('evening', 'adderall', 10),
      ],
    };
    const result = buildDoseAssignment(slots, dosesByMed);
    expect(result.get(assignmentKey('morning', 'adderall'))?.taken_at).toBe(
      new Date(2026, 4, 21, 9).getTime(),
    );
    expect(result.get(assignmentKey('evening', 'adderall'))?.taken_at).toBe(
      new Date(2026, 4, 21, 10).getTime(),
    );
  });

  it('anytime slots accept any leftover dose', () => {
    const slots = [slot('anytime', ['prn'])];
    const dosesByMed = { prn: [doseAt('99', 'prn', 13)] };
    const result = buildDoseAssignment(slots, dosesByMed);
    expect(result.get(assignmentKey('anytime', 'prn'))?.taken_at).toBe(
      new Date(2026, 4, 21, 13).getTime(),
    );
  });

  it('skips meds with no doses', () => {
    const slots = [slot('morning', ['adderall'])];
    const result = buildDoseAssignment(slots, {});
    expect(result.size).toBe(0);
  });

  it('skips slots whose medIds are all non-`med:` (legacy / external)', () => {
    const slotWithLegacyId: MedicationSlot = {
      slotKey: 'morning',
      displayTime: 'Morning',
      medLabels: ['Legacy'],
      medIds: ['self_care_step:lipitor'],
      takenAt: null,
    };
    const result = buildDoseAssignment([slotWithLegacyId], {});
    expect(result.size).toBe(0);
  });
});
