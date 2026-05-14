import { useEffect, useMemo, useState } from 'react';
import { Coffee, Flame, Footprints, MoonStar, X } from 'lucide-react';
import { subDays, format } from 'date-fns';
import {
  selectSelfCareNudge,
  type SelfCareNudge,
  type SelfCareNudgeKind,
} from '@/utils/selfCareNudgeEngine';
import {
  computeBalanceState,
  type BalanceConfig,
} from '@/utils/balanceTracker';
import {
  getBalancePreferences,
  subscribeToBalancePreferences,
  type BalancePreferences,
  DEFAULT_BALANCE_PREFERENCES,
} from '@/api/firestore/balancePreferences';
import {
  getLogsInRange,
  type MoodEnergyLog,
} from '@/api/firestore/moodEnergyLogs';
import { getRules, type BoundaryRule } from '@/api/firestore/boundaryRules';
import { checkBoundaries } from '@/utils/boundaryEnforcer';
import { scoreBurnout } from '@/utils/burnoutScorer';
import { useTaskStore } from '@/stores/taskStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { getFirebaseUid } from '@/stores/firebaseUid';

/**
 * Today-screen self-care nudge card. Mirrors Android's
 * `TodaySelfCareNudge.kt` (parity audit C.1e). Surfaces a single
 * rotating nudge when the user's self-care ratio is below target OR
 * their burnout score is elevated. Falls silent otherwise.
 *
 * The card persists the last-shown nudge id in localStorage so the
 * engine rotates kinds across opens of Today.
 */
const STORAGE_KEY = 'prismtask_self_care_nudge_last_id';
const DISMISS_KEY = 'prismtask_self_care_nudge_dismissed_date';

const NUDGE_ICON: Record<SelfCareNudgeKind, typeof Coffee> = {
  rest_break: Coffee,
  burnout_warning: Flame,
  movement: Footprints,
  wind_down: MoonStar,
};

export function SelfCareNudgeCard() {
  const tasks = useTaskStore((s) => s.tasks);
  const todayTasks = useTaskStore((s) => s.todayTasks);
  const overdueTasks = useTaskStore((s) => s.overdueTasks);
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);

  const [prefs, setPrefs] = useState<BalancePreferences>(DEFAULT_BALANCE_PREFERENCES);
  const [moodLogs, setMoodLogs] = useState<MoodEnergyLog[]>([]);
  const [rules, setRules] = useState<BoundaryRule[]>([]);
  const [dismissed, setDismissed] = useState<boolean>(() => {
    try {
      return localStorage.getItem(DISMISS_KEY) === todayKey();
    } catch {
      return false;
    }
  });

  const uid = (() => {
    try {
      return getFirebaseUid();
    } catch {
      return null;
    }
  })();

  useEffect(() => {
    if (!uid) return;
    let cancelled = false;
    (async () => {
      try {
        const [p, m, r] = await Promise.all([
          getBalancePreferences(uid),
          getLogsInRange(
            uid,
            format(subDays(new Date(), 7), 'yyyy-MM-dd'),
            format(new Date(), 'yyyy-MM-dd'),
          ),
          getRules(uid),
        ]);
        if (cancelled) return;
        setPrefs(p);
        setMoodLogs(m);
        setRules(r);
      } catch {
        // Non-fatal — keep defaults; card stays silent.
      }
    })();
    const unsub = subscribeToBalancePreferences(uid, setPrefs);
    return () => {
      cancelled = true;
      unsub();
    };
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

  const nudge: SelfCareNudge | null = useMemo(() => {
    if (dismissed) return null;
    const state = computeBalanceState(tasks, config, { dayStartHour: startOfDayHour });
    const activeTasks = todayTasks.length + overdueTasks.length;
    const breaches = checkBoundaries(rules, {
      active_tasks_today: activeTasks,
      hour_now: new Date().getHours(),
    });
    const softCap =
      rules.find((r) => r.type === 'daily_task_cap' && r.enabled)?.value ?? 10;
    const burnout = scoreBurnout({
      breaches,
      recent_mood_logs: moodLogs,
      active_tasks_today: activeTasks,
      task_soft_cap: softCap,
    });
    const lastShownId = (() => {
      try {
        return localStorage.getItem(STORAGE_KEY);
      } catch {
        return null;
      }
    })();
    return selectSelfCareNudge({
      burnoutScore: burnout.score,
      selfCareRatio: state.currentRatios.SELF_CARE,
      selfCareTarget: prefs.selfCareTarget / 100,
      hourOfDay: new Date().getHours(),
      lastShownId,
    });
  }, [
    tasks,
    config,
    startOfDayHour,
    todayTasks,
    overdueTasks,
    rules,
    moodLogs,
    prefs.selfCareTarget,
    dismissed,
  ]);

  useEffect(() => {
    if (!nudge) return;
    try {
      localStorage.setItem(STORAGE_KEY, nudge.id);
    } catch {
      // Storage may be disabled in incognito; rotation still works in-session.
    }
  }, [nudge]);

  if (!nudge) return null;
  const Icon = NUDGE_ICON[nudge.kind];

  return (
    <div
      className="mb-4 rounded-xl border border-amber-500/40 bg-amber-500/5 p-4"
      role="status"
      aria-label="Self-care nudge"
    >
      <div className="flex items-start gap-3">
        <Icon className="mt-0.5 h-5 w-5 shrink-0 text-amber-500" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">
            Self-care nudge
          </h3>
          <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
            {nudge.message}
          </p>
        </div>
        <button
          onClick={() => {
            try {
              localStorage.setItem(DISMISS_KEY, todayKey());
            } catch {
              // Storage disabled — dismiss is in-memory only.
            }
            setDismissed(true);
          }}
          className="text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
          aria-label="Dismiss nudge for today"
        >
          <X className="h-4 w-4" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}

function todayKey(): string {
  return format(new Date(), 'yyyy-MM-dd');
}
