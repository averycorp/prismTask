import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { ArrowDownRight, ArrowUpRight, Loader2, Minus, Plus, Smile } from 'lucide-react';
import { format, parseISO, subDays } from 'date-fns';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import {
  deleteLog,
  getLogsInRange,
  type MoodEnergyLog,
} from '@/api/firestore/moodEnergyLogs';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { computeStats, rollupByDay } from '@/utils/moodAnalytics';
import { MoodLogModal } from '@/features/mood/MoodLogModal';
import { MoodCorrelationsSection } from '@/features/mood/MoodCorrelationsSection';

type RangeOption = '7d' | '30d' | '90d';

const RANGE_LABEL: Record<RangeOption, string> = {
  '7d': '7 days',
  '30d': '30 days',
  '90d': '90 days',
};

function rangeToIso(range: RangeOption): { start: string; end: string } {
  const days = range === '7d' ? 7 : range === '30d' ? 30 : 90;
  const end = new Date();
  const start = subDays(end, days - 1);
  return {
    start: format(start, 'yyyy-MM-dd'),
    end: format(end, 'yyyy-MM-dd'),
  };
}

export function MoodScreen() {
  const [range, setRange] = useState<RangeOption>('30d');
  const [logs, setLogs] = useState<MoodEnergyLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);

  const { start, end } = useMemo(() => rangeToIso(range), [range]);

  const load = useCallback(async () => {
    try {
      const uid = getFirebaseUid();
      setLoading(true);
      const res = await getLogsInRange(uid, start, end);
      setLogs(res);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to load mood logs');
    } finally {
      setLoading(false);
    }
  }, [start, end]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load mood logs on mount and when range changes
    load();
  }, [load]);

  const stats = useMemo(() => computeStats(logs), [logs]);
  const chartData = useMemo(
    () =>
      rollupByDay(logs).map((p) => ({
        date: format(parseISO(p.date_iso), 'MMM d'),
        mood: Math.round(p.avg_mood * 10) / 10,
        energy: Math.round(p.avg_energy * 10) / 10,
      })),
    [logs],
  );

  const TrendIcon =
    stats.mood_trend === 'up'
      ? ArrowUpRight
      : stats.mood_trend === 'down'
      ? ArrowDownRight
      : Minus;
  const trendClass =
    stats.mood_trend === 'up'
      ? 'text-emerald-500'
      : stats.mood_trend === 'down'
      ? 'text-red-500'
      : 'text-[var(--color-text-secondary)]';

  const handleDelete = async (log: MoodEnergyLog) => {
    try {
      const uid = getFirebaseUid();
      await deleteLog(uid, log.id);
      setLogs((prev) => prev.filter((l) => l.id !== log.id));
      toast.success('Deleted');
    } catch (e) {
      toast.error((e as Error).message || 'Delete failed');
    }
  };

  return (
    <div className="mx-auto max-w-5xl pb-16">
      <header className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-xl font-semibold text-[var(--color-text-primary)]">
            <Smile
              className="h-5 w-5 text-[var(--color-accent)]"
              aria-hidden="true"
            />
            Mood & Energy
          </h1>
          <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
            Quick daily check-in. Averages smooth multi-entry days so
            morning + afternoon logs don't double-count.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <div
            className="flex items-center gap-1 rounded-lg border border-[var(--color-border)] p-1"
            role="radiogroup"
            aria-label="Time range"
          >
            {(Object.keys(RANGE_LABEL) as RangeOption[]).map((key) => (
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
                {RANGE_LABEL[key]}
              </button>
            ))}
          </div>
          <Button onClick={() => setModalOpen(true)}>
            <Plus className="mr-1 h-4 w-4" />
            Log
          </Button>
        </div>
      </header>

      {loading && logs.length === 0 && (
        <div className="flex items-center gap-3 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-6 text-sm">
          <Loader2 className="h-4 w-4 animate-spin text-[var(--color-accent)]" />
          Loading logs…
        </div>
      )}

      {!loading && logs.length === 0 && (
        <EmptyState
          title="No entries yet"
          description="Tap Log to capture how you feel. A simple 1–5 for mood and energy is enough."
          actionLabel="Log now"
          onAction={() => setModalOpen(true)}
        />
      )}

      {logs.length > 0 && (
        <>
          <section
            aria-label="Summary"
            className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-4"
          >
            <Tile
              label="Entries"
              value={String(stats.total_logs)}
              sub={`${RANGE_LABEL[range]} window`}
            />
            <Tile
              label="Avg mood"
              value={stats.overall_avg_mood.toFixed(1)}
              sub="out of 5"
            />
            <Tile
              label="Avg energy"
              value={stats.overall_avg_energy.toFixed(1)}
              sub="out of 5"
            />
            <Tile
              label="Trend"
              value={
                <span className={`inline-flex items-center gap-1 ${trendClass}`}>
                  <TrendIcon className="h-4 w-4" aria-hidden="true" />
                  {stats.mood_trend}
                </span>
              }
              sub={
                stats.best_day
                  ? `Best ${format(parseISO(stats.best_day.date_iso), 'MMM d')}`
                  : undefined
              }
            />
          </section>

          <section className="mb-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
            <h2 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
              Per-day average
            </h2>
            <div style={{ width: '100%', height: 240 }}>
              <ResponsiveContainer>
                <AreaChart data={chartData}>
                  <defs>
                    <linearGradient id="moodFill" x1="0" y1="0" x2="0" y2="1">
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
                    domain={[0, 5]}
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
                  <Legend />
                  <Area
                    type="monotone"
                    dataKey="mood"
                    stroke="var(--color-accent)"
                    fill="url(#moodFill)"
                    name="Mood"
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
            <div style={{ width: '100%', height: 180, marginTop: 8 }}>
              <ResponsiveContainer>
                <LineChart data={chartData}>
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
                    domain={[0, 5]}
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
                  <Line
                    type="monotone"
                    dataKey="energy"
                    stroke="var(--prism-warning, #f59e0b)"
                    strokeWidth={2}
                    dot={false}
                    name="Energy"
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </section>

          <MoodCorrelationsSection logs={logs} />

          <section>
            <h2 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
              Recent entries
            </h2>
            <ul className="flex flex-col gap-1.5">
              {logs
                .slice()
                .reverse()
                .slice(0, 20)
                .map((log) => (
                  <li
                    key={log.id}
                    className="flex items-start justify-between gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="text-sm text-[var(--color-text-primary)]">
                        {format(parseISO(log.date_iso), 'MMM d')} ·{' '}
                        <span className="capitalize">{log.time_of_day}</span>
                      </p>
                      <p className="text-xs text-[var(--color-text-secondary)]">
                        Mood {log.mood}/5 · Energy {log.energy}/5
                        {log.notes ? ` — ${log.notes}` : ''}
                      </p>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleDelete(log)}
                    >
                      Delete
                    </Button>
                  </li>
                ))}
            </ul>
          </section>
        </>
      )}

      <MoodLogModal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        onLogged={() => load()}
      />
    </div>
  );
}

function Tile({
  label,
  value,
  sub,
}: {
  label: string;
  value: React.ReactNode;
  sub?: React.ReactNode;
}) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <div className="text-xs font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">
        {label}
      </div>
      <div className="mt-1 text-2xl font-semibold text-[var(--color-text-primary)]">
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
