import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ChevronLeft, TrendingUp } from 'lucide-react';

import { useWeeklyReviewsStore } from '@/stores/weeklyReviewsStore';
import {
  getWeeklyReview,
  type WeeklyReview,
} from '@/api/firestore/weeklyReviews';
import { getFirebaseUid } from '@/stores/firebaseUid';

import {
  formatWeekOfLabel,
  toContent,
  type WeeklyReviewContent,
} from './weeklyReviewContent';

/**
 * Read-only detail view for a single persisted weekly review.
 *
 * Resolution order:
 *   1. Look up the row in the in-memory store (populated by the
 *      Firestore subscriber in `useFirestoreSync`). This is the common
 *      case — the user came here from the History list.
 *   2. If the store doesn't have it (deep link, store not yet hydrated),
 *      fetch directly from Firestore via `getWeeklyReview`.
 *
 * Mirrors `WeeklyReviewDetailScreen.kt` on Android (parity unit 12 of 23).
 */
export function WeeklyReviewDetailScreen() {
  const { weekStartDate: rawParam } = useParams<{ weekStartDate: string }>();
  const weekStartDate = useMemo(() => {
    if (!rawParam) return null;
    try {
      return decodeURIComponent(rawParam);
    } catch {
      return rawParam;
    }
  }, [rawParam]);

  const storeReview = useWeeklyReviewsStore((s) =>
    weekStartDate ? s.reviews.find((r) => r.weekStartDate === weekStartDate) : undefined,
  );

  const [fetched, setFetched] = useState<WeeklyReview | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!weekStartDate || storeReview) return;
    let cancelled = false;
    setLoading(true);
    (async () => {
      try {
        const uid = getFirebaseUid();
        const row = await getWeeklyReview(uid, weekStartDate);
        if (!cancelled) setFetched(row);
      } catch {
        if (!cancelled) setFetched(null);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [weekStartDate, storeReview]);

  const review: WeeklyReview | null = storeReview ?? fetched;
  const content: WeeklyReviewContent = useMemo(() => toContent(review), [review]);

  return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-4 flex items-center gap-3">
        <Link
          to="/weekly-review/history"
          className="flex items-center gap-1 text-sm text-[var(--color-text-secondary)] hover:text-[var(--color-accent)]"
          aria-label="Back to History"
        >
          <ChevronLeft className="h-4 w-4" />
          History
        </Link>
      </div>

      <div className="mb-6 flex items-center gap-3">
        <TrendingUp className="h-7 w-7 text-[var(--color-accent)]" />
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          {weekStartDate ? formatWeekOfLabel(weekStartDate) : 'Weekly Review'}
        </h1>
      </div>

      {!review && loading && (
        <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-6 py-10 text-center text-sm text-[var(--color-text-secondary)]">
          Loading review…
        </div>
      )}

      {!review && !loading && (
        <div
          role="status"
          className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-6 py-10 text-center"
        >
          <h2 className="text-base font-semibold text-[var(--color-text-primary)]">
            Review Not Found
          </h2>
          <p className="mt-2 text-sm text-[var(--color-text-secondary)]">
            This week hasn't been generated yet. Open Weekly Review to
            generate it now.
          </p>
        </div>
      )}

      {review && (
        <>
          <MetricsRow content={content} />
          <CategoryBreakdown byCategory={content.activitySummary.byCategory} />
          {content.narrative && <NarrativeProse text={content.narrative} />}
          <NarrativeList title="Patterns" items={content.patterns} tone="neutral" />
          <NarrativeList
            title="Focus For Next Week"
            items={content.nextWeekFocus}
            tone="accent"
          />
          <NarrativeList title="Wins" items={content.wins} tone="positive" />
          <NarrativeList title="Slips" items={content.slips} tone="neutral" />
        </>
      )}
    </div>
  );
}

function MetricsRow({ content }: { content: WeeklyReviewContent }) {
  const { completed, slipped, rescheduled } = content.activitySummary;
  const showRescheduled = rescheduled > 0;
  return (
    <div
      className={`mb-4 grid gap-3 ${showRescheduled ? 'grid-cols-3' : 'grid-cols-2'}`}
    >
      <MetricCard label="Completed" value={completed.toString()} />
      <MetricCard label="Slipped" value={slipped.toString()} />
      {showRescheduled && (
        <MetricCard label="Rescheduled" value={rescheduled.toString()} />
      )}
    </div>
  );
}

function MetricCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3">
      <div className="text-[10px] font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">
        {label}
      </div>
      <div className="mt-1 text-2xl font-bold text-[var(--color-text-primary)]">
        {value}
      </div>
    </div>
  );
}

function NarrativeProse({ text }: { text: string }) {
  return (
    <div className="mb-4 rounded-xl border border-[var(--color-accent)]/20 bg-[var(--color-accent)]/5 px-4 py-3">
      <p className="text-sm text-[var(--color-text-primary)]">{text}</p>
    </div>
  );
}

function NarrativeList({
  title,
  items,
  tone,
}: {
  title: string;
  items: string[];
  tone: 'positive' | 'neutral' | 'accent';
}) {
  if (items.length === 0) return null;
  const toneClass =
    tone === 'positive'
      ? 'border-green-500/20 bg-green-500/5'
      : tone === 'accent'
        ? 'border-[var(--color-accent)]/20 bg-[var(--color-accent)]/5'
        : 'border-[var(--color-border)] bg-[var(--color-bg-card)]';
  return (
    <div className={`mb-3 rounded-xl border px-4 py-3 ${toneClass}`}>
      <div className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
        {title}
      </div>
      <ul className="flex flex-col gap-1">
        {items.map((item, idx) => (
          <li key={idx} className="text-sm text-[var(--color-text-primary)]">
            • {item}
          </li>
        ))}
      </ul>
    </div>
  );
}

function CategoryBreakdown({ byCategory }: { byCategory: Record<string, number> }) {
  const entries = Object.entries(byCategory)
    .filter(([key, value]) => key.trim().length > 0 && value > 0)
    .sort((a, b) => b[1] - a[1]);
  if (entries.length === 0) return null;
  return (
    <div className="mb-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3">
      <div className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
        By Category
      </div>
      <ul className="flex flex-col gap-1">
        {entries.map(([key, count]) => (
          <li
            key={key}
            className="flex justify-between text-sm text-[var(--color-text-primary)]"
          >
            <span>{titleCaseCategory(key)}</span>
            <span className="font-semibold">{count}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function titleCaseCategory(key: string): string {
  return key
    .replace(/_/g, ' ')
    .replace(/-/g, ' ')
    .split(' ')
    .map((word) => (word.length > 0 ? word[0].toUpperCase() + word.slice(1) : ''))
    .join(' ');
}
