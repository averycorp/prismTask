import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, Brain, Loader2 } from 'lucide-react';
import { useAuthStore } from '@/stores/authStore';
import { useNdPreferencesStore } from '@/stores/ndPreferencesStore';
import {
  isAnyNdModeActive,
  type CelebrationIntensity,
  type NdPreferences,
} from '@/api/firestore/ndPreferences';

/**
 * Settings → Brain Mode screen (web parity unit 21 of 23).
 *
 * Mirrors Android's `BrainModeScreen.kt` + `BrainModeSection.kt` in a
 * focused, dedicated sub-screen surfaced from the main Settings page.
 *
 * Six controls in spec order:
 *   1. Brain Mode master toggle
 *   2. UI Complexity segmented slider (Minimal / Standard / Full)
 *   3. Forgiveness Streak toggle
 *   4. Focus Release toggle
 *   5. Ship It Celebration intensity (Off / Subtle / Standard / Big)
 *   6. Energy-Aware Suggestions toggle
 *
 * All controls read/write via `ndPreferencesStore.update`, which
 * optimistically applies locally and pushes the diff to Firestore at
 * `users/{uid}/prefs/nd_prefs` — the same doc Android writes.
 *
 * Mapping notes (web ↔ Android NdPreferences model):
 *   - "Brain Mode" is a derived master switch over the three Android
 *     mode toggles (`adhdModeEnabled`, `calmModeEnabled`,
 *     `focusReleaseModeEnabled`). Toggling master ON flips all three on,
 *     OFF flips them all off. Matches Android's
 *     `NdPreferencesDataStore.setBrainMode` cascade behavior.
 *   - "UI Complexity" maps to the five Calm-Mode visual sub-settings.
 *     Minimal = all five ON, Standard = a curated mid-tier (animations
 *     softened, palette muted, haptics gentled — but contrast stays
 *     normal and quiet mode is off), Full = all five OFF.
 *   - "Ship It Celebration intensity" combines the boolean
 *     `shipItCelebrationsEnabled` plus the `CelebrationIntensity` enum
 *     into a single 0..3 dial. 0 → disabled, 1..3 → enabled with the
 *     corresponding enum.
 */
