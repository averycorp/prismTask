import { useEffect, useMemo, useState } from 'react';
import { Sparkles, Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import { Checkbox } from '@/components/ui/Checkbox';
import { EmptyState } from '@/components/ui/EmptyState';
import { useSelfCareStore } from '@/stores/selfCareStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { startOfLogicalDayMs } from '@/utils/dayBoundary';
import { parseCompletedStepsForDisplay } from '@/api/firestore/selfCare';
import type { SelfCareStep } from '@/api/firestore/selfCare';

type RoutineTab = {
  key: string;
  label: string;
};

const ROUTINE_TABS: RoutineTab[] = [
  { key: 'morning', label: 'Self-Care' },
  { key: 'bedtime', label: 'Bedtime' },
  { key: 'medication', label: 'Medication' },
  { key: 'housework', label: 'Housework' },
];

export function SelfCareScreen() {
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const logs = useSelfCareStore((s) => s.logs);
  const steps = useSelfCareStore((s) => s.steps);
  const toggleStep = useSelfCareStore((s) => s.toggleStep);
  const error = useSelfCareStore((s) => s.error);

  const [activeTab, setActiveTab] = useState<string>('morning');
  const [pending, setPending] = useState<Set<string>>(new Set());

  const todayMs = useMemo(
    // eslint-disable-next-line react-hooks/purity -- parity batch follow-up; see #1573
    () => startOfLogicalDayMs(Date.now(), startOfDayHour),
    [startOfDayHour],
  );

  useEffect(() => {
    if (error) {
      toast.error(error);
    }
  }, [error]);

  const todayLog = useMemo(
    () =>
      logs.find((l) => l.routine_type === activeTab && l.date === todayMs) ??
      null,
    [logs, activeTab, todayMs],
  );

  const stepsForRoutine = useMemo(
    () =>
      steps
        .filter((s) => s.routine_type === activeTab)
        .sort((a, b) => a.sort_order - b.sort_order),
    [steps, activeTab],
  );

  const completedIds = useMemo(
    () =>
      new Set(
        todayLog ? parseCompletedStepsForDisplay(todayLog.completed_steps) : [],
      ),
    [todayLog],
  );

  const phasedSteps = useMemo(
    () => groupByPhase(stepsForRoutine, activeTab),
    [stepsForRoutine, activeTab],
  );

  const handleToggle = async (stepId: string) => {
    if (activeTab === 'medication') {
      // Medication routines use a richer per-block log shape
      // (`MedStepLog` with timeOfDay) that web doesn't model yet.
      // Read-only on this surface; toggling lives on Android.
      toast.info('Medication check-off lives on the phone for now.');
      return;
    }
    setPending((prev) => new Set(prev).add(stepId));
    try {
      await toggleStep(activeTab, todayMs, stepId);
    } finally {
      setPending((prev) => {
        const next = new Set(prev);
        next.delete(stepId);
        return next;
      });
    }
  };

  const total = stepsForRoutine.length;
  const done = stepsForRoutine.filter((s) => completedIds.has(s.step_id))
    .length;

  return (
    <div className="mx-auto max-w-2xl px-4 py-6">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          Self-Care
        </h1>
        <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
          Today's Routines, Synced From Your Phone.
        </p>
      </div>

      <div
        className="mb-4 flex flex-wrap gap-1 rounded-lg bg-[var(--color-bg-secondary)] p-1"
        role="tablist"
        aria-label="Self-care routines"
      >
        {ROUTINE_TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={activeTab === tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`flex-1 rounded-md px-3 py-2 text-sm font-medium transition-colors ${
              activeTab === tab.key
                ? 'bg-[var(--color-bg-primary)] text-[var(--color-text-primary)] shadow-sm'
                : 'text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {total > 0 && (
        <div className="mb-4 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-3 text-sm">
          <div className="flex items-center justify-between">
            <span className="text-[var(--color-text-secondary)]">Progress</span>
            <span className="font-semibold text-[var(--color-text-primary)]">
              {done} / {total}
            </span>
          </div>
          <div className="mt-2 h-2 overflow-hidden rounded-full bg-[var(--color-bg-primary)]">
            <div
              className="h-full bg-[var(--color-accent)] transition-all"
              style={{
                width: total === 0 ? '0%' : `${(done / total) * 100}%`,
              }}
            />
          </div>
        </div>
      )}

      {stepsForRoutine.length === 0 ? (
        <EmptyState
          icon={<Sparkles className="h-7 w-7" />}
          title="No Steps Yet"
          description="Open the Self-Care screen on your phone to seed default routines. They'll appear here once they sync."
        />
      ) : (
        <div className="space-y-6">
          {phasedSteps.map(({ phase, items }) => (
            <section key={phase}>
              <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                {phase}
              </h2>
              <ul className="space-y-2" role="list">
                {items.map((step) => {
                  const checked = completedIds.has(step.step_id);
                  const isPending = pending.has(step.step_id);
                  return (
                    <li
                      key={step.id}
                      className="flex items-center gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-3"
                    >
                      <Checkbox
                        checked={checked}
                        disabled={isPending}
                        onChange={() => handleToggle(step.step_id)}
                      />
                      <div className="min-w-0 flex-1">
                        <div className="text-sm font-medium text-[var(--color-text-primary)]">
                          {step.label}
                        </div>
                        {(step.duration || step.note) && (
                          <div className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
                            {step.duration}
                            {step.duration && step.note ? ' · ' : ''}
                            {step.note}
                          </div>
                        )}
                      </div>
                      {isPending && (
                        <Loader2
                          className="h-4 w-4 shrink-0 animate-spin text-[var(--color-text-secondary)]"
                          aria-label="Saving"
                        />
                      )}
                    </li>
                  );
                })}
              </ul>
            </section>
          ))}
        </div>
      )}
    </div>
  );
}

function groupByPhase(
  items: SelfCareStep[],
  routineType: string,
): { phase: string; items: SelfCareStep[] }[] {
  if (routineType === 'medication') {
    return items.length === 0 ? [] : [{ phase: 'Medications', items }];
  }
  const phaseOrder =
    routineType === 'morning'
      ? ['Self-Care', 'Skincare', 'Hygiene', 'Grooming']
      : routineType === 'housework'
        ? ['Kitchen', 'Living Areas', 'Bathroom', 'Laundry']
        : ['Wash', 'Skincare', 'Hygiene', 'Sleep'];

  const grouped = new Map<string, SelfCareStep[]>();
  for (const s of items) {
    const list = grouped.get(s.phase) ?? [];
    list.push(s);
    grouped.set(s.phase, list);
  }

  const result: { phase: string; items: SelfCareStep[] }[] = [];
  for (const phase of phaseOrder) {
    const list = grouped.get(phase);
    if (list && list.length > 0) {
      result.push({ phase, items: list });
      grouped.delete(phase);
    }
  }
  for (const [phase, list] of grouped.entries()) {
    result.push({ phase, items: list });
  }
  return result;
}
