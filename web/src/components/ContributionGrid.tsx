/**
 * GitHub-style contribution grid.
 *
 * Rows = day-of-week (Mon–Sun), columns = weeks back from today.
 * Each cell colored by completion count, bucketed against `maxCount`
 * into four intensity levels (40 / 70 / A0 / FF hex alphas).
 *
 * Extracted from the inline grid inside `HabitAnalyticsScreen.tsx`
 * (parity unit 16/23). The Android reference lives in
 * `app/src/main/java/com/averycorp/prismtask/ui/screens/habits/HabitAnalyticsScreen.kt`.
 */
import { useMemo } from 'react';
import { format, subDays, getDay } from 'date-fns';
import { buildCompletionGrid } from '@/utils/streaks';
import { Tooltip } from '@/components/ui/Tooltip';

export interface ContributionGridEntry {
  /** ISO `YYYY-MM-DD`. */
  date: string;
  count: number;
}

export interface ContributionGridProps {
  completions: ContributionGridEntry[];
  /**
   * Solid color used for the densest cell. Lighter cells use 40 / 70 /
   * A0 alpha-hex suffixes, so this must be a 6-digit hex (or a CSS
   * variable that resolves to one — most callers pass habit.color).
   */
  habitColor: string;
  /** Highest expected count per day; drives cell intensity. */
  maxCount: number;
  /** Number of weeks of history to show. Defaults to 12. */
  weeks?: number;
  /** Title shown above the grid. Defaults to "Contributions". */
  title?: string;
}

const DAY_NAMES_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export function ContributionGrid({
  completions,
  habitColor,
  maxCount,
  weeks = 12,
  title = 'Contributions',
}: ContributionGridProps) {
  const totalDays = weeks * 7;

  const grid = useMemo(
    () =>
      buildCompletionGrid(
        completions.map((c) => ({ date: c.date, count: c.count })),
        totalDays,
      ),
    [completions, totalDays],
  );

  const { displayWeeks, today } = useMemo(() => {
    const today = new Date();
    const startDate = subDays(today, totalDays - 1);

    // Find the Monday on or before startDate.
    const startDow = getDay(startDate);
    const adjustedStart = subDays(startDate, startDow === 0 ? 6 : startDow - 1);

    const allWeeks: { date: Date; dateStr: string; count: number }[][] = [];
    let current = adjustedStart;
    while (current <= today || allWeeks.length < weeks) {
      const week: { date: Date; dateStr: string; count: number }[] = [];
      for (let d = 0; d < 7; d++) {
        const dateStr = format(current, 'yyyy-MM-dd');
        week.push({
          date: new Date(current),
          dateStr,
          count: grid.get(dateStr) ?? 0,
        });
        current = new Date(current.getTime() + 86400000);
      }
      allWeeks.push(week);
      if (allWeeks.length >= weeks && current > today) break;
    }

    return { displayWeeks: allWeeks.slice(-weeks), today };
  }, [grid, totalDays, weeks]);

  // Month labels (one entry per month transition along the column axis).
  const monthLabels = useMemo(() => {
    const labels: { label: string; col: number }[] = [];
    let lastMonth = -1;
    displayWeeks.forEach((week, idx) => {
      const monthNum = week[0].date.getMonth();
      if (monthNum !== lastMonth) {
        labels.push({ label: format(week[0].date, 'MMM'), col: idx });
        lastMonth = monthNum;
      }
    });
    return labels;
  }, [displayWeeks]);

  function getCellColor(count: number): string {
    if (count === 0) return 'var(--color-bg-secondary)';
    const intensity = Math.min(count / Math.max(maxCount, 1), 1);
    if (intensity <= 0.25) return habitColor + '40';
    if (intensity <= 0.5) return habitColor + '70';
    if (intensity <= 0.75) return habitColor + 'A0';
    return habitColor;
  }

  return (
    <div
      className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4"
      data-testid="contribution-grid"
    >
      <h3 className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
        {title}
      </h3>
      <div className="overflow-x-auto">
        <div className="inline-flex flex-col gap-1">
          {/* Month labels */}
          <div className="flex gap-1 pl-8">
            {monthLabels.map((m, i) => (
              <div
                key={i}
                className="text-xs text-[var(--color-text-secondary)]"
                style={{
                  marginLeft: i === 0 ? m.col * 16 : undefined,
                  width:
                    i < monthLabels.length - 1
                      ? (monthLabels[i + 1].col - m.col) * 16
                      : undefined,
                }}
              >
                {m.label}
              </div>
            ))}
          </div>

          {/* Grid rows (Mon–Sun) */}
          {[0, 1, 2, 3, 4, 5, 6].map((dayIdx) => (
            <div key={dayIdx} className="flex items-center gap-1">
              <span className="w-6 text-right text-xs text-[var(--color-text-secondary)]">
                {dayIdx % 2 === 0 ? DAY_NAMES_SHORT[dayIdx] : ''}
              </span>
              <div className="flex gap-1">
                {displayWeeks.map((week, weekIdx) => {
                  const cell = week[dayIdx];
                  if (!cell || cell.date > today) {
                    return (
                      <div
                        key={weekIdx}
                        className="h-3 w-3 rounded-sm"
                        style={{ backgroundColor: 'transparent' }}
                      />
                    );
                  }
                  return (
                    <Tooltip
                      key={weekIdx}
                      content={`${format(cell.date, 'MMM d, yyyy')}: ${cell.count} completion${cell.count !== 1 ? 's' : ''}`}
                      delay={100}
                    >
                      <div
                        className="h-3 w-3 rounded-sm transition-colors"
                        style={{ backgroundColor: getCellColor(cell.count) }}
                        data-testid="contribution-grid-cell"
                        data-count={cell.count}
                        data-date={cell.dateStr}
                      />
                    </Tooltip>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
