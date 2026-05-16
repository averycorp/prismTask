import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ArrowLeft,
  ArrowRight,
  Calendar,
  Check,
  Heart,
  Loader2,
  Scale,
  Sparkles,
} from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { setCheckIn, type CheckInLog } from '@/api/firestore/checkInLogs';
import { createLog as createMoodEnergyLog } from '@/api/firestore/moodEnergyLogs';
import {
  DEFAULT_BALANCE_PREFERENCES,
  getBalancePreferences,
  type BalancePreferences,
} from '@/api/firestore/balancePreferences';
import {
  TRACKED_CATEGORIES,
  computeBalanceState,
  type BalanceConfig,
} from '@/utils/balanceTracker';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { useSettingsStore } from '@/stores/settingsStore';
import { useTaskStore } from '@/stores/taskStore';
import type { LifeCategory } from '@/types/task';
import {
  LIFE_CATEGORY_COLOR,
  LIFE_CATEGORY_LABEL,
} from '@/theme/lifeCategoryColors';

/**
 * Morning Check-In guided stepper (parity C.5a).
 *
 * Mirrors Android `MorningCheckInScreen.kt`'s HorizontalPager flow with
 * the three core step types from `MorningCheckInResolver.CheckInStep`:
 *   1. MOOD_ENERGY — mood + energy sliders (1..5 to match Android
 *      `MoodEnergyLogEntity`; the audit spec mentioned 0..10 but the
 *      shared cross-device schema is 1..5, so we keep parity over the
 *      audit's looser bound) plus optional notes.
 *   2. BALANCE — today's intended balance category targets, read from
 *      `BalancePreferences`, alongside the user's actual 7-day ratios.
 *   3. CALENDAR — read-only "morning preview" hint. Web has no Google
 *      Calendar integration yet (parity item D.x); the step renders a
 *      graceful empty state pointing users at the mobile-side toggle.
 *
 * Final "Submit" writes the check-in row via `setCheckIn(...)`. Partial
 * state (mood, energy, notes, step index) is persisted to localStorage
 * keyed per logical-day, so reload mid-flow preserves progress.
 *
 * Spec reference: docs/audits/ANDROID_WEB_PARITY_AUDIT_2026-05-13.md
 * § C.5a, batch 7.
 */

export type CheckInStepKind = 'MOOD_ENERGY' | 'BALANCE' | 'CALENDAR';

const STEP_ORDER: CheckInStepKind[] = ['MOOD_ENERGY', 'BALANCE', 'CALENDAR'];

interface DraftState {
  step: number;
  mood: number;
  energy: number;
  notes: string;
  visited: CheckInStepKind[];
}

const DEFAULT_DRAFT: DraftState = {
  step: 0,
  mood: 3,
  energy: 3,
  notes: '',
  visited: [],
};

function draftKey(dateIso: string): string {
  return `checkin:draft:${dateIso}`;
}

function loadDraft(dateIso: string): DraftState {
  try {
    const raw = localStorage.getItem(draftKey(dateIso));
    if (!raw) return DEFAULT_DRAFT;
    const parsed = JSON.parse(raw) as Partial<DraftState>;
    return {
      step: clampStep(parsed.step),
      mood: clampScale(parsed.mood),
      energy: clampScale(parsed.energy),
      notes: typeof parsed.notes === 'string' ? parsed.notes : '',
      visited: Array.isArray(parsed.visited)
        ? parsed.visited.filter(
            (s): s is CheckInStepKind =>
              s === 'MOOD_ENERGY' || s === 'BALANCE' || s === 'CALENDAR',
          )
        : [],
    };
  } catch {
    // Corrupt JSON or storage-disabled context — start fresh.
    return DEFAULT_DRAFT;
  }
}

function saveDraft(dateIso: string, draft: DraftState): void {
  try {
    localStorage.setItem(draftKey(dateIso), JSON.stringify(draft));
  } catch {
    // Storage quotas / private-mode — ignore, the draft is a UX nicety
    // not load-bearing.
  }
}

function clearDraft(dateIso: string): void {
  try {
    localStorage.removeItem(draftKey(dateIso));
  } catch {
    // No-op when storage is unavailable.
  }
}

function clampStep(n: unknown): number {
  if (typeof n !== 'number' || !Number.isFinite(n)) return 0;
  return Math.max(0, Math.min(STEP_ORDER.length - 1, Math.round(n)));
}

