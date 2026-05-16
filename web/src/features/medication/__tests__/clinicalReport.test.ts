import { describe, it, expect } from 'vitest';
import { generateClinicalReport } from '@/features/medication/clinicalReport';
import type { MedicationDoc } from '@/api/firestore/medications';
import type { MedicationRefillDoc } from '@/api/firestore/medicationRefills';
import type { MedicationDoseDoc } from '@/api/firestore/medicationDoses';

const DAY_MS = 24 * 60 * 60 * 1000;

function med(overrides: Partial<MedicationDoc>): MedicationDoc {
  return {
    id: 'm1',
    name: 'Sertraline',
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
    pill_count: 30,
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

function refill(overrides: Partial<MedicationRefillDoc>): MedicationRefillDoc {
  return {
    id: 'r1',
    medication_name: 'Sertraline',
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

function dose(overrides: Partial<MedicationDoseDoc>): MedicationDoseDoc {
  return {
    id: 'd1',
    medication_cloud_id: 'm1',
    custom_medication_name: null,
    slot_key: 'morning',
    taken_at: 0,
    taken_date_local: '2026-05-01',
    note: '',
    is_synthetic_skip: false,
    dose_amount: null,
    created_at: 0,
    updated_at: 0,
    ...overrides,
  };
}

describe('generateClinicalReport', () => {
  it('emits a title and subtitle including the date range', () => {
    const end = Date.UTC(2026, 4, 15);
    const start = end - 29 * DAY_MS;
    const report = generateClinicalReport({
      userName: 'Avery',
      dateRangeStart: start,
      dateRangeEnd: end,
      medications: [],
      refills: [],
      doses: [],
    });
    expect(report.title).toContain('PrismTask');
    expect(report.subtitle).toContain('Avery');
    expect(report.subtitle).toContain('2026');
  });

  it('lists medications in the markdown output', () => {
    const meds = [
      med({ id: 'm1', name: 'Sertraline' }),
      med({ id: 'm2', name: 'Bupropion' }),
    ];
    const report = generateClinicalReport({
      userName: null,
      dateRangeStart: Date.UTC(2026, 4, 1),
      dateRangeEnd: Date.UTC(2026, 4, 7),
      medications: meds,
      refills: [],
      doses: [],
    });
    expect(report.markdown).toContain('Sertraline');
    expect(report.markdown).toContain('Bupropion');
    expect(report.markdown).toContain('## Medication');
  });

  it('counts dose adherence over the period', () => {
    const start = Date.UTC(2026, 4, 1);
    const end = start + 9 * DAY_MS; // 10 days
    const meds = [med({ id: 'm1', name: 'Sertraline', doses_per_day: 1 })];
    // 7 of 10 doses taken
    const doses = Array.from({ length: 7 }, (_, i) =>
      dose({ id: `d${i}`, medication_cloud_id: 'm1' }),
    );
    const report = generateClinicalReport({
      userName: null,
      dateRangeStart: start,
      dateRangeEnd: end,
      medications: meds,
      refills: [],
      doses,
    });
    expect(report.markdown).toContain('70%');
    expect(report.markdown).toContain('(7/10 doses)');
  });

  it('reports refill projection days remaining', () => {
    const start = Date.UTC(2026, 4, 1);
    const end = start + 6 * DAY_MS;
    const report = generateClinicalReport({
      userName: null,
      dateRangeStart: start,
      dateRangeEnd: end,
      medications: [],
      refills: [
        refill({ medication_name: 'Sertraline', pill_count: 21, doses_per_day: 1 }),
      ],
      doses: [],
    });
    expect(report.markdown).toContain('Sertraline');
    expect(report.markdown).toContain('21 pills');
    expect(report.markdown).toContain('21 days');
  });

  it('skips archived medications in the report body', () => {
    const meds = [
      med({ id: 'm1', name: 'ActiveMed', is_archived: false }),
      med({ id: 'm2', name: 'OldMed', is_archived: true }),
    ];
    const report = generateClinicalReport({
      userName: null,
      dateRangeStart: Date.UTC(2026, 4, 1),
      dateRangeEnd: Date.UTC(2026, 4, 7),
      medications: meds,
      refills: [],
      doses: [],
    });
    expect(report.markdown).toContain('ActiveMed');
    expect(report.markdown).not.toContain('OldMed');
  });

  it('emits plain text mirroring markdown sections', () => {
    const report = generateClinicalReport({
      userName: 'Patient',
      dateRangeStart: Date.UTC(2026, 4, 1),
      dateRangeEnd: Date.UTC(2026, 4, 7),
      medications: [],
      refills: [],
      doses: [],
    });
    expect(report.plainText).toContain('Overview');
    expect(report.plainText).toContain('Medication');
    expect(report.plainText).toContain('Refill Projections');
    expect(report.plainText).toContain('Patient');
    // Plain text uses underline separators not markdown headings.
    expect(report.plainText).not.toContain('## Overview');
  });

  it('reports "No medications tracked" when the list is empty', () => {
    const report = generateClinicalReport({
      userName: null,
      dateRangeStart: Date.UTC(2026, 4, 1),
      dateRangeEnd: Date.UTC(2026, 4, 7),
      medications: [],
      refills: [],
      doses: [],
    });
    expect(report.markdown).toContain('No medications tracked.');
  });
});
