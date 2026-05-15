import { useEffect, useRef, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { useAuthStore } from '@/stores/authStore';
import { useAdvancedTuningStore } from '@/stores/advancedTuningStore';
import type {
  AdvancedTuningPatch,
  ForgivenessModeKnobs,
} from '@/api/firestore/advancedTuningPreferences';

/**
 * Settings card for Advanced Tuning — exposes the per-user knobs the
 * three composable philosophy pillars depend on (Phase 2 item #4 from
 * `docs/audits/WEB_PILLARS_PHILOSOPHY_AUDIT.md`):
 *
 *   1. Forgiveness streak knobs — `gracePeriodDays` (1..30) and
 *      `allowedMisses` (0..5). The streak math in `streaks.ts` and
 *      `checkInStreak.ts` reads these via `selectForgivenessConfig`.
 *      Mirrors Android's `ForgivenessStreakSection.kt` sliders.
 *
 *   2. Task Mode custom keywords — Work / Play / Relax, one CSV per
 *      tier. Persisted only in this PR; the classifier port worker
 *      will consume them in a follow-up.
 *
 *   3. Cognitive Load custom keywords — Easy / Medium / Hard, one CSV
 *      per tier. Same persist-only contract.
 *
 * Coexists with `NdModesSection.tsx`'s binary `forgivenessStreaks`
 * toggle. The ND toggle is a quick on-ramp ("turn forgiveness on");
 * the sliders here are the scalar tuning. Either surface can flip
 * the underlying `forgivenessEnabled` flag — they're not mutually
 * exclusive, they're two views over the same state.
 *
 * Copy is descriptive, not prescriptive. We quote the philosophy docs
 * directly rather than telling the user what to set the value to.
 */
export function AdvancedTuningSection() {
  const firebaseUid = useAuthStore((s) => s.firebaseUid);
  const prefs = useAdvancedTuningStore((s) => s.prefs);
  const loaded = useAdvancedTuningStore((s) => s.loaded);
  const update = useAdvancedTuningStore((s) => s.update);

  if (!firebaseUid) {
    return (
      <p className="text-xs text-[var(--color-text-secondary)]">
        Sign in to configure advanced tuning preferences.
      </p>
    );
  }

  if (!loaded) {
    return (
      <div className="flex items-center gap-2 py-4 text-[var(--color-text-secondary)]">
        <Loader2 className="h-4 w-4 animate-spin" /> Loading…
      </div>
    );
  }

  const patch = (p: AdvancedTuningPatch) => {
    void update(firebaseUid, p);
  };

  return (
    <div className="flex flex-col gap-5 text-sm">
      <p className="text-xs text-[var(--color-text-secondary)]">
        Knobs that shape the three composable pillars — forgiveness-first
        streaks, Task Mode classification, and Cognitive Load
        classification. Changes sync cross-device with Android.
      </p>

      {/* Forgiveness streak knobs */}
      <div className="flex flex-col gap-3">
        <SubHeader label="Forgiveness Streak" />
        <p className="text-[11px] text-[var(--color-text-secondary)]">
          A rolling window of forgiven misses inside an active streak. From{' '}
          <span className="font-mono">docs/FORGIVENESS_FIRST.md</span>: "the
          streak bends instead of breaking."
        </p>

        <ToggleRow
          label="Forgive the Occasional Miss"
          description="When off, a single missed day ends the chain. When on, the sliders below decide how many misses the chain tolerates."
          checked={prefs.forgivenessEnabled}
          onChange={(v) => patch({ forgivenessEnabled: v })}
        />

        <SliderRow
          label="Grace Window"
          value={prefs.gracePeriodDays}
          min={1}
          max={30}
          suffix={prefs.gracePeriodDays === 1 ? ' day' : ' days'}
          disabled={!prefs.forgivenessEnabled}
          hint="The rolling window misses are counted against."
          onChange={(v) => patch({ gracePeriodDays: v })}
        />
        <SliderRow
          label="Allowed Misses"
          value={prefs.allowedMisses}
          min={0}
          max={5}
          suffix={prefs.allowedMisses === 1 ? ' miss' : ' misses'}
          disabled={!prefs.forgivenessEnabled}
          hint="How many missed days the grace window tolerates."
          onChange={(v) => patch({ allowedMisses: v })}
        />
      </div>

      <Divider />

      {/* Per-mode forgiveness overrides */}
      <div className="flex flex-col gap-3">
        <SubHeader label="Streak Strictness by Mode" />
        <p className="text-[11px] text-[var(--color-text-secondary)]">
          Per-mode overrides for the forgiveness window. From{' '}
          <span className="font-mono">docs/WORK_PLAY_RELAX.md</span> §{' '}
          <em>Streak strictness</em>: Work uses the standard window, Play
          and Relax default to a wider one. Self-paced activities get more
          slack so the streak never inflates Work over rest. The base
          knobs above still apply to Uncategorized tasks and to habit
          streaks (habits don't carry a mode).
        </p>

        <ModeStrictnessGroup
          label="Work"
          mode="work"
          knobs={prefs.forgivenessByMode.work}
          disabled={!prefs.forgivenessEnabled}
          onChange={(p) => patch({ forgivenessByMode: { work: p } })}
        />
        <ModeStrictnessGroup
          label="Play"
          mode="play"
          knobs={prefs.forgivenessByMode.play}
          disabled={!prefs.forgivenessEnabled}
          onChange={(p) => patch({ forgivenessByMode: { play: p } })}
        />
        <ModeStrictnessGroup
          label="Relax"
          mode="relax"
          knobs={prefs.forgivenessByMode.relax}
          disabled={!prefs.forgivenessEnabled}
          onChange={(p) => patch({ forgivenessByMode: { relax: p } })}
        />
      </div>

      <Divider />

      {/* Task Mode keywords */}
      <div className="flex flex-col gap-3">
        <SubHeader label="Task Mode Keywords" />
        <p className="text-[11px] text-[var(--color-text-secondary)]">
          Extra keywords appended to the built-in Work / Play / Relax
          classifier. Comma-separated. From{' '}
          <span className="font-mono">docs/WORK_PLAY_RELAX.md</span>:
          keywords are matched on the task title, description, and tags;
          the tie-break order is Relax → Play → Work so Work is never
          inflated.
        </p>

        <KeywordTextarea
          label="Work keywords"
          value={prefs.taskModeKeywords.work}
          placeholder="e.g. report, deadline, deliverable"
          onChange={(v) => patch({ taskModeKeywords: { work: v } })}
        />
        <KeywordTextarea
          label="Play keywords"
          value={prefs.taskModeKeywords.play}
          placeholder="e.g. game, hobby, jam session"
          onChange={(v) => patch({ taskModeKeywords: { play: v } })}
        />
        <KeywordTextarea
          label="Relax keywords"
          value={prefs.taskModeKeywords.relax}
          placeholder="e.g. nap, stretch, breathwork"
          onChange={(v) => patch({ taskModeKeywords: { relax: v } })}
        />
      </div>

      <Divider />

      {/* Cognitive Load keywords */}
      <div className="flex flex-col gap-3">
        <SubHeader label="Cognitive Load Keywords" />
        <p className="text-[11px] text-[var(--color-text-secondary)]">
          Extra keywords appended to the built-in Easy / Medium / Hard
          classifier. Comma-separated. From{' '}
          <span className="font-mono">docs/COGNITIVE_LOAD.md</span>: load
          tags start-friction, not effort; the tie-break order is Easy →
          Medium → Hard so difficulty is never inflated.
        </p>

        <KeywordTextarea
          label="Easy keywords"
          value={prefs.cognitiveLoadKeywords.easy}
          placeholder="e.g. quick, simple, copy/paste"
          onChange={(v) => patch({ cognitiveLoadKeywords: { easy: v } })}
        />
        <KeywordTextarea
          label="Medium keywords"
          value={prefs.cognitiveLoadKeywords.medium}
          placeholder="e.g. draft, review, refactor"
          onChange={(v) => patch({ cognitiveLoadKeywords: { medium: v } })}
        />
        <KeywordTextarea
          label="Hard keywords"
          value={prefs.cognitiveLoadKeywords.hard}
          placeholder="e.g. architecture, research, audit"
          onChange={(v) => patch({ cognitiveLoadKeywords: { hard: v } })}
        />
      </div>

      <p className="text-[11px] text-[var(--color-text-secondary)]">
        Keyword fields persist now and will start influencing the on-device
        classifier once the Task Mode and Cognitive Load classifier ports
        ship (Phase 2 items #2 and #3 of the pillars audit). Until then
        they are stored cross-device but inert.
      </p>
    </div>
  );
}

function SubHeader({ label }: { label: string }) {
  return (
    <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">
      {label}
    </h3>
  );
}

function Divider() {
  return <hr className="border-t border-[var(--color-border)]" />;
}

function SliderRow({
  label,
  value,
  min,
  max,
  suffix = '',
  hint,
  disabled,
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  suffix?: string;
  hint?: string;
  disabled?: boolean;
  onChange: (v: number) => void;
}) {
  // Debounce Firestore writes so dragging the slider doesn't fan out one
  // network round-trip per integer step. The local optimistic apply still
  // fires synchronously inside the store; this just smooths the push.
  const debounceRef = useRef<number | null>(null);
  const [localValue, setLocalValue] = useState(value);

  // Keep local in sync with remote updates (e.g. Android pushing a tweak).
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- mirror external (Firestore-driven) store value into the local debounce buffer; the local state is intentionally derived from the prop, not authoritative
    setLocalValue(value);
  }, [value]);

  const commit = (next: number) => {
    setLocalValue(next);
    if (debounceRef.current !== null) {
      window.clearTimeout(debounceRef.current);
    }
    debounceRef.current = window.setTimeout(() => {
      onChange(next);
      debounceRef.current = null;
    }, 200);
  };

  return (
    <div>
      <div className="flex items-baseline justify-between">
        <span
          className={`text-sm font-medium ${
            disabled
              ? 'text-[var(--color-text-secondary)]'
              : 'text-[var(--color-text-primary)]'
          }`}
        >
          {label}
        </span>
        <span className="text-xs text-[var(--color-text-secondary)]">
          {localValue}
          {suffix}
        </span>
      </div>
      {hint && (
        <p className="mt-0.5 text-[11px] text-[var(--color-text-secondary)]">
          {hint}
        </p>
      )}
      <input
        type="range"
        min={min}
        max={max}
        value={localValue}
        disabled={disabled}
        aria-label={label}
        aria-valuemin={min}
        aria-valuemax={max}
        aria-valuenow={localValue}
        onChange={(e) => commit(Number(e.target.value))}
        className="mt-1 w-full accent-[var(--color-accent)] disabled:opacity-50"
      />
    </div>
  );
}

function KeywordTextarea({
  label,
  value,
  placeholder,
  onChange,
}: {
  label: string;
  value: string;
  placeholder?: string;
  onChange: (v: string) => void;
}) {
  // Debounce the Firestore push for the same reason as the slider — let
  // the user finish typing before fanning out a write. The local state
  // is the textarea's own controlled value; we mirror to the store on
  // pause.
  const debounceRef = useRef<number | null>(null);
  const [localValue, setLocalValue] = useState(value);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- mirror external (Firestore-driven) store value into the local debounce buffer; the local state is intentionally derived from the prop, not authoritative
    setLocalValue(value);
  }, [value]);

  const commit = (next: string) => {
    setLocalValue(next);
    if (debounceRef.current !== null) {
      window.clearTimeout(debounceRef.current);
    }
    debounceRef.current = window.setTimeout(() => {
      onChange(next);
      debounceRef.current = null;
    }, 400);
  };

  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-[var(--color-text-primary)]">
        {label}
      </span>
      <textarea
        rows={2}
        value={localValue}
        placeholder={placeholder}
        aria-label={label}
        onChange={(e) => commit(e.target.value)}
        className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2.5 py-1.5 font-mono text-[12px] text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
      />
    </label>
  );
}

