import { Brain, Loader2, Sparkles } from 'lucide-react';
import { useAuthStore } from '@/stores/authStore';
import { useNdPreferencesStore } from '@/stores/ndPreferencesStore';
import { useNdPreferences } from '@/hooks/useNdPreferences';
import type { NdPreferences } from '@/api/firestore/ndPreferences';

/**
 * Settings card for ND-friendly modes (parity audit C.7c).
 *
 * Mirrors Android's `BrainModeSection.kt` + `ForgivenessStreakSection.kt`:
 *
 *   - Three Brain Mode toggles (ADHD / Calm / Focus & Release). Each
 *     parent toggle cascades into its sub-settings via the store's
 *     `applyModeCascades` (same shape as Android's
 *     `NdPreferencesDataStore.set*Mode`).
 *   - Forgiveness Streak toggle (an ADHD-Mode sub-setting on the
 *     domain side, but surfaced separately here so users without ADHD
 *     Mode can still opt into forgiveness-first streaks).
 *   - A "UI Complexity" preview row revealing the active Calm-Mode
 *     visual sub-settings (Reduce Animations / Muted Palette / Soft
 *     Contrast / Quiet Mode / Reduce Haptics). These are the knobs that
 *     actually shape UI chrome and density — they're the closest
 *     existing analogue to the audit's "Minimal / Standard / Full"
 *     framing without inventing a new pref field.
 *   - A combined-mode info chip identical to Android's "Full brain mode
 *     activated" / "ADHD + F&R" / "Calm + F&R" chips.
 *
 * Reads/writes flow through `ndPreferencesStore.update(uid, patch)`,
 * which optimistically applies locally and pushes to Firestore
 * (`users/{uid}/prefs/nd_prefs` — same path Android writes). The
 * snapshot listener that hydrates `prefs` is mounted by
 * `useFirestoreSync.ts`; no explicit load needed here.
 */
