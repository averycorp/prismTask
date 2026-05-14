import { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import {
  DEFAULT_BALANCE_PREFERENCES,
  getBalancePreferences,
  setBalancePreferences,
  type BalancePreferences,
} from '@/api/firestore/balancePreferences';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { Button } from '@/components/ui/Button';

/**
 * Work-Life Balance settings card. Mirrors Android's
 * `WorkLifeBalanceSection.kt`: target-ratio sliders for the four tracked
 * life categories, an enable-bar toggle, and an overload-threshold slider.
 *
 * Targets are stored as 0..100 ints in Firestore so Android can round-trip
 * them via `GenericPreferenceSyncService` without lossy float ↔ int conversion.
 */
export function WorkLifeBalanceSection() {
  const [prefs, setPrefs] = useState<BalancePreferences>(DEFAULT_BALANCE_PREFERENCES);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  const uid = (() => {
    try {
      return getFirebaseUid();
    } catch {
      return null;
    }
  })();

  useEffect(() => {
    if (!uid) {
      setLoading(false);
      return;
    }
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load preferences on mount
    setLoading(true);
    getBalancePreferences(uid)
      .then((p) => setPrefs(p))
      .catch((e) =>
        toast.error((e as Error).message || 'Failed to load balance prefs'),
      )
      .finally(() => setLoading(false));
  }, [uid]);

  const update = <K extends keyof BalancePreferences>(
    key: K,
    value: BalancePreferences[K],
  ) => {
    setPrefs((prev) => ({ ...prev, [key]: value }));
    setDirty(true);
  };

  const totalTarget =
    prefs.workTarget + prefs.personalTarget + prefs.selfCareTarget + prefs.healthTarget;
  const sumOK = Math.abs(totalTarget - 100) <= 1;

  const handleSave = async () => {
    if (!uid) return;
    if (!sumOK) {
      toast.error(`Targets must sum to 100 (currently ${totalTarget}).`);
      return;
    }
    setSaving(true);
    try {
      await setBalancePreferences(uid, prefs);
      toast.success('Balance settings saved');
      setDirty(false);
    } catch (e) {
      toast.error((e as Error).message || 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  if (!uid) {
    return (
      <p className="text-xs text-[var(--color-text-secondary)]">
        Sign in to configure work-life balance preferences.
      </p>
    );
  }

  if (loading) {
    return (
      <div className="flex items-center gap-2 py-4 text-[var(--color-text-secondary)]">
        <Loader2 className="h-4 w-4 animate-spin" /> Loading…
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4 text-sm">
      <p className="text-xs text-[var(--color-text-secondary)]">
        Target distribution across life categories. The Today balance bar
        and weekly report compare your actual ratios against these. Targets
        must sum to 100.
      </p>

      <SliderRow
        label="Work"
        hint="Meetings, deadlines, deliverables"
        value={prefs.workTarget}
        onChange={(v) => update('workTarget', v)}
      />
      <SliderRow
        label="Personal"
        hint="Chores, errands, family"
        value={prefs.personalTarget}
        onChange={(v) => update('personalTarget', v)}
      />
      <SliderRow
        label="Self-care"
        hint="Movement, hobbies, rest"
        value={prefs.selfCareTarget}
        onChange={(v) => update('selfCareTarget', v)}
      />
      <SliderRow
        label="Health"
        hint="Medical, therapy, refills"
        value={prefs.healthTarget}
        onChange={(v) => update('healthTarget', v)}
      />

      <p
        className={`text-[11px] ${
          sumOK ? 'text-[var(--color-text-secondary)]' : 'text-red-500'
        }`}
      >
        Total: {totalTarget}% {sumOK ? '✓' : '— must sum to 100'}
      </p>

      <ToggleRow
        label="Show balance bar on Today"
        description="Compact stacked bar above your task list."
        checked={prefs.showBalanceBar}
        onChange={(v) => update('showBalanceBar', v)}
      />

      <SliderRow
        label="Overload threshold"
        hint="How far over the work target before the overload badge fires."
        value={prefs.overloadThresholdPct}
        min={5}
        max={25}
        suffix="%"
        onChange={(v) => update('overloadThresholdPct', v)}
      />

      <div className="flex justify-end">
        <Button onClick={handleSave} disabled={saving || !dirty || !sumOK}>
          {saving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
          Save
        </Button>
      </div>
    </div>
  );
}

function SliderRow({
  label,
  hint,
  value,
  onChange,
  min = 0,
  max = 100,
  suffix = '%',
}: {
  label: string;
  hint?: string;
  value: number;
  onChange: (v: number) => void;
  min?: number;
  max?: number;
  suffix?: string;
}) {
  return (
    <div>
      <div className="flex items-baseline justify-between">
        <span className="text-sm font-medium text-[var(--color-text-primary)]">
          {label}
        </span>
        <span className="text-xs text-[var(--color-text-secondary)]">
          {value}
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
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="mt-1 w-full accent-[var(--color-accent)]"
      />
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
    <label className="flex items-start gap-3 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="mt-0.5 h-4 w-4 shrink-0 rounded border-[var(--color-border)] text-[var(--color-accent)]"
      />
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-medium text-[var(--color-text-primary)]">
          {label}
        </span>
        {description && (
          <span className="block text-xs text-[var(--color-text-secondary)]">
            {description}
          </span>
        )}
      </span>
    </label>
  );
}
