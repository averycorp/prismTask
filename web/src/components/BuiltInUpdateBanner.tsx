/**
 * Built-in habit version-update banner.
 *
 * Web parity port of Android's `BuiltInUpdatesScreen.kt`. When the
 * starter habit-template library ships a newer `source_version` than
 * the user's instance, the banner surfaces "Update Available — ‹habit›"
 * with Apply / Dismiss actions.
 *
 * The component is intentionally a soft fallback today: web does NOT
 * yet ship a versioned starter library (`starterLibrary.ts` has no
 * `version` field on `HabitTemplate`), and the per-habit
 * `source_version` field on `Habit` is read-only / written only by the
 * Android reconciler. Until unit 2 lands the versioned `habit_templates`
 * store, the banner returns `null` — wiring it now avoids a follow-up
 * rewrite once that data shape arrives.
 *
 * Dismissal is per-device, in-memory (component state). Persistent
 * per-(templateKey, version) dismissal lives behind Android's
 * `DISMISSED_BUILT_IN_UPDATES` set; the web equivalent is tracked
 * alongside unit 2's `habit_templates` rollout.
 */

import { useMemo, useState } from 'react';
import { useHabitStore } from '@/stores/habitStore';
import { Button } from '@/components/ui/Button';

/**
 * Minimal shape the banner reads off the habit store / test override.
 * Subset of `Habit` from `@/types/habit` — declared inline so the
 * `habitsOverride` test prop and `computePendingUpdates` agree on
 * exactly which fields are read.
 */
export interface BuiltInHabitForBanner {
  id: string;
  name: string;
  is_built_in?: boolean;
  template_key?: string | null;
  is_detached_from_template?: boolean;
  source_version?: number;
}

interface PendingUpdate {
  templateKey: string;
  habitId: string;
  habitName: string;
  fromVersion: number;
  toVersion: number;
}

/**
 * Returns the list of pending built-in updates by comparing each
 * built-in habit's `source_version` against a "latest templates" map.
 * Exported for tests.
 */
export function computePendingUpdates(
  habits: BuiltInHabitForBanner[],
  latestVersions: Record<string, number>,
): PendingUpdate[] {
  const pending: PendingUpdate[] = [];
  for (const habit of habits) {
    if (habit.is_built_in !== true) continue;
    if (habit.is_detached_from_template === true) continue;
    const key = habit.template_key;
    if (!key) continue;
    const latest = latestVersions[key];
    if (typeof latest !== 'number') continue;
    const current = habit.source_version ?? 0;
    if (latest > current) {
      pending.push({
        templateKey: key,
        habitId: habit.id,
        habitName: habit.name,
        fromVersion: current,
        toVersion: latest,
      });
    }
  }
  return pending;
}

export interface BuiltInUpdateBannerProps {
  /**
   * Optional map of `templateKey → latest version`, sourced from the
   * Firestore `habit_templates` store (wired by unit 2). When the prop
   * is unset or empty, the banner soft-hides because there's no
   * version metadata to compare against.
   */
  latestVersions?: Record<string, number>;
  /**
   * Override used by tests to bypass the habit-store hook. Production
   * callers omit this and the banner pulls habits from the store.
   */
  habitsOverride?: BuiltInHabitForBanner[];
  /**
   * Optional callback fired when the user taps "Apply Update". The
   * default behaviour (no callback) is a no-op — the actual apply path
   * lives on Android until the web-side template-apply lands.
   */
  onApply?: (update: PendingUpdate) => void;
}

export function BuiltInUpdateBanner({
  latestVersions,
  habitsOverride,
  onApply,
}: BuiltInUpdateBannerProps) {
  const storeHabits = useHabitStore((s) => s.habits);
  const habits = habitsOverride ?? storeHabits;
  const [dismissed, setDismissed] = useState<Set<string>>(new Set());

  const pending = useMemo(() => {
    if (!latestVersions || Object.keys(latestVersions).length === 0) {
      return [];
    }
    return computePendingUpdates(habits, latestVersions).filter(
      (u) => !dismissed.has(`${u.templateKey}@${u.toVersion}`),
    );
  }, [habits, latestVersions, dismissed]);

  if (pending.length === 0) return null;
  const next = pending[0];
  const more = pending.length - 1;

  return (
    <div
      role="status"
      aria-live="polite"
      className="mb-3 flex flex-col gap-2 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3 sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="flex-1">
        <div className="text-sm font-semibold text-[var(--color-text-primary)]">
          Update Available: {next.habitName}
        </div>
        <div className="text-xs text-[var(--color-text-secondary)]">
          Built-in Habit · v{next.fromVersion} → v{next.toVersion}
          {more > 0 ? ` · ${more} More` : ''}
        </div>
      </div>
      <div className="flex gap-2">
        <Button
          variant="primary"
          size="sm"
          onClick={() => onApply?.(next)}
          aria-label={`Apply Update for ${next.habitName}`}
        >
          Apply Update
        </Button>
        <Button
          variant="ghost"
          size="sm"
          onClick={() =>
            setDismissed((prev) => {
              const copy = new Set(prev);
              copy.add(`${next.templateKey}@${next.toVersion}`);
              return copy;
            })
          }
          aria-label={`Dismiss Update for ${next.habitName}`}
        >
          Dismiss
        </Button>
      </div>
    </div>
  );
}
