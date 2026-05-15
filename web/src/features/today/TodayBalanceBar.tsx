import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Scale, AlertTriangle, ChevronRight } from 'lucide-react';
import {
  computeBalanceState,
  type BalanceConfig,
  TRACKED_CATEGORIES,
  EMPTY_BALANCE_STATE,
} from '@/utils/balanceTracker';
import {
  computeModeBalanceState,
  TRACKED_MODES,
  EMPTY_MODE_BALANCE_STATE,
} from '@/utils/modeBalanceTracker';
import {
  getBalancePreferences,
  subscribeToBalancePreferences,
  type BalancePreferences,
  DEFAULT_BALANCE_PREFERENCES,
} from '@/api/firestore/balancePreferences';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { useTaskStore } from '@/stores/taskStore';
import { useSettingsStore } from '@/stores/settingsStore';
import type { LifeCategory, TaskMode } from '@/types/task';

/**
 * Today-screen Work-Life Balance bar. Mirrors Android's
 * `TodayBalanceBar.kt` (parity audit C.1a). Renders the four tracked
 * life categories as a horizontal stacked bar over the last 7 days of
 * categorized tasks, with a stacked second row for the Task-Mode
 * dimension (Work / Play / Relax) when the user has tagged any mode
 * (Pillar 3 surface, audit DEFERRED→PROCEED 2026-05-15).
 *
 * Hides entirely when the user has disabled `wlb_show_bar`. The mode
 * row hides independently when `totalTracked === 0` for mode, so users
 * who only tag life categories still see the original surface.
 *
 * All copy here is descriptive — never prescriptive. See
 * `docs/WORK_PLAY_RELAX.md` § *Descriptive, not prescriptive*.
 */
const CATEGORY_COLOR: Record<Exclude<LifeCategory, 'UNCATEGORIZED'>, string> = {
  WORK: '#2563eb', // blue-600
  PERSONAL: '#a855f7', // purple-500
  SELF_CARE: '#16a34a', // green-600
  HEALTH: '#dc2626', // red-600
};

const CATEGORY_LABEL: Record<LifeCategory, string> = {
  WORK: 'Work',
  PERSONAL: 'Personal',
  SELF_CARE: 'Self-Care',
  HEALTH: 'Health',
  UNCATEGORIZED: 'Uncategorized',
};

// Mode colors are deliberately distinct from the LifeCategory palette so
// the two stacked bars are visually separable even though "Work" appears
// in both dimensions. Mode answers *what the task produces* — see
// docs/WORK_PLAY_RELAX.md for the philosophy.
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

export function TodayBalanceBar() {
  const navigate = useNavigate();
  const tasks = useTaskStore((s) => s.tasks);
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const [prefs, setPrefs] = useState<BalancePreferences>(DEFAULT_BALANCE_PREFERENCES);

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
        // Silently fall back to defaults — the section is non-critical.
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

  const state = useMemo(
    () =>
      tasks.length === 0
        ? EMPTY_BALANCE_STATE
        : computeBalanceState(tasks, config, { dayStartHour: startOfDayHour }),
    [tasks, config, startOfDayHour],
  );

  const modeState = useMemo(
    () =>
      tasks.length === 0
        ? EMPTY_MODE_BALANCE_STATE
        : computeModeBalanceState(tasks, undefined, { dayStartHour: startOfDayHour }),
    [tasks, startOfDayHour],
  );

  if (!prefs.showBalanceBar) return null;

  const hasData = state.totalTracked > 0;
  const hasModeData = modeState.totalTracked > 0;

  return (
    <button
      type="button"
      onClick={() => navigate('/balance/weekly-report')}
      className="mb-4 block w-full rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3 text-left transition-colors hover:border-[var(--color-accent)]/50 hover:bg-[var(--color-bg-secondary)]"
      aria-label="Work-life balance — open weekly report"
    >
      <div className="mb-2 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Scale className="h-4 w-4 text-[var(--color-accent)]" aria-hidden="true" />
          <span className="text-xs font-semibold text-[var(--color-text-primary)]">
            Balance
          </span>
        </div>
        <div className="flex items-center gap-2">
          {state.isOverloaded && (
            <span className="inline-flex items-center gap-1 rounded-full bg-red-500/10 px-2 py-0.5 text-[11px] font-medium text-red-500">
              <AlertTriangle className="h-3 w-3" aria-hidden="true" />
              Work high
            </span>
          )}
          {hasData && !state.isOverloaded && (
            <span className="text-[11px] text-[var(--color-text-secondary)]">
              {CATEGORY_LABEL[state.dominantCategory]}
            </span>
          )}
          <ChevronRight
            className="h-3.5 w-3.5 text-[var(--color-text-secondary)]"
            aria-hidden="true"
          />
        </div>
      </div>

      {hasData ? (
        <>
          <CategoryStackedBar ratios={state.currentRatios} />
          <CategoryLegend ratios={state.currentRatios} />
        </>
      ) : (
        <p className="text-[11px] text-[var(--color-text-secondary)]">
          Add categories to your tasks to see your balance.
        </p>
      )}

      {hasModeData && <ModeRow modeState={modeState} />}
    </button>
  );
}