export function NdModesSection() {
  const firebaseUid = useAuthStore((s) => s.firebaseUid);
  const update = useNdPreferencesStore((s) => s.update);
  const { prefs, loaded } = useNdPreferences();

  if (!firebaseUid) {
    return (
      <p className="text-xs text-[var(--color-text-secondary)]">
        Sign in to configure ND-friendly modes.
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

  const patch = (p: Partial<NdPreferences>) => {
    void update(firebaseUid, p);
  };

  const adhdAndFr = prefs.adhdModeEnabled && prefs.focusReleaseModeEnabled;
  const calmAndFr = prefs.calmModeEnabled && prefs.focusReleaseModeEnabled;
  const allThree =
    prefs.adhdModeEnabled &&
    prefs.calmModeEnabled &&
    prefs.focusReleaseModeEnabled;

  return (
    <div className="flex flex-col gap-3 text-sm">
      <p className="text-xs text-[var(--color-text-secondary)]">
        Brain Mode tunes the app to match how your brain works today. Each
        mode flips a coordinated bundle of sub-settings — toggle the parent
        and the sub-settings follow. Sync cross-device with Android.
      </p>

      <ToggleRow
        label="ADHD Mode"
        description="Bigger nudges, completion animations, streak celebrations, and forgiveness-first streaks to help you start and sustain."
        checked={prefs.adhdModeEnabled}
        onChange={(v) => patch({ adhdModeEnabled: v })}
      />
      <ToggleRow
        label="Calm Mode"
        description="Softer animations, muted palette, soft contrast, quiet notifications, and gentler haptics."
        checked={prefs.calmModeEnabled}
        onChange={(v) => patch({ calmModeEnabled: v })}
      />
      <ToggleRow
        label="Focus & Release Mode"
        description="Good-Enough timers, anti-rework guards, and Ship-It celebrations to help you finish without over-polishing."
        checked={prefs.focusReleaseModeEnabled}
        onChange={(v) => patch({ focusReleaseModeEnabled: v })}
      />

      {allThree && (
        <InfoChip text="Full Brain Mode activated — start, sustain, and release." />
      )}
      {adhdAndFr && !allThree && (
        <InfoChip text="ADHD Mode helps you start. Focus & Release helps you finish. They work great together." />
      )}
      {calmAndFr && !allThree && (
        <InfoChip text="Ship-It celebrations will use subtle animations in Calm Mode." />
      )}

      <Divider />

      <SubHeader icon={<Sparkles className="h-4 w-4" />} label="UI Complexity" />
      <p className="text-xs text-[var(--color-text-secondary)]">
        Fine-tune which chrome shows. Toggling Calm Mode flips all five at
        once; you can also adjust them individually.
      </p>
      <ToggleRow
        label="Reduce Animations"
        description="Disable non-essential motion across the app."
        checked={prefs.reduceAnimations}
        onChange={(v) => patch({ reduceAnimations: v })}
      />
      <ToggleRow
        label="Muted Color Palette"
        description="Soften accent and category colors so nothing screams for attention."
        checked={prefs.mutedColorPalette}
        onChange={(v) => patch({ mutedColorPalette: v })}
      />
      <ToggleRow
        label="Soft Contrast"
        description="Lower the contrast on borders, dividers, and supplementary text."
        checked={prefs.softContrast}
        onChange={(v) => patch({ softContrast: v })}
      />
      <ToggleRow
        label="Quiet Mode"
        description="Suppress non-essential notifications and badges."
        checked={prefs.quietMode}
        onChange={(v) => patch({ quietMode: v })}
      />
      <ToggleRow
        label="Reduce Haptics"
        description="Skip subtle vibrations on long-press, swipe, and completion."
        checked={prefs.reduceHaptics}
        onChange={(v) => patch({ reduceHaptics: v })}
      />

      <Divider />

      <SubHeader icon={<Brain className="h-4 w-4" />} label="Forgiveness-First Streaks" />
      <ToggleRow
        label="Forgive the Occasional Miss"
        description="One missed day still counts as part of the streak — matches Android's forgiveness-first behavior."
        checked={prefs.forgivenessStreaks}
        onChange={(v) => patch({ forgivenessStreaks: v })}
      />

      <PreviewChip prefs={prefs} />
    </div>
  );
}

function SubHeader({ icon, label }: { icon: React.ReactNode; label: string }) {
  return (
    <div className="mt-1 flex items-center gap-2 text-[var(--color-text-primary)]">
      <span className="text-[var(--color-accent)]">{icon}</span>
      <h3 className="text-sm font-semibold">{label}</h3>
    </div>
  );
}

function Divider() {
  return <hr className="my-1 border-t border-[var(--color-border)]" />;
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
    <div className="flex items-start justify-between gap-3 py-1.5">
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

function InfoChip({ text }: { text: string }) {
  return (
    <div
      className="flex items-start gap-2 rounded-md bg-[var(--color-accent)]/10 px-3 py-2 text-xs text-[var(--color-text-primary)]"
      role="status"
    >
      <span aria-hidden="true">💡</span>
      <span>{text}</span>
    </div>
  );
}

/**
 * Live summary of the currently active Brain Mode bundle. Mirrors the
 * "preview chip" intent in Android — surfaces what the user can expect
 * to see/feel right now without diving into individual sub-settings.
 */
function PreviewChip({ prefs }: { prefs: NdPreferences }) {
  const active: string[] = [];
  if (prefs.adhdModeEnabled) active.push('ADHD');
  if (prefs.calmModeEnabled) active.push('Calm');
  if (prefs.focusReleaseModeEnabled) active.push('Focus & Release');
  if (prefs.forgivenessStreaks) active.push('Forgiving streaks');

  const summary = active.length === 0 ? 'No ND modes active.' : active.join(' · ');

  return (
    <div
      className="mt-1 flex items-center gap-2 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-xs"
      aria-live="polite"
    >
      <Brain className="h-3.5 w-3.5 text-[var(--color-accent)]" aria-hidden="true" />
      <span className="font-medium text-[var(--color-text-primary)]">
        Currently active:
      </span>
      <span className="text-[var(--color-text-secondary)]">{summary}</span>
    </div>
  );
}
