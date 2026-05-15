import { useCallback, useEffect, useMemo, useState } from 'react';
import { format, parseISO, subDays } from 'date-fns';
import { CalendarCheck, Flame, Sun } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { Select } from '@/components/ui/Select';
import {
  getRecentCheckIns,
  type CheckInLog,
} from '@/api/firestore/checkInLogs';
import {
  getLogsInRange as getMoodLogsInRange,
  type MoodEnergyLog,
} from '@/api/firestore/moodEnergyLogs';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { useSettingsStore } from '@/stores/settingsStore';
import { useLogicalToday } from '@/utils/useLogicalToday';
import { computeCheckInStreak } from '@/utils/checkInStreak';
import {
  selectForgivenessConfig,
  useAdvancedTuningStore,
} from '@/stores/advancedTuningStore';

/**
 * Web port of Android's CheckInHistoryScreen (parity C.5c). Shows the
 * user's prior check-ins as a scrollable list with a 7×N heatmap. Each
 * row surfaces the date, a running streak indicator, the step flags
 * (medications / tasks / habits), and any same-day mood/energy snapshot.
 *
 * The check-in log itself stores only steps + flags (mirroring Android's
 * `CheckInLogEntity`), so mood/energy values are joined from
 * `mood_energy_logs` — same `dateIso` key on both collections. There's
 * no balance-ratio column on `CheckInLogEntity`; we surface step coverage
 * instead, which is what the Android screen does too.
 */

const RANGE_OPTIONS = [
  { value: '30', label: 'Last 30 days' },
  { value: '60', label: 'Last 60 days' },
  { value: '90', label: 'Last 90 days' },
];

const HEATMAP_COLS = 13; // ~90 days / 7
const HEATMAP_ROWS = 7;

function dateKey(d: Date): string {
  return format(d, 'yyyy-MM-dd');
}

function bestMoodForDay(
  moodLogs: MoodEnergyLog[],
  dateIso: string,
): MoodEnergyLog | null {
  // Prefer the morning entry — that's the slot most aligned with the
  // morning check-in. Fall back to any same-day entry, newest first.
  const sameDay = moodLogs.filter((l) => l.date_iso === dateIso);
  if (sameDay.length === 0) return null;
  const morning = sameDay.find((l) => l.time_of_day === 'morning');
  if (morning) return morning;
  return sameDay.sort((a, b) => b.created_at - a.created_at)[0];
}

function stepCoverage(log: CheckInLog): number {
  // 0..1 — fraction of the four step flags the user acknowledged.
  let n = 0;
  if (log.medications_confirmed) n += 1;
  if (log.tasks_reviewed) n += 1;
  if (log.habits_completed) n += 1;
  if (log.steps_completed_csv.trim().length > 0) n += 1;
  return n / 4;
}

