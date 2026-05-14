import { useCallback, useEffect, useMemo, useState } from 'react';
import { format, subDays } from 'date-fns';
import { Clipboard, Download, FileText, Pill, RefreshCw } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { Select } from '@/components/ui/Select';
import {
  generateClinicalReport,
  type ClinicalReport,
} from '@/features/medication/clinicalReport';
import {
  getAllDosesInRange,
  type MedicationDoseDoc,
} from '@/api/firestore/medicationDoses';
import {
  getAllMedications,
  type MedicationDoc,
} from '@/api/firestore/medications';
import {
  getRefills,
  type MedicationRefillDoc,
} from '@/api/firestore/medicationRefills';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { useAuthStore } from '@/stores/authStore';

/**
 * Web port of Android's clinical-report dialog. Generates a self-
 * contained plain-text + markdown report from Firestore data and
 * exposes copy / download actions. PDF emission was a stretch goal in
 * the audit; markdown download covers the same patient-portal-paste
 * workflow without pulling a PDF dep into the bundle.
 */

const RANGE_OPTIONS = [
  { value: '7', label: 'Last 7 days' },
  { value: '30', label: 'Last 30 days' },
  { value: '60', label: 'Last 60 days' },
  { value: '90', label: 'Last 90 days' },
];

export function ClinicalReportPanel() {
  const user = useAuthStore((s) => s.user);
  const [rangeDays, setRangeDays] = useState(30);
  const [generating, setGenerating] = useState(false);
  const [report, setReport] = useState<ClinicalReport | null>(null);
  const [medications, setMedications] = useState<MedicationDoc[]>([]);
  const [refills, setRefills] = useState<MedicationRefillDoc[]>([]);
  const [doses, setDoses] = useState<MedicationDoseDoc[]>([]);

  const fetchInputs = useCallback(async () => {
    const uid = getFirebaseUid();
    const endIso = format(new Date(), 'yyyy-MM-dd');
    const startIso = format(subDays(new Date(), rangeDays - 1), 'yyyy-MM-dd');
    const [meds, refillRows, doseRows] = await Promise.all([
      getAllMedications(uid),
      getRefills(uid),
      getAllDosesInRange(uid, startIso, endIso),
    ]);
    setMedications(meds);
    setRefills(refillRows);
    setDoses(doseRows);
  }, [rangeDays]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch on range change
    fetchInputs().catch((e) => toast.error((e as Error).message ?? 'Failed to load data'));
  }, [fetchInputs]);

  const userName = useMemo(
    () => user?.displayName ?? user?.email ?? null,
    [user],
  );

  const handleGenerate = async () => {
    setGenerating(true);
    try {
      const now = Date.now();
      const start = now - (rangeDays - 1) * 24 * 60 * 60 * 1000;
      const generated = generateClinicalReport({
        userName,
        dateRangeStart: start,
        dateRangeEnd: now,
        medications,
        refills,
        doses,
      });
      setReport(generated);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to generate report');
    } finally {
      setGenerating(false);
    }
  };

  const handleCopy = async () => {
    if (report === null) return;
    try {
      await navigator.clipboard.writeText(report.plainText);
      toast.success('Copied to clipboard');
    } catch {
      toast.error('Clipboard unavailable');
    }
  };

  const handleDownload = (kind: 'txt' | 'md') => {
    if (report === null) return;
    const content = kind === 'md' ? report.markdown : report.plainText;
    const mime = kind === 'md' ? 'text/markdown' : 'text/plain';
    const blob = new Blob([content], { type: `${mime};charset=utf-8` });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `prismtask-clinical-report-${format(new Date(), 'yyyy-MM-dd')}.${kind}`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <FileText className="h-5 w-5 text-[var(--color-accent)]" />
          <div>
            <h2 className="text-base font-semibold text-[var(--color-text-primary)]">
              Clinical Report
            </h2>
            <p className="text-xs text-[var(--color-text-secondary)]">
              Self-reported summary to share with your healthcare provider.
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Select
            options={RANGE_OPTIONS}
            value={String(rangeDays)}
            onChange={(v) => setRangeDays(parseInt(v ?? '30', 10) || 30)}
            className="w-36"
          />
          <Button
            variant="primary"
            size="sm"
            onClick={handleGenerate}
            disabled={generating}
          >
            {generating ? (
              'Generating…'
            ) : (
              <>
                <RefreshCw className="mr-1 h-3.5 w-3.5" /> Generate
              </>
            )}
          </Button>
        </div>
      </div>

      {report === null ? (
        <p className="rounded-lg bg-[var(--color-bg-secondary)] p-4 text-sm text-[var(--color-text-secondary)]">
          <Pill className="mr-2 inline h-4 w-4 align-middle" />
          Click Generate to compute the report from your data.
        </p>
      ) : (
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap gap-2">
            <Button variant="ghost" size="sm" onClick={handleCopy}>
              <Clipboard className="mr-1 h-3.5 w-3.5" /> Copy plain text
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => handleDownload('md')}
            >
              <Download className="mr-1 h-3.5 w-3.5" /> Download .md
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => handleDownload('txt')}
            >
              <Download className="mr-1 h-3.5 w-3.5" /> Download .txt
            </Button>
          </div>
          <pre className="max-h-96 overflow-auto rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3 text-xs text-[var(--color-text-primary)] whitespace-pre-wrap">
            {report.plainText}
          </pre>
        </div>
      )}
    </section>
  );
}
