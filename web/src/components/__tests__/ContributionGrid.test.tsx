import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ContributionGrid } from '@/components/ContributionGrid';
import { format, subDays } from 'date-fns';

function isoDaysAgo(days: number): string {
  return format(subDays(new Date(), days), 'yyyy-MM-dd');
}

describe('ContributionGrid', () => {
  it('renders the configured title', () => {
    render(
      <ContributionGrid
        completions={[]}
        habitColor="#22c55e"
        maxCount={1}
        title="Contributions"
      />,
    );
    expect(screen.getByText('Contributions')).toBeInTheDocument();
  });

  it('renders 12 weeks of cells by default (7 rows × 12 columns)', () => {
    render(
      <ContributionGrid
        completions={[]}
        habitColor="#22c55e"
        maxCount={1}
      />,
    );
    const cells = screen.getAllByTestId('contribution-grid-cell');
    // Only past + today cells are rendered (future cells are spacers
    // without the testid). 12 weeks covers at most 84 days; the rendered
    // count is ≤ 84 and ≥ 79 (12 weeks − up to 5 future days).
    expect(cells.length).toBeLessThanOrEqual(84);
    expect(cells.length).toBeGreaterThanOrEqual(79);
  });

  it('buckets recent completions into the grid by date', () => {
    // Use a date 7 days ago — guaranteed to be inside the rendered 12-week
    // window regardless of where today falls in its week.
    const targetDate = isoDaysAgo(7);
    const completions = [{ date: targetDate, count: 3 }];
    render(
      <ContributionGrid
        completions={completions}
        habitColor="#22c55e"
        maxCount={3}
      />,
    );
    const matches = document.querySelectorAll(`[data-date="${targetDate}"]`);
    expect(matches.length).toBe(1);
    expect(matches[0].getAttribute('data-count')).toBe('3');
  });

  it('clamps cells with no completions to count = 0', () => {
    const targetDate = isoDaysAgo(7);
    render(
      <ContributionGrid
        completions={[]}
        habitColor="#22c55e"
        maxCount={1}
      />,
    );
    const cell = document.querySelector(`[data-date="${targetDate}"]`);
    expect(cell?.getAttribute('data-count')).toBe('0');
  });

  it('respects a custom weeks count', () => {
    render(
      <ContributionGrid
        completions={[]}
        habitColor="#22c55e"
        maxCount={1}
        weeks={4}
      />,
    );
    const cells = screen.getAllByTestId('contribution-grid-cell');
    // 4 weeks = at most 28 days, at least 23 (4 × 7 minus future spacers).
    expect(cells.length).toBeLessThanOrEqual(28);
    expect(cells.length).toBeGreaterThanOrEqual(23);
  });
});
