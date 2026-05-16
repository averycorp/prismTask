import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ChevronLeft, Loader2, Save, Wand2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import {
  AnalogClockPicker,
  useAnalogClockState,
} from '@/components/AnalogClockPicker';
import { useBoundaryRulesStore } from '@/stores/boundaryRulesStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import {
  createRule,
  updateRule,
  type BoundaryLifeCategory,
  type BoundaryRule,
  type BoundaryRuleType,
} from '@/api/firestore/boundaryRules';
import { parse, type BoundaryRule as ParsedRule } from '@/lib/boundaryRuleParser';
import { useSettingsStore } from '@/stores/settingsStore';
import { DAY_SHORT, prettyCategory } from './boundaryFormat';

/**
 * Single-rule editor — web port of Android's
 * `ui/screens/boundaries/BoundaryRuleEditScreen.kt`.
 *
 * Three rule types share one form:
 *  - `work_hours_window`: start hour + end hour via AnalogClockPicker.
 *  - `category_limit`: LifeCategory chip + hours/day spinner + max/min.
 *  - `escalation`: comma-separated notification profile names + delay.
 *
 * Active-days are a 7-chip toggle. A "Parse from text" helper runs the
 * shared `boundaryRuleParser` so users can pre-fill the form by typing
 * "No work after 18:00".
 *
 * The save path writes through the existing `createRule` / `updateRule`
 * Firestore helpers (`lwwUpdate` underneath — that's the safe-merge
 * primitive in this repo). The snapshot listener reconciles the local
 * store; this screen just navigates back to the list on success.
 */
