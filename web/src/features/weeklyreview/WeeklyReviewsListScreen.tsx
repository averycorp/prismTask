import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { ChevronLeft, History, TrendingUp } from 'lucide-react';

import { useWeeklyReviewsStore } from '@/stores/weeklyReviewsStore';
import { EmptyState } from '@/components/ui/EmptyState';

import {
  formatWeekOfLabel,
  parseMetrics,
  summaryLine,
} from './weeklyReviewContent';

/**
 * History list of persisted weekly reviews. Reads from the live Firestore
 * subscriber (`useWeeklyReviewsStore`, wired by `useFirestoreSync`) so
 * cron-generated rows + Android-pushed rows show up here without a refresh.
 *
 * Mirrors `WeeklyReviewsListScreen.kt` on Android — same row layout
 * (Week-of header, summary line, tap-to-detail) and same empty-state copy.
 */
export function WeeklyReviewsListScreen() {
  const reviews = useWeeklyReviewsStore((s) => s.reviews);

  // Stable-sort by weekStartDate desc — store already delivers desc order
  // but we resort defensively so a future ordering change in the
  // listener doesn't quietly break the list.
  const sorted = useMemo(() => {
    return [...reviews].sort((a, b) =>
      b.weekStartDate.localeCompare(a.weekStartDate),
    );
  }, [reviews]);

  return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-4 flex items-center gap-3">
        <Link
          to="/weekly-review"
          className="flex items-center gap-1 text-sm text-[var(--color-text-secondary)] hover:text-[var(--color-accent)]"
          aria-label="Back to Weekly Review"
        >
          <ChevronLeft className="h-4 w-4" />
          Weekly Review
        </Link>
      </div>

      <div className="mb-6 flex items-center gap-3">
        <History className="h-7 w-7 text-[var(--color-accent)]" />
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          History
        </h1>
      </div>

      {sorted.length === 0 ? (
        <EmptyState
          icon={<TrendingUp className="h-7 w-7" />}
          title="No Reviews Yet"
          description="Your first weekly review will generate this Sunday — or tap Generate Now on the Weekly Review screen."
        />
      ) : (
        <ul className="flex flex-col gap-3">
          {sorted.map((review) => {
            const summary = parseMetrics(review.metricsJson);
            return (
              <li key={review.id}>
                <Link
                  to={`/weekly-review/detail/${encodeURIComponent(review.weekStartDate)}`}
                  className="block rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3 hover:border-[var(--color-accent)] hover:bg-[var(--color-bg-secondary)]"
                >
                  <div className="text-sm font-semibold text-[var(--color-text-primary)]">
                    {formatWeekOfLabel(review.weekStartDate)}
                  </div>
                  <div className="mt-1 text-xs text-[var(--color-text-secondary)]">
                    {summaryLine(summary)}
                  </div>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
