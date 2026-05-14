import { useMemo } from 'react';
import { TrendingDown, TrendingUp, Activity } from 'lucide-react';
import { format, parseISO } from 'date-fns';
import {
  averageByDay,
  correlateMood,
  correlateEnergy,
  explainCorrelation,
  type CorrelationResult,
  type DailyObservation,
} from '@/utils/moodCorrelation';
import type { MoodEnergyLog } from '@/api/firestore/moodEnergyLogs';
import { useTaskStore } from '@/stores/taskStore';
import { useHabitStore } from '@/stores/habitStore';
import type { Task } from '@/types/task';

/**
 * Pearson-correlation insights between mood/energy and the user's
 * tasks/habits stats. Mirrors Android's `MoodCorrelationEngine` outputs
 * (parity audit C.3). Shows the top-3 strongest correlations per axis
 * once the user has at least 7 mood-logged days.
 */
export function MoodCorrelationsSection({
  logs,
}: {
  logs: MoodEnergyLog[];
}) {
  const tasks = useTaskStore((s) => s.tasks);
  const { habits, completions } = useHabitStore();

  const observations = useMemo<DailyObservation[]>(
    () => buildObservations(logs, tasks, habits, completions),
    [logs, tasks, habits, completions],
  );

  const moodResults = useMemo(() => correlateMood(observations), [observations]);
  const energyResults = useMemo(
    () => correlateEnergy(observations),
    [observations],
  );

  if (observations.length < 7) {
    return (
      <section className="mb-6 rounded-xl border border-dashed border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-[var(--color-text-primary)]">
          <Activity className="h-4 w-4 text-[var(--color-accent)]" />
          Correlations
        </h2>
        <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
          Log mood on at least 7 different days to see how your mood and
          energy correlate with tasks, habits, and self-care.
        </p>
      </section>
    );
  }

  return (
    <section className="mb-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-[var(--color-text-primary)]">
        <Activity className="h-4 w-4 text-[var(--color-accent)]" />
        Correlations
      </h2>
      <div className="grid gap-4 sm:grid-cols-2">
        <CorrelationList title="With mood" results={moodResults.slice(0, 3)} />
        <CorrelationList title="With energy" results={energyResults.slice(0, 3)} />
      </div>
    </section>
  );
}

function CorrelationList({
  title,
  results,
}: {
  title: string;
  results: CorrelationResult[];
}) {
  if (results.length === 0) {
    return (
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
          {title}
        </h3>
        <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
          Not enough variance to compute correlations yet.
        </p>
      </div>
    );
  }
  return (
    <div>
      <h3 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
        {title}
      </h3>
      <ul className="mt-1 flex flex-col gap-1.5">
        {results.map((r) => {
          const Icon = r.coefficient >= 0 ? TrendingUp : TrendingDown;
          const tone =
            r.strength === 'STRONG'
              ? 'text-[var(--color-accent)]'
              : r.strength === 'MODERATE'
              ? 'text-[var(--color-text-primary)]'
              : 'text-[var(--color-text-secondary)]';
          return (
            <li
              key={r.factor}
              className={`flex items-start gap-2 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2.5 ${tone}`}
            >
              <Icon className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              <span className="text-xs">{explainCorrelation(r)}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function buildObservations(
  logs: MoodEnergyLog[],
  tasks: Task[],
  habits: ReturnType<typeof useHabitStore.getState>['habits'],
  completions: ReturnType<typeof useHabitStore.getState>['completions'],
): DailyObservation[] {
  const byDay = averageByDay(logs);
  const out: DailyObservation[] = [];
  for (const [dateIso, avg] of byDay) {
    const stats = computeDailyStats(dateIso, tasks, habits, completions);
    out.push({
      date: parseISO(dateIso).getTime(),
      mood: avg.avgMood,
      energy: avg.avgEnergy,
      tasksCompleted: stats.tasksCompleted,
      workTasksCompleted: stats.workTasksCompleted,
      selfCareTasksCompleted: stats.selfCareTasksCompleted,
      habitCompletionRate: stats.habitCompletionRate,
    });
  }
  return out;
}

function computeDailyStats(
  dateIso: string,
  tasks: Task[],
  habits: ReturnType<typeof useHabitStore.getState>['habits'],
  completions: ReturnType<typeof useHabitStore.getState>['completions'],
): {
  tasksCompleted: number;
  workTasksCompleted: number;
  selfCareTasksCompleted: number;
  habitCompletionRate: number;
} {
  let total = 0;
  let work = 0;
  let selfCare = 0;
  for (const t of tasks) {
    if (!t.completed_at) continue;
    if (format(parseISO(t.completed_at), 'yyyy-MM-dd') !== dateIso) continue;
    total++;
    if (t.life_category === 'WORK') work++;
    if (t.life_category === 'SELF_CARE') selfCare++;
  }
  const activeHabits = habits.filter((h) => h.is_active);
  let habitDone = 0;
  for (const h of activeHabits) {
    const list = completions[h.id] ?? [];
    const dayCount = list
      .filter((c) => c.date === dateIso)
      .reduce((sum, c) => sum + c.count, 0);
    if (dayCount >= h.target_count) habitDone++;
  }
  const habitRate = activeHabits.length === 0 ? 0 : habitDone / activeHabits.length;
  return {
    tasksCompleted: total,
    workTasksCompleted: work,
    selfCareTasksCompleted: selfCare,
    habitCompletionRate: habitRate,
  };
}