function clampScale(n: unknown): number {
  if (typeof n !== 'number' || !Number.isFinite(n)) return 3;
  return Math.max(1, Math.min(5, Math.round(n)));
}

// LifeCategory palette is the single source of truth shared with
// TodayBalanceBar and the Weekly Balance Report — see
// `@/theme/lifeCategoryColors`. These tokens mirror Android's
// `LifeCategoryColor.kt` exactly.
const CATEGORY_LABEL: Record<LifeCategory, string> = LIFE_CATEGORY_LABEL;
const CATEGORY_COLOR: Record<LifeCategory, string> = LIFE_CATEGORY_COLOR;

interface MorningCheckInStepperProps {
  dateIso: string;
  initial: CheckInLog | null;
  onSaved: () => void;
  onClose: () => void;
}

export function MorningCheckInStepper({
  dateIso,
  initial,
  onSaved,
  onClose,
}: MorningCheckInStepperProps) {
  const [draft, setDraft] = useState<DraftState>(() => loadDraft(dateIso));

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- form-init: reload localStorage draft when the active logical day changes
    setDraft(loadDraft(dateIso));
  }, [dateIso]);

  useEffect(() => {
    saveDraft(dateIso, draft);
  }, [dateIso, draft]);

  const currentStep = STEP_ORDER[draft.step];
  const totalSteps = STEP_ORDER.length;
  const [submitting, setSubmitting] = useState(false);

  const updateDraft = useCallback((patch: Partial<DraftState>) => {
    setDraft((d) => ({ ...d, ...patch }));
  }, []);

  const markVisited = useCallback((step: CheckInStepKind) => {
    setDraft((d) =>
      d.visited.includes(step) ? d : { ...d, visited: [...d.visited, step] },
    );
  }, []);

  const goNext = useCallback(() => {
    markVisited(currentStep);
    setDraft((d) => ({ ...d, step: Math.min(totalSteps - 1, d.step + 1) }));
  }, [currentStep, markVisited, totalSteps]);

  const goBack = useCallback(() => {
    setDraft((d) => ({ ...d, step: Math.max(0, d.step - 1) }));
  }, []);

  const handleSubmit = useCallback(async () => {
    setSubmitting(true);
    try {
      const uid = getFirebaseUid();
      const visited = [...draft.visited];
      if (!visited.includes(currentStep)) visited.push(currentStep);
      const stepsCsv = visited.join(',');

      // Persist mood/energy in its own log table — mirrors Android,
      // which writes a `MoodEnergyLogEntity` from this step. Wrapped
      // in try/catch so a mood-log failure doesn't block the
      // check-in record itself.
      if (visited.includes('MOOD_ENERGY')) {
        try {
          await createMoodEnergyLog(uid, {
            date_iso: dateIso,
            mood: draft.mood,
            energy: draft.energy,
            notes: draft.notes.trim(),
            time_of_day: 'morning',
          });
        } catch {
          // Non-fatal — surface as a soft toast below.
          toast.error('Mood entry could not be saved, but your check-in went through.');
        }
      }

      await setCheckIn(uid, {
        date_iso: dateIso,
        steps_completed_csv: stepsCsv,
        // Pre-V4 fields the rest of the app still reads. Marking all
        // three as "true" on submission mirrors Android's behaviour:
        // once you walk the guided flow, the Today banner stops
        // prompting and the streak counts the day.
        medications_confirmed: true,
        tasks_reviewed: true,
        habits_completed: true,
      });

      clearDraft(dateIso);
      toast.success(initial ? 'Check-in updated' : 'Checked in');
      onSaved();
      onClose();
    } catch (e) {
      toast.error((e as Error).message || 'Check-in failed');
    } finally {
      setSubmitting(false);
    }
  }, [currentStep, dateIso, draft, initial, onClose, onSaved]);

  return (
    <div className="flex flex-col gap-4">
      <StepProgress current={draft.step} total={totalSteps} />
      <div className="min-h-[280px]">
        {currentStep === 'MOOD_ENERGY' && (
          <MoodEnergyStep
            mood={draft.mood}
            energy={draft.energy}
            notes={draft.notes}
            onChange={(patch) => updateDraft(patch)}
          />
        )}
        {currentStep === 'BALANCE' && <BalanceStep />}
        {currentStep === 'CALENDAR' && <CalendarStep />}
      </div>
      <div className="flex items-center justify-between gap-2 border-t border-[var(--color-border)] pt-3">
        <Button
          variant="ghost"
          onClick={draft.step === 0 ? onClose : goBack}
          disabled={submitting}
        >
          <ArrowLeft className="mr-1 h-4 w-4" />
          {draft.step === 0 ? 'Cancel' : 'Back'}
        </Button>
        {draft.step < totalSteps - 1 ? (
          <Button onClick={goNext} disabled={submitting}>
            Next
            <ArrowRight className="ml-1 h-4 w-4" />
          </Button>
        ) : (
          <Button onClick={handleSubmit} disabled={submitting}>
            {submitting ? (
              <Loader2 className="mr-1 h-4 w-4 animate-spin" />
            ) : (
              <Check className="mr-1 h-4 w-4" />
            )}
            {initial ? 'Update' : 'Submit'}
          </Button>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*                          Step components                            */
/* ------------------------------------------------------------------ */

function StepProgress({ current, total }: { current: number; total: number }) {
  return (
    <div className="flex items-center gap-2">
      {Array.from({ length: total }, (_, i) => (
        <div
          key={i}
          className={`h-1.5 flex-1 rounded-full ${
            i <= current
              ? 'bg-[var(--color-accent)]'
              : 'bg-[var(--color-border)]'
          }`}
          aria-hidden="true"
        />
      ))}
      <span className="ml-2 shrink-0 text-[10px] uppercase tracking-wide text-[var(--color-text-secondary)]">
        {current + 1} / {total}
      </span>
    </div>
  );
}

interface MoodEnergyStepProps {
  mood: number;
  energy: number;
  notes: string;
  onChange: (patch: Partial<DraftState>) => void;
}

function MoodEnergyStep({ mood, energy, notes, onChange }: MoodEnergyStepProps) {
  return (
    <div className="flex flex-col gap-4 text-sm">
      <div className="flex items-start gap-2">
        <Heart className="mt-0.5 h-4 w-4 shrink-0 text-pink-500" aria-hidden="true" />
        <div>
          <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">
            How are you feeling?
          </h4>
          <p className="text-xs text-[var(--color-text-secondary)]">
            Two quick scales — no wrong answer.
          </p>
        </div>
      </div>

      <ScaleRow
        label="Mood"
        value={mood}
        emojis={['😢', '😕', '😐', '🙂', '😊']}
        onChange={(v) => onChange({ mood: v })}
      />
      <ScaleRow
        label="Energy"
        value={energy}
        emojis={['🪫', '🔋', '🔋', '🔋', '⚡']}
        onChange={(v) => onChange({ energy: v })}
      />

      <label className="flex flex-col gap-1">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
          Notes
        </span>
        <textarea
          value={notes}
          onChange={(e) => onChange({ notes: e.target.value })}
          rows={2}
          placeholder="Anything affecting your day?"
          className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        />
      </label>
    </div>
  );
}

interface ScaleRowProps {
  label: string;
  value: number;
  emojis: string[];
  onChange: (v: number) => void;
}

function ScaleRow({ label, value, emojis, onChange }: ScaleRowProps) {
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-[var(--color-text-primary)]">
          {label}
        </span>
        <span className="text-xs text-[var(--color-text-secondary)]">
          {value} / {emojis.length}
        </span>
      </div>
      <div className="flex items-center gap-1.5">
        {emojis.map((emoji, idx) => {
          const score = idx + 1;
          const selected = score === value;
          return (
            <button
              key={idx}
              type="button"
              onClick={() => onChange(score)}
              aria-label={`${label} ${score}`}
              aria-pressed={selected}
              className={`flex-1 rounded-md border px-2 py-1.5 text-lg transition-colors ${
                selected
                  ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10'
                  : 'border-[var(--color-border)] bg-[var(--color-bg-secondary)] hover:border-[var(--color-accent)]/50'
              }`}
            >
              {emoji}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function BalanceStep() {
  const tasks = useTaskStore((s) => s.tasks);
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const [prefs, setPrefs] = useState<BalancePreferences>(DEFAULT_BALANCE_PREFERENCES);

  useEffect(() => {
    let cancelled = false;
    try {
      const uid = getFirebaseUid();
      getBalancePreferences(uid)
        .then((p) => {
          if (!cancelled) setPrefs(p);
        })
        .catch(() => {
          // Defaults render fine when offline / signed-out.
        });
    } catch {
      // Not signed in — keep defaults.
    }
    return () => {
      cancelled = true;
    };
  }, []);

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
    () => computeBalanceState(tasks, config, { dayStartHour: startOfDayHour }),
    [tasks, config, startOfDayHour],
  );

  return (
    <div className="flex flex-col gap-3 text-sm">
      <div className="flex items-start gap-2">
        <Scale className="mt-0.5 h-4 w-4 shrink-0 text-[var(--color-accent)]" aria-hidden="true" />
        <div>
          <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">
            Today's intended balance
          </h4>
          <p className="text-xs text-[var(--color-text-secondary)]">
            Your targets versus the last seven days.
          </p>
        </div>
      </div>

      <BalanceBars label="Target" ratios={config} />
      <BalanceBars
        label="Last 7 days"
        ratios={{
          workTarget: state.currentRatios.WORK ?? 0,
          personalTarget: state.currentRatios.PERSONAL ?? 0,
          selfCareTarget: state.currentRatios.SELF_CARE ?? 0,
          healthTarget: state.currentRatios.HEALTH ?? 0,
        }}
      />

      {state.isOverloaded && (
        <p className="rounded-md border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-600 dark:text-amber-400">
          Work is exceeding your overload threshold. Consider pulling a self-care
          or personal task into today.
        </p>
      )}
      {state.totalTracked === 0 && (
        <p className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-xs text-[var(--color-text-secondary)]">
          Tag tasks with a life category to see your balance ratios here.
        </p>
      )}
    </div>
  );
}

interface BalanceBarsProps {
  label: string;
  ratios: {
    workTarget: number;
    personalTarget: number;
    selfCareTarget: number;
    healthTarget: number;
  };
}

function BalanceBars({ label, ratios }: BalanceBarsProps) {
  const ordered = useMemo(() => {
    const lookup: Record<Exclude<LifeCategory, 'UNCATEGORIZED'>, number> = {
      WORK: ratios.workTarget,
      PERSONAL: ratios.personalTarget,
      SELF_CARE: ratios.selfCareTarget,
      HEALTH: ratios.healthTarget,
    };
    return TRACKED_CATEGORIES.map((cat) => ({ cat, value: lookup[cat] }));
  }, [ratios]);

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
          {label}
        </span>
      </div>
      <div className="flex h-4 overflow-hidden rounded-md bg-[var(--color-bg-secondary)]">
        {ordered.map(({ cat, value }) => (
          <div
            key={cat}
            style={{
              width: `${Math.max(0, Math.min(1, value)) * 100}%`,
              backgroundColor: CATEGORY_COLOR[cat],
            }}
            aria-label={`${CATEGORY_LABEL[cat]} ${Math.round(value * 100)} percent`}
            title={`${CATEGORY_LABEL[cat]} ${Math.round(value * 100)}%`}
          />
        ))}
      </div>
      <div className="flex flex-wrap gap-2 text-[11px] text-[var(--color-text-secondary)]">
        {ordered.map(({ cat, value }) => (
          <span key={cat} className="inline-flex items-center gap-1">
            <span
              className="inline-block h-2 w-2 rounded-full"
              style={{ backgroundColor: CATEGORY_COLOR[cat] }}
              aria-hidden="true"
            />
            {CATEGORY_LABEL[cat]} {Math.round(value * 100)}%
          </span>
        ))}
      </div>
    </div>
  );
}

function CalendarStep() {
  // Web has no Google Calendar integration yet (parity audit D.x).
  // Mirror Android's "Connect Calendar in Settings" empty state.
  return (
    <div className="flex flex-col gap-3 text-sm">
      <div className="flex items-start gap-2">
        <Calendar
          className="mt-0.5 h-4 w-4 shrink-0 text-[var(--color-accent)]"
          aria-hidden="true"
        />
        <div>
          <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">
            Calendar glance
          </h4>
          <p className="text-xs text-[var(--color-text-secondary)]">
            Your morning events at a glance.
          </p>
        </div>
      </div>
      <div className="rounded-md border border-dashed border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-6 text-center">
        <Sparkles
          className="mx-auto mb-2 h-6 w-6 text-[var(--color-text-secondary)]"
          aria-hidden="true"
        />
        <p className="text-sm font-medium text-[var(--color-text-primary)]">
          Calendar preview coming to web
        </p>
        <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
          Two-way Google Calendar sync ships in PrismTask on Android. Web will
          surface the same morning preview once the integration is wired up.
        </p>
      </div>
      <p className="text-[11px] text-[var(--color-text-secondary)]">
        For now, treat this step as a moment to glance at your own calendar app
        before you dive in.
      </p>
    </div>
  );
}