export function BrainModeScreen() {
  const firebaseUid = useAuthStore((s) => s.firebaseUid);
  const prefs = useNdPreferencesStore((s) => s.prefs);
  const loaded = useNdPreferencesStore((s) => s.loaded);
  const update = useNdPreferencesStore((s) => s.update);

  const brainModeOn = useMemo(() => isAnyNdModeActive(prefs), [prefs]);
  const uiComplexityLevel = useMemo(
    () => computeUiComplexityLevel(prefs),
    [prefs],
  );
  const shipItIntensityLevel = useMemo(
    () => computeShipItIntensityLevel(prefs),
    [prefs],
  );

  if (!firebaseUid) {
    return (
      <PageShell>
        <p className="text-sm text-[var(--color-text-secondary)]">
          Sign in to configure Brain Mode.
        </p>
      </PageShell>
    );
  }

  if (!loaded) {
    return (
      <PageShell>
        <div className="flex items-center gap-2 py-6 text-[var(--color-text-secondary)]">
          <Loader2 className="h-4 w-4 animate-spin" /> Loading…
        </div>
      </PageShell>
    );
  }

  const patch = (p: Partial<NdPreferences>) => {
    void update(firebaseUid, p);
  };

  const setBrainModeMaster = (next: boolean) => {
    // Cascade all three Android-side mode toggles together so the
    // master switch lines up with the actual gates downstream code
    // reads (`focusReleaseModeEnabled`, `calmModeEnabled`,
    // `adhdModeEnabled`).
    patch({
      adhdModeEnabled: next,
      calmModeEnabled: next,
      focusReleaseModeEnabled: next,
    });
  };

  const setUiComplexity = (level: 0 | 1 | 2) => {
    patch(uiComplexityPatchFor(level));
  };

  const setShipItIntensity = (level: 0 | 1 | 2 | 3) => {
    patch(shipItIntensityPatchFor(level));
  };

  const disabledByMaster = !brainModeOn;

  return (
    <PageShell>
      <p className="text-sm text-[var(--color-text-secondary)]">
        Brain Mode tunes the app to match how your brain works today.
        Flip the master switch and pick the bundle that fits — every
        setting syncs across web and Android.
      </p>

      <Card>
        <ToggleRow
          label="Brain Mode"
          description="Master switch. When off, all Brain Mode features stay off regardless of the controls below."
          checked={brainModeOn}
          onChange={setBrainModeMaster}
        />
      </Card>

      <Card>
        <SegmentedSlider
          label="UI Complexity"
          description="Minimal hides chrome and dampens motion. Full shows every detail."
          options={UI_COMPLEXITY_OPTIONS}
          value={uiComplexityLevel}
          onChange={(v) => setUiComplexity(v as 0 | 1 | 2)}
          disabled={disabledByMaster}
        />
      </Card>

      <Card>
        <ToggleRow
          label="Forgiveness Streak"
          description="One missed day still counts — your streak bends instead of breaking."
          checked={prefs.forgivenessStreaks}
          onChange={(v) => patch({ forgivenessStreaks: v })}
          disabled={disabledByMaster}
        />
      </Card>

      <Card>
        <ToggleRow
          label="Focus Release"
          description="Good-Enough timers, anti-rework guards, and Ship-It celebrations so you can let go of completed work."
          checked={prefs.focusReleaseModeEnabled}
          onChange={(v) => patch({ focusReleaseModeEnabled: v })}
          disabled={disabledByMaster}
        />
      </Card>

      <Card>
        <SegmentedSlider
          label="Ship It Celebration"
          description="How loud the celebration is when you mark something done. Off skips it entirely."
          options={SHIP_IT_OPTIONS}
          value={shipItIntensityLevel}
          onChange={(v) => setShipItIntensity(v as 0 | 1 | 2 | 3)}
          disabled={disabledByMaster || !prefs.focusReleaseModeEnabled}
        />
        {!disabledByMaster && !prefs.focusReleaseModeEnabled && (
          <p className="mt-2 text-xs text-[var(--color-text-secondary)]">
            Turn on Focus Release to enable Ship-It celebrations.
          </p>
        )}
      </Card>

      <Card>
        <ToggleRow
          label="Energy-Aware Suggestions"
          description="Pomodoro and planner adapt their recommendations to the energy level you logged this week."
          checked={prefs.energyAwareSuggestionsEnabled}
          onChange={(v) => patch({ energyAwareSuggestionsEnabled: v })}
          disabled={disabledByMaster}
        />
      </Card>
    </PageShell>
  );
}

// ---------------------------------------------------------------------------
// Mapping helpers — exported for unit tests.
// ---------------------------------------------------------------------------

const CALM_KEYS: (keyof NdPreferences)[] = [
  'reduceAnimations',
  'mutedColorPalette',
  'softContrast',
  'quietMode',
  'reduceHaptics',
];

/**
 * Compute the displayed UI Complexity tier from the five Calm-Mode
 * sub-settings:
 *   - 0 (Minimal): all five ON
 *   - 1 (Standard): mid-tier — `reduceAnimations` + `mutedColorPalette`
 *     + `reduceHaptics` ON; `softContrast` + `quietMode` OFF
 *   - 2 (Full): everything else (defaults to "Full" so unrecognised
 *     combinations bias toward showing more, not less, which matches
 *     Android's "Full" being the no-restriction state)
 */
export function computeUiComplexityLevel(prefs: NdPreferences): 0 | 1 | 2 {
  const allOn = CALM_KEYS.every((k) => prefs[k] === true);
  if (allOn) return 0;
  const standard =
    prefs.reduceAnimations &&
    prefs.mutedColorPalette &&
    prefs.reduceHaptics &&
    !prefs.softContrast &&
    !prefs.quietMode;
  if (standard) return 1;
  return 2;
}

