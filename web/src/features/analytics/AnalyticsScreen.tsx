import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import {
  Activity,
  ArrowDownRight,
  ArrowUpRight,
  BarChart3,
  Clock,
  Flame,
  Loader2,
  Minus,
  TrendingUp,
  type LucideIcon,
} from 'lucide-react';
import { format, parseISO, subDays } from 'date-fns';
import { toast } from 'sonner';
import { analyticsApi } from '@/api/analytics';
import { dashboardApi } from '@/api/dashboard';
import { isExpectedAnalyticsError } from './analyticsErrors';
import { EmptyState } from '@/components/ui/EmptyState';
import { ProjectProgressPanel } from '@/features/analytics/ProjectProgressPanel';
import { ProUpgradeModal } from '@/components/shared/ProUpgradeModal';
import { useProFeature } from '@/hooks/useProFeature';
import type {
  HabitCorrelationResponse,
  ProductivityScoreResponse,
  ProductivityTrend,
  TimeTrackingGroupBy,
  TimeTrackingResponse,
} from '@/types/analytics';
import type { AnalyticsSummary } from '@/types/api';

type RangeOption = '7d' | '30d' | '90d';

const RANGE_LABELS: Record<RangeOption, string> = {
  '7d': '7 days',
  '30d': '30 days',
  '90d': '90 days',
};

function rangeToParams(range: RangeOption) {
  const days = range === '7d' ? 7 : range === '30d' ? 30 : 90;
  const end = new Date();
  const start = subDays(end, days - 1);
  return {
    start_date: format(start, 'yyyy-MM-dd'),
    end_date: format(end, 'yyyy-MM-dd'),
  };
}

