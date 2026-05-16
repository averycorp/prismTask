import { render, screen } from '@testing-library/react';
import { describe, expect, it, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';

import { WeeklyReviewsListScreen } from '@/features/weeklyreview/WeeklyReviewsListScreen';
import { useWeeklyReviewsStore } from '@/stores/weeklyReviewsStore';
import type { WeeklyReview } from '@/api/firestore/weeklyReviews';

function makeReview(weekStartDate: string, completed: number, slipped: number): WeeklyReview {
  return {
    id: weekStartDate,
    weekStartDate,
    weekStartMs: 0,
    metricsJson: JSON.stringify({
      completed_count: completed,
      slipped_count: slipped,
    }),
    aiInsightsJson: null,
    createdAt: 0,
    updatedAt: 0,
  };
}

describe('WeeklyReviewsListScreen', () => {
  beforeEach(() => {
    useWeeklyReviewsStore.setState({ reviews: [] });
  });

  it('renders empty state when no reviews are cached', () => {
    render(
      <MemoryRouter>
        <WeeklyReviewsListScreen />
      </MemoryRouter>,
    );
    expect(screen.getByText('No Reviews Yet')).toBeInTheDocument();
    expect(screen.getByText(/will generate this Sunday/)).toBeInTheDocument();
  });

  it('renders persisted reviews ordered by week_start desc', () => {
    useWeeklyReviewsStore.setState({
      reviews: [
        makeReview('2026-04-27', 1, 2),
        makeReview('2026-05-04', 5, 1),
        makeReview('2026-05-11', 3, 0),
      ],
    });

    render(
      <MemoryRouter>
        <WeeklyReviewsListScreen />
      </MemoryRouter>,
    );

    // Three rows with summary lines render — assert by counts so we
    // don't depend on locale-formatted week headers.
    expect(screen.getByText('5 completed · 1 slipped')).toBeInTheDocument();
    expect(screen.getByText('3 completed · 0 slipped')).toBeInTheDocument();
    expect(screen.getByText('1 completed · 2 slipped')).toBeInTheDocument();

    // The newest row should be first in the rendered list. Match by the
    // link's href to avoid coupling on locale strings.
    const links = screen.getAllByRole('link');
    const detailLinks = links.filter((l) =>
      (l.getAttribute('href') ?? '').startsWith('/weekly-review/detail/'),
    );
    expect(detailLinks.map((l) => l.getAttribute('href'))).toEqual([
      '/weekly-review/detail/2026-05-11',
      '/weekly-review/detail/2026-05-04',
      '/weekly-review/detail/2026-04-27',
    ]);
  });

  it('shows "No activity logged" for empty cron-seeded rows', () => {
    useWeeklyReviewsStore.setState({
      reviews: [makeReview('2026-05-04', 0, 0)],
    });
    render(
      <MemoryRouter>
        <WeeklyReviewsListScreen />
      </MemoryRouter>,
    );
    expect(screen.getByText('No activity logged')).toBeInTheDocument();
  });
});