function ModeStrictnessGroup({
  label,
  mode,
  knobs,
  disabled,
  onChange,
}: {
  label: string;
  mode: 'work' | 'play' | 'relax';
  knobs: ForgivenessModeKnobs;
  disabled: boolean;
  onChange: (p: Partial<ForgivenessModeKnobs>) => void;
}) {
  // A small two-slider cluster: window + misses for one mode. The outer
  // section's `forgivenessEnabled` toggle drives the disabled state
  // through — when forgiveness is globally off, per-mode tuning has no
  // effect and the sliders dim accordingly.
  return (
    <div className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)]/40 p-2.5">
      <p
        className={`mb-2 text-xs font-semibold ${
          disabled
            ? 'text-[var(--color-text-secondary)]'
            : 'text-[var(--color-text-primary)]'
        }`}
        aria-label={`${label} streak strictness`}
      >
        {label}
      </p>
      <SliderRow
        label={`${label} grace window`}
        value={knobs.gracePeriodDays}
        min={1}
        max={30}
        suffix={knobs.gracePeriodDays === 1 ? ' day' : ' days'}
        disabled={disabled}
        onChange={(v) => onChange({ gracePeriodDays: v })}
      />
      <SliderRow
        label={`${label} allowed misses`}
        value={knobs.allowedMisses}
        min={0}
        max={5}
        suffix={knobs.allowedMisses === 1 ? ' miss' : ' misses'}
        disabled={disabled}
        onChange={(v) => onChange({ allowedMisses: v })}
      />
      <p className="mt-1 text-[10px] text-[var(--color-text-secondary)]">
        Override only — Uncategorized {mode === 'work' ? 'and habit ' : ''}
        streaks always read the base knobs above.
      </p>
    </div>
  );
}

function ToggleRow({
  label,
  description,
  checked,
  onChange,
}: {
  label: string;
  description?: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="flex items-start justify-between gap-3">
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-[var(--color-text-primary)]">
          {label}
        </p>
        {description && (
          <p className="text-xs text-[var(--color-text-secondary)]">
            {description}
          </p>
        )}
      </div>
      <button
        role="switch"
        aria-checked={checked}
        aria-label={label}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full transition-colors ${
          checked ? 'bg-[var(--color-accent)]' : 'bg-[var(--color-border)]'
        }`}
      >
        <span
          className={`inline-block h-5 w-5 transform rounded-full bg-white shadow transition-transform ${
            checked ? 'translate-x-[22px]' : 'translate-x-0.5'
          } mt-0.5`}
        />
      </button>
    </div>
  );
}