function CategoryStackedBar({
  ratios,
}: {
  ratios: Record<LifeCategory, number>;
}) {
  return (
    <div
      className="flex h-2 w-full overflow-hidden rounded-full bg-[var(--color-bg-secondary)]"
      role="img"
      aria-label="Life-category distribution over the last 7 days"
    >
      {TRACKED_CATEGORIES.map((cat) => {
        const pct = (ratios[cat] ?? 0) * 100;
        if (pct <= 0) return null;
        return (
          <div
            key={cat}
            style={{
              width: `${pct}%`,
              backgroundColor: CATEGORY_COLOR[cat],
            }}
            title={`${CATEGORY_LABEL[cat]}: ${Math.round(pct)}%`}
          />
        );
      })}
    </div>
  );
}

function CategoryLegend({
  ratios,
}: {
  ratios: Record<LifeCategory, number>;
}) {
  return (
    <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1">
      {TRACKED_CATEGORIES.map((cat) => {
        const pct = Math.round((ratios[cat] ?? 0) * 100);
        return (
          <div
            key={cat}
            className="flex items-center gap-1 text-[10px] text-[var(--color-text-secondary)]"
          >
            <span
              className="h-2 w-2 rounded-full"
              style={{ backgroundColor: CATEGORY_COLOR[cat] }}
              aria-hidden="true"
            />
            {CATEGORY_LABEL[cat]} {pct}%
          </div>
        );
      })}
    </div>
  );
}

/**
 * Mode-balance row stacked beneath the LifeCategory row. Renders only
 * when at least one task is tagged with a mode in the 7-day window —
 * mirrors the LifeCategory gate semantics so users who only tag one
 * dimension never see an empty surface.
 *
 * Copy is strictly factual ("Mode" header + dominant mode label) per
 * `docs/WORK_PLAY_RELAX.md` § *Descriptive, not prescriptive*. The
 * header uses the same shape as the LifeCategory header to make the
 * row read as "the second dimension of the same surface".
 */
function ModeRow({
  modeState,
}: {
  modeState: ReturnType<typeof computeModeBalanceState>;
}) {
  return (
    <div className="mt-3 border-t border-[var(--color-border)] pt-2">
      <div className="mb-1.5 flex items-center justify-between">
        <span className="text-[11px] font-semibold text-[var(--color-text-primary)]">
          Mode
        </span>
        <span className="text-[11px] text-[var(--color-text-secondary)]">
          {MODE_LABEL[modeState.dominantMode]}
        </span>
      </div>
      <ModeStackedBar ratios={modeState.currentRatios} />
      <ModeLegend ratios={modeState.currentRatios} />
    </div>
  );
}

function ModeStackedBar({
  ratios,
}: {
  ratios: Record<TaskMode, number>;
}) {
  return (
    <div
      className="flex h-2 w-full overflow-hidden rounded-full bg-[var(--color-bg-secondary)]"
      role="img"
      aria-label="Task-mode distribution over the last 7 days"
    >
      {TRACKED_MODES.map((mode) => {
        const pct = (ratios[mode] ?? 0) * 100;
        if (pct <= 0) return null;
        return (
          <div
            key={mode}
            style={{
              width: `${pct}%`,
              backgroundColor: MODE_COLOR[mode],
            }}
            title={`${MODE_LABEL[mode]}: ${Math.round(pct)}%`}
          />
        );
      })}
    </div>
  );
}

function ModeLegend({
  ratios,
}: {
  ratios: Record<TaskMode, number>;
}) {
  return (
    <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1">
      {TRACKED_MODES.map((mode) => {
        const pct = Math.round((ratios[mode] ?? 0) * 100);
        return (
          <div
            key={mode}
            className="flex items-center gap-1 text-[10px] text-[var(--color-text-secondary)]"
          >
            <span
              className="h-2 w-2 rounded-full"
              style={{ backgroundColor: MODE_COLOR[mode] }}
              aria-hidden="true"
            />
            {MODE_LABEL[mode]} {pct}%
          </div>
        );
      })}
    </div>
  );
}