export function uiComplexityPatchFor(level: 0 | 1 | 2): Partial<NdPreferences> {
  if (level === 0) {
    return {
      reduceAnimations: true,
      mutedColorPalette: true,
      softContrast: true,
      quietMode: true,
      reduceHaptics: true,
    };
  }
  if (level === 1) {
    return {
      reduceAnimations: true,
      mutedColorPalette: true,
      reduceHaptics: true,
      softContrast: false,
      quietMode: false,
    };
  }
  return {
    reduceAnimations: false,
    mutedColorPalette: false,
    softContrast: false,
    quietMode: false,
    reduceHaptics: false,
  };
}

export function computeShipItIntensityLevel(
  prefs: NdPreferences,
): 0 | 1 | 2 | 3 {
  if (!prefs.shipItCelebrationsEnabled) return 0;
  switch (prefs.celebrationIntensity) {
    case 'LOW':
      return 1;
    case 'MEDIUM':
      return 2;
    case 'HIGH':
      return 3;
    default:
      return 2;
  }
}

export function shipItIntensityPatchFor(
  level: 0 | 1 | 2 | 3,
): Partial<NdPreferences> {
  if (level === 0) {
    return { shipItCelebrationsEnabled: false };
  }
  const intensity: CelebrationIntensity =
    level === 1 ? 'LOW' : level === 2 ? 'MEDIUM' : 'HIGH';
  return {
    shipItCelebrationsEnabled: true,
    celebrationIntensity: intensity,
  };
}

// ---------------------------------------------------------------------------
// Local primitives — kept inline so the screen reads top-to-bottom.
// ---------------------------------------------------------------------------

const UI_COMPLEXITY_OPTIONS = [
  { value: 0, label: 'Minimal' },
  { value: 1, label: 'Standard' },
  { value: 2, label: 'Full' },
] as const;

const SHIP_IT_OPTIONS = [
  { value: 0, label: 'Off' },
  { value: 1, label: 'Subtle' },
  { value: 2, label: 'Standard' },
  { value: 3, label: 'Big' },
] as const;

function PageShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto max-w-2xl">
      <div className="mb-4 flex items-center gap-3">
        <Link
          to="/settings"
          aria-label="Back to Settings"
          className="inline-flex h-9 w-9 items-center justify-center rounded-full hover:bg-[var(--color-bg-secondary)]"
        >
          <ArrowLeft className="h-5 w-5 text-[var(--color-text-primary)]" />
        </Link>
        <Brain className="h-7 w-7 text-[var(--color-accent)]" />
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          Brain Mode
        </h1>
      </div>
      <div className="flex flex-col gap-4">{children}</div>
    </div>
  );
}

function Card({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-5">
      {children}
    </div>
  );
}

function ToggleRow({
  label,
  description,
  checked,
  onChange,
  disabled,
}: {
  label: string;
  description?: string;
  checked: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <div
      className={`flex items-start justify-between gap-3 ${
        disabled ? 'opacity-60' : ''
      }`}
    >
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
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        aria-disabled={disabled || undefined}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full transition-colors disabled:cursor-not-allowed ${
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

function SegmentedSlider({
  label,
  description,
  options,
  value,
  onChange,
  disabled,
}: {
  label: string;
  description?: string;
  options: readonly { readonly value: number; readonly label: string }[];
  value: number;
  onChange: (v: number) => void;
  disabled?: boolean;
}) {
  return (
    <div className={disabled ? 'opacity-60' : ''}>
      <p className="text-sm font-medium text-[var(--color-text-primary)]">
        {label}
      </p>
      {description && (
        <p className="mb-3 text-xs text-[var(--color-text-secondary)]">
          {description}
        </p>
      )}
      <div
        role="radiogroup"
        aria-label={label}
        className="grid w-full gap-2"
        style={{
          gridTemplateColumns: `repeat(${options.length}, minmax(0, 1fr))`,
        }}
      >
        {options.map((opt) => {
          const selected = opt.value === value;
          return (
            <button
              key={opt.value}
              type="button"
              role="radio"
              aria-checked={selected}
              disabled={disabled}
              onClick={() => onChange(opt.value)}
              className={`rounded-lg border px-3 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed ${
                selected
                  ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                  : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]'
              }`}
            >
              {opt.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