function formatMinutes(minutes: number): string {
  if (minutes === 0) return '0m';
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours}h` : `${hours}h ${rest}m`;
}

function TrendBadge({ trend }: { trend: ProductivityTrend | string }) {
  let Icon = Minus;
  let className = 'text-[var(--color-text-secondary)]';
  if (trend === 'improving' || trend === 'up') {
    Icon = ArrowUpRight;
    className = 'text-emerald-500';
  } else if (trend === 'declining' || trend === 'down') {
    Icon = ArrowDownRight;
    className = 'text-red-500';
  }
  return (
    <span
      className={`inline-flex items-center gap-1 text-xs font-medium capitalize ${className}`}
    >
      <Icon className="h-3.5 w-3.5" aria-hidden="true" />
      {trend}
    </span>
  );
}

function SummaryTile({
  label,
  value,
  sub,
  icon: Icon,
}: {
  label: string;
  value: string;
  sub?: React.ReactNode;
  icon: LucideIcon;
}) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <div className="mb-1 flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">
        <Icon className="h-3.5 w-3.5" aria-hidden="true" />
        {label}
      </div>
      <div className="text-2xl font-semibold text-[var(--color-text-primary)]">
        {value}
      </div>
      {sub && (
        <div className="mt-1 text-xs text-[var(--color-text-secondary)]">
          {sub}
        </div>
      )}
    </div>
  );
}

export function AnalyticsScreen() {
  const { isPro, showUpgrade, setShowUpgrade } = useProFeature();
  const [range, setRange] = useState<RangeOption>('30d');
  const [groupBy, setGroupBy] = useState<TimeTrackingGroupBy>('project');

  const [summary, setSummary] = useState<AnalyticsSummary | null>(null);
  const [productivity, setProductivity] =
    useState<ProductivityScoreResponse | null>(null);
  const [timeTracking, setTimeTracking] = useState<TimeTrackingResponse | null>(
    null,
  );
  const [correlations, setCorrelations] =
    useState<HabitCorrelationResponse | null>(null);
  const [loading, setLoading] = useState(false);

  const dateParams = useMemo(() => rangeToParams(range), [range]);

  const loadAll = useCallback(async () => {
    if (!isPro) return;
    setLoading(true);
    const params = dateParams;
    try {
      // suppressErrorToast: these calls own their error UX via the
      // consolidated message below, so the global interceptor must not
      // also toast (that produced a double / per-call toast storm).
      const opts = { suppressErrorToast: true };
      const [sum, score, time, corr] = await Promise.allSettled([
        dashboardApi.getAnalyticsSummary(opts),
        analyticsApi.productivityScore({ period: 'daily', ...params }, opts),
        analyticsApi.timeTracking({ group_by: groupBy, ...params }, opts),
        analyticsApi.habitCorrelations(opts),
      ]);

      if (sum.status === 'fulfilled') setSummary(sum.value);
      if (score.status === 'fulfilled') setProductivity(score.value);
      if (time.status === 'fulfilled') setTimeTracking(time.value);
      if (corr.status === 'fulfilled') setCorrelations(corr.value);

      // Only count genuinely unexpected failures. The AI-backed habit
      // correlation endpoint is rate-limited to once per day (429) and is
      // gated behind the AI-features toggle (451); a "not found"/empty
      // (404) is also expected. None of those mean analytics is broken, so
      // they must not trigger the persistent "calls failed" toast (B-07).
      const failures = [sum, score, time, corr].filter(
        (r) => r.status === 'rejected' && !isExpectedAnalyticsError(r.reason),
      ).length;
      if (failures > 0) {
        toast.error(
          `${failures} analytics call${failures === 1 ? '' : 's'} failed — partial results shown`,
        );
      }
    } finally {
      setLoading(false);
    }
  }, [isPro, dateParams, groupBy]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load analytics on mount and when filters change
    loadAll();
  }, [loadAll]);

  const productivityChartData = useMemo(
    () =>
      productivity?.scores.map((s) => ({
        date: format(parseISO(s.date), 'MMM d'),
        iso: s.date,
        score: Math.round(s.score),
        task_completion: s.breakdown.task_completion,
        habit_completion: s.breakdown.habit_completion,
      })) ?? [],
    [productivity],
  );

  const timeTrackingChartData = useMemo(
    () =>
      timeTracking?.entries
        .slice(0, 10)
        .map((e) => ({
          group: e.group,
          actual: e.total_minutes,
          estimated: e.estimated_total,
          accuracy: e.accuracy_pct,
        })) ?? [],
    [timeTracking],
  );

  if (!isPro) {
    return (
      <div className="mx-auto max-w-5xl pb-16">
        <header className="mb-6">
          <h1 className="flex items-center gap-2 text-xl font-semibold text-[var(--color-text-primary)]">
            <BarChart3
              className="h-5 w-5 text-[var(--color-accent)]"
              aria-hidden="true"
            />
            Analytics
          </h1>
        </header>
        <EmptyState
          title="Pro feature"
          description="Productivity analytics, time tracking, and habit correlations are part of Pro. Upgrade to unlock the dashboard."
          actionLabel="Upgrade to Pro"
          onAction={() => setShowUpgrade(true)}
        />
        <ProUpgradeModal
          isOpen={showUpgrade}
          onClose={() => setShowUpgrade(false)}
          featureName="Analytics Dashboard"
          featureDescription="Daily productivity scores, time-tracking accuracy, and habit correlations."
        />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-6xl pb-16">
      <header className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-xl font-semibold text-[var(--color-text-primary)]">
            <BarChart3
              className="h-5 w-5 text-[var(--color-accent)]"
              aria-hidden="true"
            />
            Analytics
          </h1>
          <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
            Productivity, time accuracy, and habit impact over the last{' '}
            {RANGE_LABELS[range]}.
          </p>
        </div>
        <div
          className="flex items-center gap-1 rounded-lg border border-[var(--color-border)] p-1"
          role="radiogroup"
          aria-label="Time range"
        >
          {(Object.keys(RANGE_LABELS) as RangeOption[]).map((key) => (
            <button
              key={key}
              role="radio"
              aria-checked={range === key}
              onClick={() => setRange(key)}
              className={`rounded-md px-3 py-1 text-xs font-medium transition-colors ${
                range === key
                  ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                  : 'text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
              }`}
            >
              {RANGE_LABELS[key]}
            </button>
          ))}
        </div>
      </header>

      {loading && !summary && (
        <div className="flex items-center gap-3 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-6">
          <Loader2 className="h-5 w-5 animate-spin text-[var(--color-accent)]" />
          <span className="text-sm text-[var(--color-text-primary)]">
            Loading analytics…
          </span>
        </div>
      )}

      {summary && (
        <section
          aria-label="Summary tiles"
          className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-4"
        >
          <SummaryTile
            label="Today"
            value={`${Math.round(summary.today.score)}`}
            sub={`${summary.today.completed} done · ${summary.today.remaining} left`}
            icon={Activity}
          />
          <SummaryTile
            label="This week"
            value={`${Math.round(summary.this_week.score)}`}
            sub={<TrendBadge trend={summary.this_week.trend} />}
            icon={TrendingUp}
          />
          <SummaryTile
            label="Streak"
            value={`${summary.streaks.current_productive_days}d`}
            sub={`Longest ${summary.streaks.longest_productive_days}d`}
            icon={Flame}
          />
          <SummaryTile
            label="Habits (7d)"
            value={`${Math.round(summary.habits.completion_rate_7d * 100)}%`}
            sub={`30d: ${Math.round(summary.habits.completion_rate_30d * 100)}%`}
            icon={Activity}
          />
        </section>
      )}

      {productivity && productivity.scores.length > 0 && (
        <section className="mb-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-[var(--color-text-primary)]">
                Productivity score
              </h2>
              <p className="text-xs text-[var(--color-text-secondary)]">
                Average {Math.round(productivity.average_score)} ·{' '}
                <TrendBadge trend={productivity.trend} />
              </p>
            </div>
            <div className="flex gap-4 text-xs text-[var(--color-text-secondary)]">
              {productivity.best_day && (
                <span>
                  Best {format(parseISO(productivity.best_day.date), 'MMM d')}:
                  &nbsp;{Math.round(productivity.best_day.score)}
                </span>
              )}
              {productivity.worst_day && (
                <span>
                  Worst{' '}
                  {format(parseISO(productivity.worst_day.date), 'MMM d')}:
                  &nbsp;{Math.round(productivity.worst_day.score)}
                </span>
              )}
            </div>
          </div>
          <div style={{ width: '100%', height: 240 }}>
            <ResponsiveContainer>
              <AreaChart data={productivityChartData}>
                <defs>
                  <linearGradient id="scoreFill" x1="0" y1="0" x2="0" y2="1">
                    <stop
                      offset="0%"
                      stopColor="var(--color-accent)"
                      stopOpacity={0.4}
                    />
                    <stop
                      offset="100%"
                      stopColor="var(--color-accent)"
                      stopOpacity={0}
                    />
                  </linearGradient>
                </defs>
                <CartesianGrid
                  strokeDasharray="3 3"
                  stroke="var(--color-border)"
                />
                <XAxis
                  dataKey="date"
                  stroke="var(--color-text-secondary)"
                  fontSize={11}
                />
                <YAxis
                  domain={[0, 100]}
                  stroke="var(--color-text-secondary)"
                  fontSize={11}
                />
                <Tooltip
                  contentStyle={{
                    background: 'var(--color-bg-card)',
                    border: '1px solid var(--color-border)',
                    borderRadius: 8,
                    color: 'var(--color-text-primary)',
                  }}
                />
                <Area
                  type="monotone"
                  dataKey="score"
                  stroke="var(--color-accent)"
                  strokeWidth={2}
                  fill="url(#scoreFill)"
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </section>
      )}

      {timeTracking && (
        <section className="mb-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 className="flex items-center gap-2 text-sm font-semibold text-[var(--color-text-primary)]">
                <Clock className="h-4 w-4" aria-hidden="true" />
                Time tracking
              </h2>
              <p className="text-xs text-[var(--color-text-secondary)]">
                {formatMinutes(timeTracking.total_tracked_minutes)} tracked ·{' '}
                {Math.round(timeTracking.overall_accuracy_pct)}% accurate vs.
                estimates
                {timeTracking.most_time_consuming_project &&
                  ` · Most time: ${timeTracking.most_time_consuming_project}`}
              </p>
            </div>
            <div
              className="flex items-center gap-1 rounded-lg border border-[var(--color-border)] p-1"
              role="radiogroup"
              aria-label="Group time-tracking by"
            >
              {(['project', 'tag', 'priority', 'day'] as TimeTrackingGroupBy[]).map(
                (opt) => (
                  <button
                    key={opt}
                    role="radio"
                    aria-checked={groupBy === opt}
                    onClick={() => setGroupBy(opt)}
                    className={`rounded-md px-2 py-0.5 text-[11px] font-medium capitalize transition-colors ${
                      groupBy === opt
                        ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                        : 'text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
                    }`}
                  >
                    {opt}
                  </button>
                ),
              )}
            </div>
          </div>
          {timeTrackingChartData.length === 0 ? (
            <p className="py-8 text-center text-sm italic text-[var(--color-text-secondary)]">
              No tracked time in this range.
            </p>
          ) : (
            <div style={{ width: '100%', height: 260 }}>
              <ResponsiveContainer>
                <BarChart data={timeTrackingChartData}>
                  <CartesianGrid
                    strokeDasharray="3 3"
                    stroke="var(--color-border)"
                  />
                  <XAxis
                    dataKey="group"
                    stroke="var(--color-text-secondary)"
                    fontSize={11}
                  />
                  <YAxis
                    stroke="var(--color-text-secondary)"
                    fontSize={11}
                    tickFormatter={(m: number) => `${Math.round(m / 60)}h`}
                  />
                  <Tooltip
                    contentStyle={{
                      background: 'var(--color-bg-card)',
                      border: '1px solid var(--color-border)',
                      borderRadius: 8,
                      color: 'var(--color-text-primary)',
                    }}
                    formatter={(v: unknown) =>
                      typeof v === 'number' ? formatMinutes(v) : String(v)
                    }
                  />
                  <Legend />
                  <Bar
                    dataKey="estimated"
                    fill="var(--color-text-secondary)"
                    name="Estimated"
                  />
                  <Bar
                    dataKey="actual"
                    fill="var(--color-accent)"
                    name="Actual"
                  >
                    {timeTrackingChartData.map((entry, index) => (
                      <Cell
                        key={`cell-${index}`}
                        fill={
                          entry.accuracy >= 80
                            ? 'var(--prism-success, var(--color-accent))'
                            : entry.accuracy >= 50
                            ? 'var(--color-accent)'
                            : 'var(--prism-destructive, var(--color-accent))'
                        }
                      />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </section>
      )}

      {/* Project burndown — computed client-side (see ProjectProgressPanel). */}
      <div className="mb-6">
        <ProjectProgressPanel
          startIso={dateParams.start_date}
          endIso={dateParams.end_date}
        />
      </div>

      {correlations && correlations.correlations.length > 0 && (
        <section className="mb-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
          <div className="mb-3">
            <h2 className="text-sm font-semibold text-[var(--color-text-primary)]">
              Habit correlations
            </h2>
            <p className="text-xs text-[var(--color-text-secondary)]">
              {correlations.top_insight}
            </p>
          </div>
          <ul className="flex flex-col gap-2">
            {correlations.correlations.map((c) => (
              <li
                key={c.habit}
                className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3"
              >
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-[var(--color-text-primary)]">
                      {c.habit}
                    </span>
                    <span
                      className={`rounded-full px-1.5 py-0.5 text-[10px] font-medium uppercase ${
                        c.correlation === 'positive'
                          ? 'bg-emerald-500/10 text-emerald-500'
                          : c.correlation === 'negative'
                          ? 'bg-red-500/10 text-red-500'
                          : 'bg-[var(--color-border)] text-[var(--color-text-secondary)]'
                      }`}
                    >
                      {c.correlation}
                    </span>
                  </div>
                  <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
                    {c.interpretation}
                  </p>
                </div>
                <div className="text-right text-xs text-[var(--color-text-secondary)]">
                  <div>
                    Done:{' '}
                    <span className="font-semibold text-[var(--color-text-primary)]">
                      {Math.round(c.done_productivity)}
                    </span>
                  </div>
                  <div>
                    Skipped:{' '}
                    <span className="font-semibold text-[var(--color-text-primary)]">
                      {Math.round(c.not_done_productivity)}
                    </span>
                  </div>
                </div>
              </li>
            ))}
          </ul>
          {correlations.recommendation && (
            <p className="mt-3 rounded-lg bg-[var(--color-accent)]/5 p-3 text-sm text-[var(--color-text-primary)]">
              <span className="font-medium">Recommendation: </span>
              {correlations.recommendation}
            </p>
          )}
        </section>
      )}

      {!loading &&
        !summary &&
        !productivity &&
        !timeTracking &&
        !correlations && (
          <EmptyState
            title="No analytics data yet"
            description="Complete a few tasks and habits to start populating your dashboard."
          />
        )}
    </div>
  );
}
