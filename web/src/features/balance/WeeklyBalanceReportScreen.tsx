import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowLeft,
  ChevronLeft,
  ChevronRight,
  AlertTriangle,
  Scale,
  TrendingUp,
  TrendingDown,
  Minus,
} from 'lucide-react';
import {
  DEFAULT_BALANCE_PREFERENCES,
  getBalancePreferences,
  subscribeToBalancePreferences,
  type BalancePreferences,
} from '@/api/firestore/balancePreferences';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { useTaskStore } from '@/stores/taskStore';
import {
  TRACKED_CATEGORIES,
  type BalanceConfig,
} from '@/utils/balanceTracker';
import type { LifeCategory, TaskMode } from '@/types/task';
import {
  LIFE_CATEGORY_COLOR,
  LIFE_CATEGORY_LABEL,
} from '@/theme/lifeCategoryColors';
import {
  computeWeeklyBalanceReport,
  computeWeekWindow,
  deltaPercentPoints,
  formatWeekLabel,
  shiftWeek,
  type WeeklyBalanceReport,
  type WeeklyWindow,
} from './weeklyBalanceReport';
import {
  computeWeeklyModeReport,
  modeDeltaPercentPoints,
  type WeeklyModeReport,
} from './weeklyModeReport';
import { TRACKED_MODES } from '@/utils/modeBalanceTracker';

/**
 * Weekly Balance Report (parity audit C.2c) — full-screen analytics view
 * mirroring Android's `WeeklyBalanceReportScreen.kt`.
 *
 * LifeCategory sections:
 *  1. Week header with prev/next chevrons.
 *  2. Stacked-bar of weekly ratio across LifeCategory + total tracked.
 *  3. Target vs actual comparison table.
 *  4. Compare-to-last-week delta panel.
 *  5. Per-day breakdown (Mon..Sun stacked columns) + overload-day count.
 *  6. 4-week trend per category (mini sparkline bars).
 *
 * Task-Mode sections (Pillar 3 — audit DEFERRED→PROCEED 2026-05-15):
 *  7. Mode distribution + total tracked.
 *  8. Mode delta vs last week.
 *  9. Mode per-day breakdown.
 * 10. Mode 4-week trend.
 *
 * Mode does NOT get a Target-vs-actual subsection: per
 * `docs/WORK_PLAY_RELAX.md` § *Descriptive, not prescriptive*, mode is
 * descriptive-only — there are no user-set target ratios and no
 * overload concept. Skipping the Targets subsection for mode preserves
 * that invariant on the surface.
 *
 * Burnout / cognitive-load sections from the Android screen remain out
 * of scope — the web `burnoutScorer` has a different input shape
 * (breaches + mood logs vs Android's task-derived score) and the
 * cognitive-load engine hasn't been ported yet. The cognitive-load
 * balance section is explicitly deferred on Android too
 * (`docs/COGNITIVE_LOAD.md:200–203`).
 */

// LifeCategory palette is the single source of truth shared with
// TodayBalanceBar and the Morning check-in summary — see
// `@/theme/lifeCategoryColors`. These tokens mirror Android's
// `LifeCategoryColor.kt` exactly so the two clients agree pixel-for-pixel
// when comparing the balance bar across devices.
const CATEGORY_COLOR: Record<LifeCategory, string> = LIFE_CATEGORY_COLOR;
const CATEGORY_LABEL: Record<LifeCategory, string> = LIFE_CATEGORY_LABEL;

// Mode palette is deliberately distinct from CATEGORY_COLOR so "Work"
// reads as a different visual entity across the two dimensions — the
// label collides on purpose (mode answers a different question than
// life category) but the colors should not.
const MODE_COLOR: Record<Exclude<TaskMode, 'UNCATEGORIZED'>, string> = {
  WORK: '#0ea5e9', // sky-500 — output
  PLAY: '#f59e0b', // amber-500 — enjoyment
  RELAX: '#10b981', // emerald-500 — restored energy
};

const MODE_LABEL: Record<TaskMode, string> = {
  WORK: 'Work',
  PLAY: 'Play',
  RELAX: 'Relax',
  UNCATEGORIZED: 'Uncategorized',
};

