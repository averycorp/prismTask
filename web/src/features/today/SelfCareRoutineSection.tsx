import { useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Sun, Moon } from 'lucide-react';
import { useSelfCareStore } from '@/stores/selfCareStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { logicalToday } from '@/utils/dayBoundary';

/**
 * Today-screen self-care *routine* prompt. Web port of the
 * morning/bedtime nudge surface on Android (parity unit 7 of 23).
 *
 * Distinct from `SelfCareNudgeCard.tsx`, which surfaces a *burnout-
 * driven* rotating nudge (rest break / movement / wind-down). This
 * component reads `selfCareStore` and surfaces the user's actual
 * self-care routine — the morning routine in the morning, the bedtime
 * routine in the evening — so a tap routes straight into the step
 * checklist on `/self-care`.
 *
 * Time-of-day heuristic, Start-of-Day-aware:
 *   - Hours `[SoD, SoD + 6)` → "morning" prompt.
 *   - Hours `[18, 24)` ∪ `[0, SoD)` → "bedtime" prompt.
 *   - Anything else → silent (the user has the screen for full review).
 *
 * Falls silent when:
 *   - The user hasn't seeded any steps for the relevant routine.
 *   - Today's log is already fully completed (all steps checked).
 */
export function SelfCareRoutineSection() {
  const navigate = useNavigate();
  const logs = useSelfCareStore((s) => s.logs);
  const steps = useSelfCareStore((s) => s.steps);
  const subscribeToLogs = useSelfCareStore((s) => s.subscribeToLogs);
  const subscribeToSteps = useSelfCareStore((s) => s.subscribeToSteps);
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);

  const uid = (() => {
    try {
      return getFirebaseUid();
    } catch {
      return null;
    }
  })();

  useEffect(() => {
    if (!uid) return;
    const unsubLogs = subscribeToLogs(uid);
    const unsubSteps = subscribeToSteps(uid);
    return () => {
      unsubLogs();
      unsubSteps();
    };
  }, [uid, subscribeToLogs, subscribeToSteps]);

  const promptRoutine = useMemo<'morning' | 'bedtime' | null>(() => {
    const hour = new Date().getHours();
    // Morning window: SoD to SoD+6 (wraps modulo 24 to support late
    // SoDs like 20:00).
    const sodEnd = (startOfDayHour + 6) % 24;
    const inMorning =
      sodEnd > startOfDayHour
        ? hour >= startOfDayHour && hour < sodEnd
        : hour >= startOfDayHour || hour < sodEnd;
    if (inMorning) return 'morning';
    // Bedtime window: 18:00 to SoD (next day).
    const inBedtime =
      startOfDayHour > 18
        ? hour >= 18 && hour < startOfDayHour
        : hour >= 18 || hour < startOfDayHour;
    if (inBedtime) return 'bedtime';
    return null;
  }, [startOfDayHour]);

  const todayMs = useMemo(() => {
    // Logical-day epoch-ms, aligned to user's Start-of-Day hour. Matches
    // the natural-key shape `selfCareStore` writes in `toggleStep`.
    // eslint-disable-next-line react-hooks/purity -- Date.now is the canonical SoD-now anchor; parity batch follow-up
    const iso = logicalToday(Date.now(), startOfDayHour);
    return new Date(iso + 'T00:00:00').getTime();
  }, [startOfDayHour]);

  if (!promptRoutine) return null;

  const routineSteps = steps.filter((s) => s.routine_type === promptRoutine);
  if (routineSteps.length === 0) return null;

  const todayLog = logs.find(
    (l) => l.routine_type === promptRoutine && l.date === todayMs,
  );
  const completedStepIds = new Set<string>(
    todayLog ? extractCompletedIds(todayLog.completed_steps) : [],
  );
  const totalSteps = routineSteps.length;
  // Android stores `step_id` (natural id) in the completed list, not the
  // Firestore doc id. Match on `step_id` so logs round-trip across
  // devices without phantom-incomplete counts.
  const completedCount = routineSteps.filter((s) =>
    completedStepIds.has(s.step_id),
  ).length;

  // Already done — drop out instead of showing a 100% prompt.
  if (completedCount >= totalSteps) return null;

  const Icon = promptRoutine === 'morning' ? Sun : Moon;
  const label =
    promptRoutine === 'morning' ? 'Morning Routine' : 'Bedtime Routine';

  return (
    <button
      type="button"
      onClick={() => navigate('/self-care')}
      className="mb-4 flex w-full items-center gap-3 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] px-4 py-3 text-left transition-colors hover:border-[var(--color-accent)]/50"
      aria-label={`${label}: ${completedCount} of ${totalSteps} done. Open self-care screen.`}
    >
      <Icon
        className="h-5 w-5 shrink-0 text-[var(--color-accent)]"
        aria-hidden="true"
      />
      <span className="flex-1">
        <span className="block text-sm font-semibold text-[var(--color-text-primary)]">
          {label}
        </span>
        <span className="block text-xs text-[var(--color-text-secondary)]">
          {completedCount} of {totalSteps} steps done · Tap to check off
        </span>
      </span>
    </button>
  );
}

function extractCompletedIds(raw: string | null | undefined): string[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed.filter((x): x is string => typeof x === 'string');
    }
    return [];
  } catch {
    return [];
  }
}

export default SelfCareRoutineSection;
