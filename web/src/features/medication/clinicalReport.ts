import { format } from 'date-fns';
import type { MedicationRefillDoc } from '@/api/firestore/medicationRefills';
import type { MedicationDoseDoc } from '@/api/firestore/medicationDoses';
import type { MedicationDoc } from '@/api/firestore/medications';

/**
 * Client-side port of Android `ClinicalReportGenerator`. Emits a
 * plain-text + markdown report that the user can paste into a patient
 * portal message or save as a file. Backend endpoint generation is out
 * of scope — the audit spec acknowledged web could ship a self-
 * contained generator since the input data already lives in Firestore.
 *
 * Sections (subset of Android's; the rest depend on data we don't yet
 * surface on web):
 *   - Overview: reporting period
 *   - Medications: per-med pill count, adherence (based on dose log
 *     density vs expected doses)
 *   - Refill projections: days remaining, urgency
 *
 * The plain-text mirrors Android's section structure so a clinician
 * who has seen the Android version can read the web version without
 * re-orienting.
 */

export interface ClinicalReportInputs {
  userName: string | null;
  dateRangeStart: number;
  dateRangeEnd: number;
  medications: readonly MedicationDoc[];
  refills: readonly MedicationRefillDoc[];
  doses: readonly MedicationDoseDoc[];
}

export interface ClinicalReportSection {
  header: string;
  lines: string[];
}

export interface ClinicalReport {
  title: string;
  subtitle: string;
  sections: ClinicalReportSection[];
  plainText: string;
  markdown: string;
}

const DAY_MS = 24 * 60 * 60 * 1000;

export function generateClinicalReport(
  inputs: ClinicalReportInputs,
): ClinicalReport {
  const title = 'PrismTask Health & Wellness Report';
  const subtitlePieces: string[] = [];
  if (inputs.userName !== null && inputs.userName.trim().length > 0) {
    subtitlePieces.push(`For ${inputs.userName}`);
  }
  subtitlePieces.push(
    `${format(inputs.dateRangeStart, 'MMM d, yyyy')} to ${format(
      inputs.dateRangeEnd,
      'MMM d, yyyy',
    )}`,
  );
  const subtitle = subtitlePieces.join(' — ');

  const sections: ClinicalReportSection[] = [
    overviewSection(inputs),
    medicationSection(inputs),
    refillSection(inputs),
  ];

  const plainText = renderPlainText(title, subtitle, sections);
  const markdown = renderMarkdown(title, subtitle, sections);
  return { title, subtitle, sections, plainText, markdown };
}

function overviewSection(inputs: ClinicalReportInputs): ClinicalReportSection {
  const days = dayCount(inputs);
  return {
    header: 'Overview',
    lines: [
      `Reporting period: ${days} day${days === 1 ? '' : 's'}`,
      'Data sources: medications, doses, refills (web client)',
      'Note: all values are self-reported and should be discussed with your healthcare provider.',
    ],
  };
}

function medicationSection(
  inputs: ClinicalReportInputs,
): ClinicalReportSection {
  if (inputs.medications.length === 0) {
    return { header: 'Medication', lines: ['No medications tracked.'] };
  }
  const days = dayCount(inputs);
  const lines: string[] = [];
  for (const med of inputs.medications) {
    if (med.is_archived) continue;
    const dosesForMed = inputs.doses.filter(
      (d) => d.medication_cloud_id === med.id,
    );
    const taken = dosesForMed.length;
    const expected = days * med.doses_per_day;
    const adherence =
      expected > 0
        ? `${Math.round((taken / expected) * 100)}% adherence (${taken}/${expected} doses)`
        : 'adherence unknown';
    const pillLine =
      med.pill_count !== null ? `${med.pill_count} pills on hand` : 'no pill tracking';
    lines.push(`${med.display_label ?? med.name}: ${pillLine}, ${adherence}`);
  }
  return { header: 'Medication', lines: lines.length > 0 ? lines : ['No active medications.'] };
}

function refillSection(inputs: ClinicalReportInputs): ClinicalReportSection {
  if (inputs.refills.length === 0) {
    return { header: 'Refill Projections', lines: ['No refill data on file.'] };
  }
  const lines = inputs.refills.map((r) => {
    const daily = Math.max(1, r.pills_per_dose * r.doses_per_day);
    const daysLeft = Math.max(0, Math.floor(r.pill_count / daily));
    const lastRefill =
      r.last_refill_date !== null
        ? `last refill ${format(r.last_refill_date, 'MMM d, yyyy')}`
        : 'no refill date on file';
    return `${r.medication_name}: ${r.pill_count} pills (~${daysLeft} day${
      daysLeft === 1 ? '' : 's'
    } at current dose, ${lastRefill})`;
  });
  return { header: 'Refill Projections', lines };
}

function dayCount(inputs: ClinicalReportInputs): number {
  const span = inputs.dateRangeEnd - inputs.dateRangeStart;
  if (span <= 0) return 0;
  return Math.max(1, Math.floor(span / DAY_MS) + 1);
}

function renderPlainText(
  title: string,
  subtitle: string,
  sections: readonly ClinicalReportSection[],
): string {
  const out: string[] = [title, subtitle, ''];
  for (const section of sections) {
    out.push(section.header);
    out.push('-'.repeat(section.header.length));
    for (const line of section.lines) out.push(`  ${line}`);
    out.push('');
  }
  out.push('Generated by PrismTask. Not a medical document.');
  return out.join('\n');
}

function renderMarkdown(
  title: string,
  subtitle: string,
  sections: readonly ClinicalReportSection[],
): string {
  const out: string[] = [`# ${title}`, `_${subtitle}_`, ''];
  for (const section of sections) {
    out.push(`## ${section.header}`);
    out.push('');
    for (const line of section.lines) out.push(`- ${line}`);
    out.push('');
  }
  out.push('---');
  out.push('_Generated by PrismTask. Not a medical document._');
  return out.join('\n');
}
