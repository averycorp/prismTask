import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Flame, ShieldAlert, ShieldCheck } from 'lucide-react';
import { subDays, format } from 'date-fns';
import { useTaskStore } from '@/stores/taskStore';
import { useBoundaryRulesStore } from '@/stores/boundaryRulesStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import {
  getLogsInRange,
  type MoodEnergyLog,
} from '@/api/firestore/moodEnergyLogs';
import { checkBoundaries, type Breach } from '@/utils/boundaryEnforcer';
import { scoreBurnout, type BurnoutScore } from '@/utils/burnoutScorer';

/**
 * Lightweight Today-screen banner combining slice-21 outputs —
 * boundary breaches + the derived burnout score. Silent when calm
 * with no breaches so it doesn't nag the user when things are fine.
 */
export function BoundaryTodayBanner() {
  const todayTasks = useTaskStore((s) => s.todayTasks);
  const overdueTasks = useTaskStore((s) => s.overdueTasks);
  // Live cache populated by useFirestoreSync — cross-device rule
  // edits surface here without a page refresh (parity audit § A.1b).
  const rules = useBoundaryRulesStore((s) => s.rules);

  const [moodLogs, setMoodLogs] = useState<MoodEnergyLog[]>([]);

  useEffect(() => {
    (async () => {
      try {
        const uid = getFirebaseUid();
        const m = await getLogsInRange(
          uid,
          format(subDays(new Date(), 7), 'yyyy-MM-dd'),
          format(new Date(), 'yyyy-MM-dd'),
        );
        setMoodLogs(m);
      } catch {
        // No auth / no logs — banner falls back to a no-mood scorer.
      }
    })();
  }, []);

  if (rules.length === 0) return null;

  const activeTasks = todayTasks.length + overdueTasks.length;
  const breaches: Breach[] = checkBoundaries(rules, {
    active_tasks_today: activeTasks,
    hour_now: new Date().getHours(),
  });
  const softCap =
    rules.find((r) => r.type === 'daily_task_cap' && r.enabled)?.value ?? 10;
  const burnout: BurnoutScore = scoreBurnout({
    breaches,
    recent_mood_logs: moodLogs,
    active_tasks_today: activeTasks,
    task_soft_cap: softCap,
  });

  // Quiet state — no breaches AND low burnout.
  if (breaches.length === 0 && burnout.bucket === 'calm') return null;

  const bucketMeta: Record<
    BurnoutScore['bucket'],
    { label: string; className: string; Icon: typeof Flame }
  > = {
    calm: {
      label: 'Calm',
      className: 'text-emerald-500 border-emerald-500/40 bg-emerald-500/5',
      Icon: ShieldCheck,
    },
    moderate: {
      label: 'Moderate load',
      className: 'text-blue-500 border-blue-500/40 bg-blue-500/5',
      Icon: ShieldCheck,
    },
    risky: {
      label: 'Risky',
      className: 'text-amber-500 border-amber-500/40 bg-amber-500/5',
      Icon: ShieldAlert,
    },
    burning: {
      label: 'Burning hot',
      className: 'text-red-500 border-red-500/40 bg-red-500/5',
      Icon: Flame,
    },
  };
  const meta = bucketMeta[burnout.bucket];

  return (
    <div className={`mb-4 rounded-xl border p-4 ${meta.className}`} role="status">
      <div className="flex items-start gap-3">
        <meta.Icon className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h3 className="text-sm font-semibold">
              {meta.label} · burnout {burnout.score}/100
            </h3>
          </div>
          {breaches.length > 0 && (
            <ul className="mt-1 flex flex-col gap-0.5 text-xs">
              {breaches.map((b) => (
                <li key={b.rule_id}>• {b.message}</li>
              ))}
            </ul>
          )}
          {breaches.length === 0 && (
            <p className="mt-0.5 text-xs">
              Signals building up — consider reviewing tasks or taking a
              short break.
            </p>
          )}
          <Link
            to="/boundaries"
            className="mt-2 inline-block text-xs font-medium underline-offset-2 hover:underline"
            data-testid="boundary-today-banner-manage-link"
          >
            Review Boundary Rules
          </Link>
        </div>
      </div>
    </div>
  );
}