export function CheckInHistoryScreen() {
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const todayIso = useLogicalToday(startOfDayHour);
  const [rangeDays, setRangeDays] = useState<number>(90);
  const [logs, setLogs] = useState<CheckInLog[]>([]);
  const [moodLogs, setMoodLogs] = useState<MoodEnergyLog[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const uid = getFirebaseUid();
      const startIso = format(subDays(parseISO(todayIso), rangeDays - 1), 'yyyy-MM-dd');
      const [recent, moods] = await Promise.all([
        getRecentCheckIns(uid, rangeDays).catch(() => [] as CheckInLog[]),
        getMoodLogsInRange(uid, startIso, todayIso).catch(
          () => [] as MoodEnergyLog[],
        ),
      ]);
      setLogs(recent);
      setMoodLogs(moods);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to load history');
    } finally {
      setLoading(false);
    }
  }, [rangeDays, todayIso]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load on range change
    load();
  }, [load]);

  const forgiveness = useAdvancedTuningStore((s) =>
    selectForgivenessConfig(s.prefs),
  );

  const streak = useMemo(
    () => computeCheckInStreak(logs, todayIso, forgiveness),
    [logs, todayIso, forgiveness],
  );

  const logsByDate = useMemo(() => {
    const m = new Map<string, CheckInLog>();
    for (const log of logs) m.set(log.date_iso, log);
    return m;
  }, [logs]);

  // Walk back from today; for each ISO date emit a row containing the
  // log (if any) + same-day mood + the streak index at that point.
  const rows = useMemo(() => {
    const out: Array<{
      dateIso: string;
      log: CheckInLog | null;
      mood: MoodEnergyLog | null;
    }> = [];
    const today = parseISO(todayIso);
    for (let i = 0; i < rangeDays; i += 1) {
      const d = subDays(today, i);
      const iso = dateKey(d);
      out.push({
        dateIso: iso,
        log: logsByDate.get(iso) ?? null,
        mood: bestMoodForDay(moodLogs, iso),
      });
    }
    return out;
  }, [logsByDate, moodLogs, rangeDays, todayIso]);

  // Heatmap cells — same 90-day window, oldest top-left so the eye
  // reads recency at bottom-right. We slot by (col * rows + row).
  const heatmapCells = useMemo(() => {
    const cells: Array<{
      dateIso: string;
      coverage: number;
      logged: boolean;
    }> = [];
    const total = Math.min(rangeDays, HEATMAP_COLS * HEATMAP_ROWS);
    const today = parseISO(todayIso);
    for (let i = total - 1; i >= 0; i -= 1) {
      const d = subDays(today, i);
      const iso = dateKey(d);
      const log = logsByDate.get(iso);
      cells.push({
        dateIso: iso,
        coverage: log ? stepCoverage(log) : 0,
        logged: !!log,
      });
    }
    return cells;
  }, [logsByDate, rangeDays, todayIso]);

  const totalLogged = logs.length;

  return (
    <div className="mx-auto max-w-3xl pb-16">
      <header className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <CalendarCheck className="h-5 w-5 text-[var(--color-accent)]" />
          <div>
            <h1 className="text-xl font-semibold text-[var(--color-text-primary)]">
              Check-In History
            </h1>
            <p className="text-sm text-[var(--color-text-secondary)]">
              Morning grounding logs from the past {rangeDays} days.
            </p>
          </div>
        </div>
        <Select
          options={RANGE_OPTIONS}
          value={String(rangeDays)}
          onChange={(v) => setRangeDays(parseInt(v ?? '90', 10) || 90)}
          className="w-40"
        />
      </header>

      <div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatTile
          label="Current streak"
          value={`${streak.current}d`}
          icon={<Flame className="h-4 w-4 text-amber-500" />}
        />
        <StatTile
          label="Longest streak"
          value={`${streak.longest}d`}
          icon={<Sun className="h-4 w-4 text-[var(--color-accent)]" />}
        />
        <StatTile
          label="Days logged"
          value={`${totalLogged}/${rangeDays}`}
          icon={<CalendarCheck className="h-4 w-4 text-[var(--color-accent)]" />}
        />
      </div>

      <section className="mb-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
        <h2 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
          Coverage heatmap
        </h2>
        <p className="mb-3 text-xs text-[var(--color-text-secondary)]">
          Darker squares = more steps acknowledged. Older days on the left.
        </p>
        <div
          className="grid gap-1"
          style={{
            gridTemplateColumns: `repeat(${HEATMAP_COLS}, minmax(0, 1fr))`,
            gridAutoFlow: 'column',
            gridTemplateRows: `repeat(${HEATMAP_ROWS}, minmax(0, 1fr))`,
          }}
          role="img"
          aria-label={`Check-in coverage heatmap, ${heatmapCells.length} days`}
        >
          {heatmapCells.map((cell) => (
            <div
              key={cell.dateIso}
              title={`${cell.dateIso} — ${cell.logged ? `${Math.round(cell.coverage * 100)}% coverage` : 'not logged'}`}
              className="aspect-square rounded-sm"
              style={{
                backgroundColor: cell.logged
                  ? `color-mix(in srgb, var(--color-accent) ${Math.max(20, cell.coverage * 100)}%, transparent)`
                  : 'var(--color-bg-secondary)',
              }}
            />
          ))}
        </div>
      </section>

      {loading && logs.length === 0 ? (
        <p className="py-8 text-center text-sm text-[var(--color-text-secondary)]">
          Loading history…
        </p>
      ) : totalLogged === 0 ? (
        <EmptyState
          icon={<Sun className="h-8 w-8" />}
          title="No check-ins yet"
          description="Tap Check in on Today to build your streak."
        />
      ) : (
        <ul className="flex flex-col gap-2">
          {rows.map((row) => (
            <CheckInRow key={row.dateIso} {...row} />
          ))}
        </ul>
      )}

      <div className="mt-8 flex justify-end">
        <Button variant="ghost" size="sm" onClick={() => load()}>
          Refresh
        </Button>
      </div>
    </div>
  );
}