export function WeeklyBalanceReportScreen() {
  const navigate = useNavigate();
  const tasks = useTaskStore((s) => s.tasks);
  const [prefs, setPrefs] = useState<BalancePreferences>(DEFAULT_BALANCE_PREFERENCES);
  const [window, setWindow] = useState<WeeklyWindow>(() => computeWeekWindow());

  const uid = (() => {
    try {
      return getFirebaseUid();
    } catch {
      return null;
    }
  })();

  useEffect(() => {
    if (!uid) return;
    getBalancePreferences(uid)
      .then((p) => setPrefs(p))
      .catch(() => {
        // Silently fall back to defaults — analytics non-critical.
      });
    const unsub = subscribeToBalancePreferences(uid, setPrefs);
    return () => unsub();
  }, [uid]);

  const config: BalanceConfig = useMemo(
    () => ({
      workTarget: prefs.workTarget / 100,
      personalTarget: prefs.personalTarget / 100,
      selfCareTarget: prefs.selfCareTarget / 100,
      healthTarget: prefs.healthTarget / 100,
      overloadThreshold: prefs.overloadThresholdPct / 100,
    }),
    [prefs],
  );

  const report = useMemo<WeeklyBalanceReport>(
    () => computeWeeklyBalanceReport(tasks, config, window.startMs),
    [tasks, config, window.startMs],
  );

  const modeReport = useMemo<WeeklyModeReport>(
    () => computeWeeklyModeReport(tasks, window.startMs),
    [tasks, window.startMs],
  );

  const isCurrentWeek = window.startMs === computeWeekWindow().startMs;

  return (
    <div className="mx-auto max-w-4xl px-4 py-6">
      {/* Header */}
      <div className="mb-4 flex items-center gap-3">
        <button
          type="button"
          onClick={() => navigate(-1)}
          className="rounded-full p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
          aria-label="Back"
        >
          <ArrowLeft className="h-5 w-5" />
        </button>
        <div className="flex items-center gap-2">
          <Scale className="h-5 w-5 text-[var(--color-accent)]" aria-hidden="true" />
          <h1 className="text-xl font-bold text-[var(--color-text-primary)]">
            Weekly Balance Report
          </h1>
        </div>
      </div>

      {/* Week navigation */}
      <div className="mb-6 flex items-center justify-between rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3">
        <button
          type="button"
          onClick={() => setWindow((w) => shiftWeek(w, -1))}
          className="rounded-full p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
          aria-label="Previous week"
        >
          <ChevronLeft className="h-5 w-5" />
        </button>
        <div className="text-center">
          <div className="text-sm font-semibold text-[var(--color-text-primary)]">
            {formatWeekLabel(window)}
          </div>
          <div className="text-[11px] text-[var(--color-text-secondary)]">
            {report.totalTracked} tracked completion{report.totalTracked === 1 ? '' : 's'}
          </div>
        </div>
        <button
          type="button"
          onClick={() => setWindow((w) => shiftWeek(w, 1))}
          disabled={isCurrentWeek}
          className="rounded-full p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)] disabled:cursor-not-allowed disabled:opacity-30"
          aria-label="Next week"
        >
          <ChevronRight className="h-5 w-5" />
        </button>
      </div>

      {report.totalTracked === 0 && modeReport.totalTracked === 0 ? (
        <EmptyState />
      ) : (
        <div className="flex flex-col gap-4">
          {report.totalTracked > 0 && (
            <>
              <DistributionCard report={report} />
              <TargetComparisonCard report={report} />
              <DeltaCard report={report} />
              <PerDayCard report={report} />
              <FourWeekTrendCard report={report} />
            </>
          )}
          {modeReport.totalTracked > 0 && (
            <>
              <ModeSectionHeader />
              <ModeDistributionCard report={modeReport} />
              <ModeDeltaCard report={modeReport} />
              <ModePerDayCard report={modeReport} />
              <ModeFourWeekTrendCard report={modeReport} />
            </>
          )}
        </div>
      )}
    </div>
  );
}

// ─── Empty state ──────────────────────────────────────────────────────────

function EmptyState() {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-8 text-center">
      <Scale
        className="mx-auto mb-3 h-10 w-10 text-[var(--color-text-secondary)]"
        aria-hidden="true"
      />
      <p className="text-sm font-medium text-[var(--color-text-primary)]">
        No completions this week yet
      </p>
      <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
        Categorize and complete tasks to populate the balance report.
      </p>
    </div>
  );
}

// ─── 1. Distribution stacked-bar card ─────────────────────────────────────

