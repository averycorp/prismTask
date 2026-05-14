import { useEffect, useMemo, useState } from 'react';
import { Scale, AlertTriangle } from 'lucide-react';
import {
  computeBalanceState,
  type BalanceConfig,
  TRACKED_CATEGORIES,
  EMPTY_BALANCE_STATE,
} from '@/utils/balanceTracker';
import {
  getBalancePreferences,
  subscribeToBalancePreferences,
  type BalancePreferences,
  DEFAULT_BALANCE_PREFERENCES,
} from '@/api/firestore/balancePreferences';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { useTaskStore } from '@/stores/taskStore';
import { useSettingsStore } from '@/stores/settingsStore';
import type { LifeCategory } from '@/types/task';

/**
 * Today-screen Work-Life Balance bar. Mirrors Android's
 * `TodayBalanceBar.kt` (parity audit C.1a). Renders the four tracked
 * life categories as a horizontal stacked bar over the last 7 days of
 * categorized tasks. Hides when the user has disabled `wlb_show_bar`.
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

export function TodayBalanceBar() {
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

  if (!prefs.showBalanceBar) return null;

  const hasData = state.totalTracked > 0;

  return (
    <div
      className="mb-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3"
      aria-label="Work-life balance"
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
        </div>
      </div>

      {hasData ? (
        <>
          <StackedBar ratios={state.currentRatios} />
          <Legend ratios={state.currentRatios} />
        </>
      ) : (
        <p className="text-[11px] text-[var(--color-text-secondary)]">
          Add categories to your tasks to see your balance.
        </p>
      )}
    </div>
  );
}

function StackedBar({
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

function Legend({
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