function StatTile({
  label,
  value,
  icon,
}: {
  label: string;
  value: string;
  icon: React.ReactNode;
}) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <div className="flex items-center gap-2 text-xs uppercase tracking-wide text-[var(--color-text-secondary)]">
        {icon}
        {label}
      </div>
      <div className="mt-2 text-2xl font-semibold text-[var(--color-text-primary)]">
        {value}
      </div>
    </div>
  );
}

function CheckInRow({
  dateIso,
  log,
  mood,
}: {
  dateIso: string;
  log: CheckInLog | null;
  mood: MoodEnergyLog | null;
}) {
  const date = parseISO(dateIso);
  const hasLog = !!log;
  const coverage = log ? stepCoverage(log) : 0;
  const stepCount = log
    ? log.steps_completed_csv
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean).length
    : 0;
  return (
    <li
      className={`rounded-lg border border-[var(--color-border)] p-3 transition-colors ${
        hasLog
          ? 'bg-[var(--color-bg-card)]'
          : 'bg-[var(--color-bg-secondary)]/40'
      }`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-1 items-center gap-3">
          <div
            className="flex h-10 w-10 shrink-0 flex-col items-center justify-center rounded-md text-[10px] font-medium"
            style={{
              backgroundColor: hasLog
                ? `color-mix(in srgb, var(--color-accent) ${Math.max(20, coverage * 100)}%, transparent)`
                : 'var(--color-bg-secondary)',
              color: hasLog
                ? 'var(--color-text-primary)'
                : 'var(--color-text-secondary)',
            }}
            aria-hidden="true"
          >
            <span>{format(date, 'MMM')}</span>
            <span className="text-sm font-semibold">{format(date, 'd')}</span>
          </div>
          <div className="min-w-0">
            <p className="truncate text-sm font-medium text-[var(--color-text-primary)]">
              {format(date, 'EEEE')}
            </p>
            <p className="truncate text-xs text-[var(--color-text-secondary)]">
              {hasLog
                ? `${Math.round(coverage * 100)}% coverage · ${stepCount} note${stepCount === 1 ? '' : 's'}`
                : 'Not logged'}
            </p>
          </div>
        </div>
        {mood && (
          <div className="flex shrink-0 items-center gap-2 text-xs">
            <span className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-[var(--color-text-secondary)]">
              Mood {mood.mood}/5
            </span>
            <span className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-[var(--color-text-secondary)]">
              Energy {mood.energy}/5
            </span>
          </div>
        )}
      </div>
      {hasLog && (
        <div className="mt-2 flex flex-wrap gap-1">
          {log.medications_confirmed && (
            <Tag label="Meds confirmed" />
          )}
          {log.tasks_reviewed && <Tag label="Tasks reviewed" />}
          {log.habits_completed && <Tag label="Habits planned" />}
          {log.steps_completed_csv.trim().length > 0 && (
            <Tag label={`Notes: ${truncate(log.steps_completed_csv, 80)}`} />
          )}
        </div>
      )}
    </li>
  );
}

function Tag({ label }: { label: string }) {
  return (
    <span className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-[10px] text-[var(--color-text-secondary)]">
      {label}
    </span>
  );
}

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  return `${s.slice(0, n - 1)}…`;
}
