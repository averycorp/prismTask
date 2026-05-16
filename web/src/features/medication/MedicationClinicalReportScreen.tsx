import { Link } from 'react-router-dom';
import { ChevronLeft, FileText } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { ClinicalReportPanel } from '@/features/medication/ClinicalReportPanel';

/**
 * Standalone Clinical Report screen — full-page wrap of
 * {@link ClinicalReportPanel} routed at `/medication/clinical-report`.
 * Mirrors Android's dedicated `ClinicalReportGenerator` surface so
 * users can deep-link to the report without scrolling past the
 * medication-history table.
 *
 * The actual report assembly + download UI lives in the embedded
 * panel; this screen only adds the route-level chrome (header,
 * back link, descriptive blurb).
 */
export function MedicationClinicalReportScreen() {
  return (
    <div className="mx-auto max-w-3xl pb-16">
      <header className="mb-6 flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <FileText
            className="h-5 w-5 text-[var(--color-accent)]"
            aria-hidden="true"
          />
          <div>
            <h1 className="text-xl font-semibold text-[var(--color-text-primary)]">
              Clinical Report
            </h1>
            <p className="text-sm text-[var(--color-text-secondary)]">
              Therapist-friendly summary of medications, adherence, and
              refill projections.
            </p>
          </div>
        </div>
        <Link to="/medication">
          <Button variant="ghost" size="sm" aria-label="Back to medications">
            <ChevronLeft className="mr-1 h-4 w-4" />
            Back
          </Button>
        </Link>
      </header>

      <ClinicalReportPanel />
    </div>
  );
}