export function BoundaryRuleEditScreen() {
  const { id } = useParams<{ id: string }>();
  const isNew = !id || id === 'new';
  const navigate = useNavigate();

  const rules = useBoundaryRulesStore((s) => s.rules);
  const existing: BoundaryRule | null = isNew
    ? null
    : (rules.find((r) => r.id === id) ?? null);

  const uid = readUid();

  const timeFormat = useSettingsStore((s) => s.timeFormat);
  const is24Hour = timeFormat === '24h';

  // Form state. We seed from `existing` when editing, otherwise from
  // sensible defaults that match Android's BUILT_IN rules.
  const [ruleType, setRuleType] = useState<BoundaryRuleType>(
    existing?.type ?? 'work_hours_window',
  );
  const [label, setLabel] = useState(existing?.label ?? '');
  const [enabled, setEnabled] = useState(existing?.enabled ?? true);

  // Work-hours times.
  const startApi = useAnalogClockState({
    initialHour: existing?.type === 'work_hours_window' ? existing.value : 9,
    initialMinute:
      existing?.type === 'work_hours_window'
        ? (existing.start_minute ?? 0)
        : 0,
    initialSecond: 0,
    is24Hour,
  });
  const endApi = useAnalogClockState({
    initialHour:
      existing?.type === 'work_hours_window'
        ? (existing.secondary_value ?? 18)
        : 18,
    initialMinute:
      existing?.type === 'work_hours_window' ? (existing.end_minute ?? 0) : 0,
    initialSecond: 0,
    is24Hour,
  });

  // Category-limit fields.
  const [category, setCategory] = useState<BoundaryLifeCategory>(
    (existing?.category as BoundaryLifeCategory) ?? 'WORK',
  );
  const [bound, setBound] = useState<'max' | 'min'>(
    existing?.bound === 'min' ? 'min' : 'max',
  );
  const [hoursPerDay, setHoursPerDay] = useState<number>(
    existing?.type === 'category_limit' ? existing.value : 4,
  );

  // Escalation fields.
  const [chainRaw, setChainRaw] = useState<string>(
    existing?.escalation_chain?.join(', ') ?? 'Focus, Quiet',
  );
  const [escalationTrigger, setEscalationTrigger] = useState<string>(
    existing?.escalation_trigger ?? '',
  );
  const [delayMinutes, setDelayMinutes] = useState<number>(
    existing?.type === 'escalation' ? existing.value : 10,
  );

  // Active days. Default to every day. Stored as a Set so toggles are
  // O(1) and the chip row renders without extra reduce work.
  const [activeDays, setActiveDays] = useState<Set<number>>(() => {
    const initial = existing?.active_days ?? [1, 2, 3, 4, 5, 6, 7];
    return new Set(initial);
  });

  const [parseText, setParseText] = useState('');
  const [parseError, setParseError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  if (!uid) {
    return (
      <div className="mx-auto max-w-3xl p-4">
        <p className="text-sm text-[var(--color-text-secondary)]">
          Sign in to edit Boundary Rules.
        </p>
      </div>
    );
  }

  const toggleDay = (day: number) => {
    setActiveDays((prev) => {
      const next = new Set(prev);
      if (next.has(day)) next.delete(day);
      else next.add(day);
      return next;
    });
  };

  const applyParsed = (parsed: ParsedRule) => {
    setParseError(null);
    if (parsed.ruleType === 'work-hours') {
      setRuleType('work_hours_window');
      startApi.setHour(parsed.startHour);
      startApi.setMinute(parsed.startMinute);
      endApi.setHour(parsed.endHour);
      endApi.setMinute(parsed.endMinute);
      setActiveDays(new Set(parsed.activeDays));
    } else if (parsed.ruleType === 'category-limit') {
      setRuleType('category_limit');
      setCategory(parsed.category);
      setBound(parsed.bound);
      setHoursPerDay(parsed.hoursPerDay);
      setActiveDays(new Set(parsed.activeDays));
    } else {
      setRuleType('escalation');
      setEscalationTrigger(parsed.trigger);
      setChainRaw(parsed.profiles.join(', '));
      setDelayMinutes(parsed.delayMinutes);
      setActiveDays(new Set(parsed.activeDays));
    }
  };

  const handleParse = () => {
    const result = parse(parseText);
    if ('error' in result) {
      setParseError(result.error);
      return;
    }
    applyParsed(result);
    toast.success('Parsed phrase into form');
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      const computedLabel = label.trim() || defaultLabel();
      const days = Array.from(activeDays).sort();
      const base = {
        label: computedLabel,
        enabled,
        active_days: days.length > 0 ? days : null,
      };
      let payload: Parameters<typeof createRule>[1];
      if (ruleType === 'work_hours_window') {
        payload = {
          type: 'work_hours_window',
          value: startApi.state.hour,
          secondary_value: endApi.state.hour,
          start_minute: startApi.state.minute,
          end_minute: endApi.state.minute,
          category: null,
          bound: null,
          escalation_chain: null,
          escalation_trigger: null,
          ...base,
        };
      } else if (ruleType === 'category_limit') {
        if (!(hoursPerDay > 0 && hoursPerDay <= 24)) {
          toast.error('Hours/day must be between 0 and 24.');
          setSaving(false);
          return;
        }
        payload = {
          type: 'category_limit',
          value: hoursPerDay,
          secondary_value: null,
          category,
          bound,
          start_minute: null,
          end_minute: null,
          escalation_chain: null,
          escalation_trigger: null,
          ...base,
        };
      } else {
        const profiles = chainRaw
          .split(/[,\n]/)
          .map((s) => s.trim())
          .filter(Boolean);
        if (profiles.length === 0) {
          toast.error('Escalation chain needs at least one profile.');
          setSaving(false);
          return;
        }
        if (!(delayMinutes > 0)) {
          toast.error('Stage delay must be > 0 minutes.');
          setSaving(false);
          return;
        }
        payload = {
          type: 'escalation',
          value: delayMinutes,
          secondary_value: null,
          escalation_chain: profiles,
          escalation_trigger: escalationTrigger.trim() || null,
          category: null,
          bound: null,
          start_minute: null,
          end_minute: null,
          ...base,
        };
      }
      if (existing) {
        await updateRule(uid, existing.id, payload);
        toast.success('Rule updated');
      } else {
        await createRule(uid, payload);
        toast.success('Rule created');
      }
      navigate('/boundaries');
    } catch (e) {
      toast.error((e as Error).message || 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  function defaultLabel(): string {
    if (ruleType === 'work_hours_window') {
      const s = `${pad(startApi.state.hour)}:${pad(startApi.state.minute)}`;
      const e = `${pad(endApi.state.hour)}:${pad(endApi.state.minute)}`;
      return `${s}–${e} work window`;
    }
    if (ruleType === 'category_limit') {
      const verb = bound === 'min' ? 'Min' : 'Max';
      return `${verb} ${hoursPerDay}h/day on ${prettyCategory(category)}`;
    }
    const chain = chainRaw.trim().replace(/\s+/g, ' ');
    return `Escalate to ${chain}`;
  }

  return (
    <div className="mx-auto max-w-3xl p-4">
      <header className="mb-6 flex items-center gap-2">
        <Link
          to="/boundaries"
          className="rounded-md p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]"
          aria-label="Back to Boundary Rules"
        >
          <ChevronLeft className="h-5 w-5" />
        </Link>
        <h1 className="text-2xl font-semibold text-[var(--color-text-primary)]">
          {existing ? 'Edit Rule' : 'New Rule'}
        </h1>
      </header>

      <section className="mb-4 rounded-md border border-dashed border-[var(--color-border)] bg-[var(--color-bg-card)] p-3">
        <label className="block text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
          Quick Parse
        </label>
        <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
          Type a phrase like "No work after 18:00" — we fill the form for you.
        </p>
        <div className="mt-2 flex flex-col gap-2 sm:flex-row">
          <input
            type="text"
            value={parseText}
            onChange={(e) => setParseText(e.target.value)}
            placeholder="No work after 18:00 on weekdays"
            className="flex-1 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            data-testid="boundary-quick-parse-input"
          />
          <Button
            variant="secondary"
            onClick={handleParse}
            data-testid="boundary-quick-parse-submit"
          >
            <Wand2 className="mr-1 h-3 w-3" aria-hidden="true" />
            Parse
          </Button>
        </div>
        {parseError && (
          <p className="mt-2 text-xs text-rose-500">{parseError}</p>
        )}
      </section>

      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          handleSave();
        }}
      >
        <Field label="Rule Type">
          <div className="flex flex-wrap gap-1.5">
            <TypeChip
              label="Work Hours"
              selected={ruleType === 'work_hours_window'}
              onClick={() => setRuleType('work_hours_window')}
            />
            <TypeChip
              label="Category Limit"
              selected={ruleType === 'category_limit'}
              onClick={() => setRuleType('category_limit')}
            />
            <TypeChip
              label="Escalation"
              selected={ruleType === 'escalation'}
              onClick={() => setRuleType('escalation')}
            />
          </div>
        </Field>

        {ruleType === 'work_hours_window' && (
          <Field
            label="Work Hours Window"
            hint="Choose the start and end of the allowed work window. Outside this window, Today shows an info nudge."
          >
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                  Start
                </p>
                <AnalogClockPicker api={startApi} diameter={200} />
              </div>
              <div>
                <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                  End
                </p>
                <AnalogClockPicker api={endApi} diameter={200} />
              </div>
            </div>
          </Field>
        )}

        {ruleType === 'category_limit' && (
          <Field
            label="Category Limit"
            hint='Cap (or floor) the hours/day spent in one Life Category. "Max" warns when crossed; "Min" warns when undershot.'
          >
            <div className="flex flex-wrap gap-1.5">
              {(['WORK', 'PERSONAL', 'SELF_CARE', 'HEALTH'] as const).map(
                (c) => (
                  <TypeChip
                    key={c}
                    label={prettyCategory(c)}
                    selected={category === c}
                    onClick={() => setCategory(c)}
                  />
                ),
              )}
            </div>
            <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
              <label className="text-sm">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                  Bound
                </span>
                <div className="flex gap-1.5">
                  <TypeChip
                    label="Max"
                    selected={bound === 'max'}
                    onClick={() => setBound('max')}
                  />
                  <TypeChip
                    label="Min"
                    selected={bound === 'min'}
                    onClick={() => setBound('min')}
                  />
                </div>
              </label>
              <label className="text-sm">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                  Hours per day
                </span>
                <input
                  type="number"
                  min={0.5}
                  max={24}
                  step={0.5}
                  value={hoursPerDay}
                  onChange={(e) =>
                    setHoursPerDay(Number(e.target.value) || 0)
                  }
                  className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
              </label>
            </div>
          </Field>
        )}

        {ruleType === 'escalation' && (
          <Field
            label="Escalation Chain"
            hint="When the triggering rule trips, fire the first profile; advance to the next profile after each stage delay."
          >
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <label className="text-sm">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                  Triggered by (rule name)
                </span>
                <input
                  type="text"
                  value={escalationTrigger}
                  onChange={(e) => setEscalationTrigger(e.target.value)}
                  placeholder="Work-Hours"
                  className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
              </label>
              <label className="text-sm">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                  Stage delay (minutes)
                </span>
                <input
                  type="number"
                  min={1}
                  max={240}
                  value={delayMinutes}
                  onChange={(e) =>
                    setDelayMinutes(Number(e.target.value) || 0)
                  }
                  className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
              </label>
            </div>
            <label className="mt-3 block text-sm">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                Profiles (comma-separated)
              </span>
              <input
                type="text"
                value={chainRaw}
                onChange={(e) => setChainRaw(e.target.value)}
                placeholder="Focus, Quiet, Do Not Disturb"
                className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
              />
            </label>
          </Field>
        )}

        <Field
          label="Active Days"
          hint="Tap a day to include or exclude it from this rule."
        >
          <div className="flex flex-wrap gap-1.5">
            {([1, 2, 3, 4, 5, 6, 7] as const).map((d) => (
              <TypeChip
                key={d}
                label={DAY_SHORT[d]}
                selected={activeDays.has(d)}
                onClick={() => toggleDay(d)}
              />
            ))}
          </div>
        </Field>

        <Field label="Label">
          <input
            type="text"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder={defaultLabel()}
            className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            data-testid="boundary-rule-label"
          />
        </Field>

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            className="h-4 w-4 rounded border-[var(--color-border)] text-[var(--color-accent)]"
          />
          <span>Enabled</span>
        </label>

        <div className="flex justify-end gap-2 pt-2">
          <Button
            type="button"
            variant="secondary"
            onClick={() => navigate('/boundaries')}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            disabled={saving}
            data-testid="boundary-rule-save"
          >
            {saving ? (
              <Loader2 className="mr-1 h-3 w-3 animate-spin" />
            ) : (
              <Save className="mr-1 h-3 w-3" />
            )}
            Save Rule
          </Button>
        </div>
      </form>
    </div>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <fieldset className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3">
      <legend className="px-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
        {label}
      </legend>
      {hint && (
        <p className="mb-2 mt-1 text-xs text-[var(--color-text-secondary)]">
          {hint}
        </p>
      )}
      {children}
    </fieldset>
  );
}

function TypeChip({
  label,
  selected,
  onClick,
}: {
  label: string;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      className={`rounded-full px-3 py-1 text-xs font-semibold transition-colors ${
        selected
          ? 'bg-[var(--color-accent)] text-white'
          : 'border border-[var(--color-border)] bg-[var(--color-bg-card)] text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]'
      }`}
    >
      {label}
    </button>
  );
}

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

function readUid(): string | null {
  try {
    return getFirebaseUid();
  } catch {
    return null;
  }
}
