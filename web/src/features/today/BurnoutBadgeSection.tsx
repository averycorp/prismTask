import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertTriangle, Flame, ShieldCheck } from 'lucide-react';
import { subDays, format } from 'date-fns';
import { useTaskStore } from '@/stores/taskStore';
import { useBoundaryRulesStore } from '@/stores/boundaryRulesStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { getLogsInRange, type MoodEnergyLog } from '@/api/firestore/moodEnergyLogs';
import { checkBoundaries } from '@/utils/boundaryEnforcer';
import { scoreBurnout, type BurnoutBucket } from '@/utils/burnoutScorer';

/**
 * Today-screen Burnout Badge. Web port of `TodayBurnoutBadge.kt` (parity
 * unit 7 of 23).
 *
 * Visibility: a small badge surfaces when burnout risk exceeds the
 * `moderate` threshold (score >= 25). Falls silent on `calm`. Tapping
 * navigates to the Boundaries section under Settings so the user can
 * adjust soft caps / work-hours / category limits — this is the same
 * routing Android uses (Boundary Rules screen).
 *
 * Score derivation mirrors `BurnoutScorer.computeFromTasks` at the
 * signal level: overdue tasks + boundary breaches + recent mood/energy
 * logs + active-task overload. Pure-function `scoreBurnout` does the
 * math; this component only assembles inputs and renders.
 */
const BUCKET_STYLE: Record<
  BurnoutBucket,
  { bg: string; border: string; text: string; icon: typeof Flame; label: string }
> = {
  calm: {
    bg: 'bg-emerald-500/5',
    border: 'border-emerald-500/40',
    text: 'text-emerald-500',
    icon: ShieldCheck,
    label: 'Balanced',
  },
  moderate: {
    bg: 'bg-amber-500/5',
    border: 'border-amber-500/40',
    text: 'text-amber-500',
    icon: AlertTriangle,
    label: 'Monitor',
  },
  risky: {
    bg: 'bg-orange-500/5',
    border: 'border-orange-500/40',
    text: 'text-orange-500',
    icon: AlertTriangle,
    label: 'Caution',
  },
  burning: {
    bg: 'bg-red-500/5',
    border: 'border-red-500/40',
    text: 'text-red-500',
    icon: Flame,
    label: 'High Risk',
  },
};

export function BurnoutBadgeSection() {
  const navigate = useNavigate();
  const todayTasks = useTaskStore((s) => s.todayTasks);
  const overdueTasks = useTaskStore((s) => s.overdueTasks);
  const rules = useBoundaryRulesStore((s) => s.rules);
  // Subscribed so cross-device SoD changes recompute the local 7-day window.
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);

  const [moodLogs, setMoodLogs] = useState<MoodEnergyLog[]>([]);

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
        const logs = await getLogsInRange(
          uid,
          format(subDays(new Date(), 7), 'yyyy-MM-dd'),
          format(new Date(), 'yyyy-MM-dd'),
        );
        if (!cancelled) setMoodLogs(logs);
      } catch {
        // Non-fatal — keep prior logs; badge degrades gracefully.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [uid]);

  const result = useMemo(() => {
    const activeTasks = todayTasks.length + overdueTasks.length;
    const breaches = checkBoundaries(rules, {
      active_tasks_today: activeTasks,
      hour_now: new Date().getHours(),
    });
    const softCap =
      rules.find((r) => r.type === 'daily_task_cap' && r.enabled)?.value ?? 10;
    return scoreBurnout({
      breaches,
      recent_mood_logs: moodLogs,
      active_tasks_today: activeTasks,
      task_soft_cap: softCap,
    });
  }, [todayTasks, overdueTasks, rules, moodLogs]);

  // Touch dep so the closure stays stable across SoD swaps.
  void startOfDayHour;

  // Silent below the moderate threshold — matches Android, which only
  // surfaces the badge once the score crosses out of BALANCED.
  if (result.bucket === 'calm') return null;

  const style = BUCKET_STYLE[result.bucket];
  const Icon = style.icon;

  return (
    <div className="mb-4">
      <button
        type="button"
        onClick={() => navigate('/settings')}
        className={`flex w-full items-center gap-3 rounded-xl border ${style.border} ${style.bg} px-4 py-2.5 text-left transition-colors hover:opacity-90`}
        aria-label={`Burnout risk: ${style.label}. Open Boundaries settings.`}
      >
        <Icon className={`h-5 w-5 shrink-0 ${style.text}`} aria-hidden="true" />
        <span className="flex-1">
          <span className={`block text-sm font-semibold ${style.text}`}>
            Burnout Risk: {style.label}
          </span>
          <span className="block text-xs text-[var(--color-text-secondary)]">
            Score {result.score}/100 · Tap to review boundaries
          </span>
        </span>
      </button>
    </div>
  );
}

export default BurnoutBadgeSection;