function DistributionCard({ report }: { report: WeeklyBalanceReport }) {
  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
        Category Distribution
      </h2>
      <div
        className="flex h-3 w-full overflow-hidden rounded-full bg-[var(--color-bg-secondary)]"
        role="img"
        aria-label="Life-category distribution this week"
      >
        {TRACKED_CATEGORIES.map((cat) => {
          const pct = (report.currentRatios[cat] ?? 0) * 100;
          if (pct <= 0) return null;
          return (
            <div
              key={cat}
              style={{ width: `${pct}%`, backgroundColor: CATEGORY_COLOR[cat] }}
              title={`${CATEGORY_LABEL[cat]}: ${Math.round(pct)}%`}
            />
          );
        })}
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
        {TRACKED_CATEGORIES.map((cat) => {
          const pct = Math.round((report.currentRatios[cat] ?? 0) * 100);
          const count = report.currentCounts[cat] ?? 0;
          return (
            <div
              key={cat}
              className="flex items-center gap-2 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2"
            >
              <span
                className="h-2.5 w-2.5 shrink-0 rounded-full"
                style={{ backgroundColor: CATEGORY_COLOR[cat] }}
                aria-hidden="true"
              />
              <div className="min-w-0 flex-1">
                <div className="truncate text-xs font-medium text-[var(--color-text-primary)]">
                  {CATEGORY_LABEL[cat]}
                </div>
                <div className="text-[11px] text-[var(--color-text-secondary)]">
                  {count} · {pct}%
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}

// ─── 2. Target vs actual ──────────────────────────────────────────────────

function TargetComparisonCard({ report }: { report: WeeklyBalanceReport }) {
  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
        Target vs Actual
      </h2>
      <div className="flex flex-col gap-2">
        {TRACKED_CATEGORIES.map((cat) => {
          const target = Math.round((report.targetRatios[cat] ?? 0) * 100);
          const actual = Math.round((report.currentRatios[cat] ?? 0) * 100);
          const diff = actual - target;
          return (
            <div key={cat} className="flex flex-col gap-1">
              <div className="flex items-center justify-between text-xs">
                <span className="flex items-center gap-1.5 font-medium text-[var(--color-text-primary)]">
                  <span
                    className="h-2 w-2 rounded-full"
                    style={{ backgroundColor: CATEGORY_COLOR[cat] }}
                    aria-hidden="true"
                  />
                  {CATEGORY_LABEL[cat]}
                </span>
                <span className="text-[var(--color-text-secondary)]">
                  Target {target}% · Actual {actual}%{' '}
                  <span
                    className={
                      diff === 0
                        ? 'text-[var(--color-text-secondary)]'
                        : diff > 0
                          ? 'text-amber-500'
                          : 'text-sky-500'
                    }
                  >
                    ({diff > 0 ? '+' : ''}
                    {diff}pp)
                  </span>
                </span>
              </div>
              <div className="relative h-2 w-full overflow-hidden rounded-full bg-[var(--color-bg-secondary)]">
                <div
                  className="absolute inset-y-0 left-0"
                  style={{
                    width: `${Math.min(100, actual)}%`,
                    backgroundColor: CATEGORY_COLOR[cat],
                    opacity: 0.9,
                  }}
                />
                <div
                  className="absolute inset-y-0 w-0.5 bg-[var(--color-text-primary)] opacity-70"
                  style={{ left: `${Math.min(100, target)}%` }}
                  aria-label={`Target ${target}%`}
                />
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}

// ─── 3. Delta vs last week ────────────────────────────────────────────────

function DeltaCard({ report }: { report: WeeklyBalanceReport }) {
  const priorTotal = TRACKED_CATEGORIES.reduce(
    (acc, c) => acc + (report.priorCounts[c] ?? 0),
    0,
  );
  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
        Compared to Last Week
      </h2>
      {priorTotal === 0 ? (
        <p className="text-xs text-[var(--color-text-secondary)]">
          No tracked completions last week — nothing to compare yet.
        </p>
      ) : (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {TRACKED_CATEGORIES.map((cat) => {
            const dpp = deltaPercentPoints(
              report.currentRatios,
              report.priorRatios,
              cat,
            );
            const Icon = dpp > 0 ? TrendingUp : dpp < 0 ? TrendingDown : Minus;
            const tone =
              dpp === 0
                ? 'text-[var(--color-text-secondary)]'
                : dpp > 0
                  ? 'text-emerald-500'
                  : 'text-sky-500';
            return (
              <div
                key={cat}
                className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3"
              >
                <div className="mb-1 flex items-center gap-1.5">
                  <span
                    className="h-2 w-2 rounded-full"
                    style={{ backgroundColor: CATEGORY_COLOR[cat] }}
                    aria-hidden="true"
                  />
                  <span className="text-[11px] font-medium text-[var(--color-text-primary)]">
                    {CATEGORY_LABEL[cat]}
                  </span>
                </div>
                <div className={`flex items-center gap-1 text-sm font-semibold ${tone}`}>
                  <Icon className="h-3.5 w-3.5" aria-hidden="true" />
                  {dpp > 0 ? '+' : ''}
                  {dpp}pp
                </div>
                <div className="mt-0.5 text-[10px] text-[var(--color-text-secondary)]">
                  {report.currentCounts[cat] ?? 0} this · {report.priorCounts[cat] ?? 0}{' '}
                  last
                </div>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}

// ─── 4. Per-day breakdown ─────────────────────────────────────────────────

function PerDayCard({ report }: { report: WeeklyBalanceReport }) {
  const maxTotal = Math.max(1, ...report.perDay.map((d) => d.total));
  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-[var(--color-text-primary)]">
          Per-Day Breakdown
        </h2>
        {report.overloadDays > 0 && (
          <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/10 px-2 py-0.5 text-[11px] font-medium text-amber-500">
            <AlertTriangle className="h-3 w-3" aria-hidden="true" />
            {report.overloadDays} overload day{report.overloadDays === 1 ? '' : 's'}
          </span>
        )}
      </div>
      <div className="flex h-32 items-end gap-2">
        {report.perDay.map((day, i) => (
          <DayColumn key={i} day={day} maxTotal={maxTotal} />
        ))}
      </div>
      <p className="mt-2 text-[10px] text-[var(--color-text-secondary)]">
        Overload day: WORK ratio above target by more than the threshold
        ({Math.round(report.overloadThreshold * 100)}pp).
      </p>
    </section>
  );
}

function DayColumn({
  day,
  maxTotal,
}: {
  day: WeeklyBalanceReport['perDay'][number];
  maxTotal: number;
}) {
  const heightPct = day.total > 0 ? (day.total / maxTotal) * 100 : 0;
  return (
    <div className="flex flex-1 flex-col items-center gap-1">
      <div className="flex w-full flex-1 items-end justify-center">
        <div
          className="relative w-full overflow-hidden rounded-md bg-[var(--color-bg-secondary)]"
          style={{ height: `${Math.max(4, heightPct)}%`, minHeight: '4px' }}
          title={`${day.total} task${day.total === 1 ? '' : 's'}`}
        >
          {TRACKED_CATEGORIES.map((cat) => {
            const c = day.counts[cat] ?? 0;
            if (c === 0 || day.total === 0) return null;
            return (
              <div
                key={cat}
                style={{
                  height: `${(c / day.total) * 100}%`,
                  backgroundColor: CATEGORY_COLOR[cat],
                }}
              />
            );
          })}
        </div>
      </div>
      <div
        className={`text-[10px] font-medium ${
          day.isOverloaded
            ? 'text-amber-500'
            : 'text-[var(--color-text-secondary)]'
        }`}
      >
        {day.label}
      </div>
      <div className="text-[10px] text-[var(--color-text-secondary)]">{day.total}</div>
    </div>
  );
}

// ─── 5. 4-week trend ──────────────────────────────────────────────────────

function FourWeekTrendCard({ report }: { report: WeeklyBalanceReport }) {
  const hasAnyTrend = TRACKED_CATEGORIES.some((c) =>
    report.fourWeekCounts[c].some((v) => v > 0),
  );
  if (!hasAnyTrend) return null;
  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
        4-Week Trend
      </h2>
      <div className="flex flex-col gap-2">
        {TRACKED_CATEGORIES.map((cat) => {
          const counts = report.fourWeekCounts[cat];
          const ratios = report.fourWeekTrend[cat];
          const thisWk = counts[counts.length - 1] ?? 0;
          const lastWk = counts[counts.length - 2] ?? 0;
          const delta =
            lastWk === 0 && thisWk === 0
              ? '—'
              : thisWk > lastWk
                ? `▲ ${thisWk - lastWk}`
                : thisWk < lastWk
                  ? `▼ ${lastWk - thisWk}`
                  : `= ${thisWk}`;
          const maxRatio = Math.max(0.01, ...ratios);
          return (
            <div key={cat} className="flex items-center gap-3">
              <div className="w-20 shrink-0">
                <div
                  className="text-xs font-medium"
                  style={{ color: CATEGORY_COLOR[cat] }}
                >
                  {CATEGORY_LABEL[cat]}
                </div>
                <div className="text-[10px] text-[var(--color-text-secondary)]">
                  {thisWk} {delta}
                </div>
              </div>
              <div className="flex h-8 flex-1 items-end gap-1">
                {ratios.map((r, i) => {
                  const isLast = i === ratios.length - 1;
                  const h = (r / maxRatio) * 100;
                  return (
                    <div
                      key={i}
                      className="flex-1 rounded-sm"
                      style={{
                        height: `${Math.max(4, h)}%`,
                        backgroundColor: CATEGORY_COLOR[cat],
                        opacity: isLast ? 1 : 0.4,
                      }}
                      title={`Week ${i + 1}: ${counts[i] ?? 0}`}
                    />
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}

// ─── Mode sections (Pillar 3 — Work / Play / Relax) ───────────────────────
//
// Mode is the orthogonal "what does this task produce?" axis (Work →
// output, Play → enjoyment, Relax → restored energy). Copy is strictly
// factual — never prescriptive — per
// `docs/WORK_PLAY_RELAX.md` § *Descriptive, not prescriptive*. There is
// no Target-vs-actual subsection on purpose; mode targets do not exist
// as a user-tunable concept.

function ModeSectionHeader() {
  return (
    <div className="mt-2 flex items-center gap-2">
      <Scale className="h-4 w-4 text-[var(--color-accent)]" aria-hidden="true" />
      <h2 className="text-base font-semibold text-[var(--color-text-primary)]">
        Mode
      </h2>
      <span className="text-[11px] text-[var(--color-text-secondary)]">
        Work / Play / Relax
      </span>
    </div>
  );
}

function ModeDistributionCard({ report }: { report: WeeklyModeReport }) {
  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-[var(--color-text-primary)]">
          Mode Distribution
        </h2>
        <span className="text-[11px] text-[var(--color-text-secondary)]">
          {report.totalTracked} tracked
        </span>
      </div>
      <div
        className="flex h-3 w-full overflow-hidden rounded-full bg-[var(--color-bg-secondary)]"
        role="img"
        aria-label="Task-mode distribution this week"
      >
        {TRACKED_MODES.map((mode) => {
          const pct = (report.currentRatios[mode] ?? 0) * 100;
          if (pct <= 0) return null;
          return (
            <div
              key={mode}
              style={{ width: `${pct}%`, backgroundColor: MODE_COLOR[mode] }}
              title={`${MODE_LABEL[mode]}: ${Math.round(pct)}%`}
            />
          );
        })}
      </div>
      <div className="mt-3 grid grid-cols-3 gap-2">
        {TRACKED_MODES.map((mode) => {
          const pct = Math.round((report.currentRatios[mode] ?? 0) * 100);
          const count = report.currentCounts[mode] ?? 0;
          return (
            <div
              key={mode}
              className="flex items-center gap-2 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2"
            >
              <span
                className="h-2.5 w-2.5 shrink-0 rounded-full"
                style={{ backgroundColor: MODE_COLOR[mode] }}
                aria-hidden="true"
              />
              <div className="min-w-0 flex-1">
                <div className="truncate text-xs font-medium text-[var(--color-text-primary)]">
                  {MODE_LABEL[mode]}
                </div>
                <div className="text-[11px] text-[var(--color-text-secondary)]">
                  {count} · {pct}%
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}

function ModeDeltaCard({ report }: { report: WeeklyModeReport }) {
  const priorTotal = TRACKED_MODES.reduce(
    (acc, m) => acc + (report.priorCounts[m] ?? 0),
    0,
  );
  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
        Mode Compared to Last Week
      </h2>
      {priorTotal === 0 ? (
        <p className="text-xs text-[var(--color-text-secondary)]">
          No tracked completions last week — nothing to compare yet.
        </p>
      ) : (
        <div className="grid grid-cols-3 gap-2">
          {TRACKED_MODES.map((mode) => {
            const dpp = modeDeltaPercentPoints(
              report.currentRatios,
              report.priorRatios,
              mode,
            );
            const Icon = dpp > 0 ? TrendingUp : dpp < 0 ? TrendingDown : Minus;
            // Neutral tone for non-zero deltas — mode is descriptive, not
            // prescriptive, so neither direction is "good" or "bad".
            const tone =
              dpp === 0
                ? 'text-[var(--color-text-secondary)]'
                : 'text-[var(--color-text-primary)]';
            return (
              <div
                key={mode}
                className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3"
              >
                <div className="mb-1 flex items-center gap-1.5">
                  <span
                    className="h-2 w-2 rounded-full"
                    style={{ backgroundColor: MODE_COLOR[mode] }}
                    aria-hidden="true"
                  />
                  <span className="text-[11px] font-medium text-[var(--color-text-primary)]">
                    {MODE_LABEL[mode]}
                  </span>
                </div>
                <div className={`flex items-center gap-1 text-sm font-semibold ${tone}`}>
                  <Icon className="h-3.5 w-3.5" aria-hidden="true" />
                  {dpp > 0 ? '+' : ''}
                  {dpp}pp
                </div>
                <div className="mt-0.5 text-[10px] text-[var(--color-text-secondary)]">
                  {report.currentCounts[mode] ?? 0} this ·{' '}
                  {report.priorCounts[mode] ?? 0} last
                </div>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}

function ModePerDayCard({ report }: { report: WeeklyModeReport }) {
  const maxTotal = Math.max(1, ...report.perDay.map((d) => d.total));
  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
        Mode Per-Day Breakdown
      </h2>
      <div className="flex h-32 items-end gap-2">
        {report.perDay.map((day, i) => (
          <ModeDayColumn key={i} day={day} maxTotal={maxTotal} />
        ))}
      </div>
    </section>
  );
}

function ModeDayColumn({
  day,
  maxTotal,
}: {
  day: WeeklyModeReport['perDay'][number];
  maxTotal: number;
}) {
  const heightPct = day.total > 0 ? (day.total / maxTotal) * 100 : 0;
  return (
    <div className="flex flex-1 flex-col items-center gap-1">
      <div className="flex w-full flex-1 items-end justify-center">
        <div
          className="relative w-full overflow-hidden rounded-md bg-[var(--color-bg-secondary)]"
          style={{ height: `${Math.max(4, heightPct)}%`, minHeight: '4px' }}
          title={`${day.total} task${day.total === 1 ? '' : 's'}`}
        >
          {TRACKED_MODES.map((mode) => {
            const c = day.counts[mode] ?? 0;
            if (c === 0 || day.total === 0) return null;
            return (
              <div
                key={mode}
                style={{
                  height: `${(c / day.total) * 100}%`,
                  backgroundColor: MODE_COLOR[mode],
                }}
              />
            );
          })}
        </div>
      </div>
      <div className="text-[10px] font-medium text-[var(--color-text-secondary)]">
        {day.label}
      </div>
      <div className="text-[10px] text-[var(--color-text-secondary)]">{day.total}</div>
    </div>
  );
}

function ModeFourWeekTrendCard({ report }: { report: WeeklyModeReport }) {
  const hasAnyTrend = TRACKED_MODES.some((m) =>
    report.fourWeekCounts[m].some((v) => v > 0),
  );
  if (!hasAnyTrend) return null;
  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
        Mode 4-Week Trend
      </h2>
      <div className="flex flex-col gap-2">
        {TRACKED_MODES.map((mode) => {
          const counts = report.fourWeekCounts[mode];
          const ratios = report.fourWeekTrend[mode];
          const thisWk = counts[counts.length - 1] ?? 0;
          const lastWk = counts[counts.length - 2] ?? 0;
          const delta =
            lastWk === 0 && thisWk === 0
              ? '—'
              : thisWk > lastWk
                ? `▲ ${thisWk - lastWk}`
                : thisWk < lastWk
                  ? `▼ ${lastWk - thisWk}`
                  : `= ${thisWk}`;
          const maxRatio = Math.max(0.01, ...ratios);
          return (
            <div key={mode} className="flex items-center gap-3">
              <div className="w-20 shrink-0">
                <div
                  className="text-xs font-medium"
                  style={{ color: MODE_COLOR[mode] }}
                >
                  {MODE_LABEL[mode]}
                </div>
                <div className="text-[10px] text-[var(--color-text-secondary)]">
                  {thisWk} {delta}
                </div>
              </div>
              <div className="flex h-8 flex-1 items-end gap-1">
                {ratios.map((r, i) => {
                  const isLast = i === ratios.length - 1;
                  const h = (r / maxRatio) * 100;
                  return (
                    <div
                      key={i}
                      className="flex-1 rounded-sm"
                      style={{
                        height: `${Math.max(4, h)}%`,
                        backgroundColor: MODE_COLOR[mode],
                        opacity: isLast ? 1 : 0.4,
                      }}
                      title={`Week ${i + 1}: ${counts[i] ?? 0}`}
                    />
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}
