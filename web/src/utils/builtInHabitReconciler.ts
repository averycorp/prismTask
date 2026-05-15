import type { Habit } from '@/types/habit';
import {
  allBuiltInDefinitions,
  builtInDefinition,
  builtInVersionFor,
  type BuiltInHabitDefinition,
} from '@/data/builtInHabitTemplates';

/**
 * Web port of Android's `BuiltInHabitReconciler`
 * (`app/.../data/remote/BuiltInHabitReconciler.kt`) + `BuiltInUpdateDetector`
 * (`app/.../domain/usecase/BuiltInUpdateDetector.kt`). Parity audit § B.4
 * (2026-05-13).
 *
 * Three responsibilities:
 *
 *  1. `findDuplicateBuiltIns` — group built-in habits by `template_key` (or
 *     name fallback for legacy rows pre-`source_version`) and identify
 *     duplicates that arose from independent seeding on multiple devices.
 *     Mirrors Android's `mergeDuplicateBuiltIns` group-by logic, scaled
 *     down: web only knows about `habit_completions` totals (no
 *     `sync_metadata` table), so the keeper is the row with the most
 *     completions; loser rows are flagged for deletion. Web has no Room,
 *     so the actual delete/reassign step is owned by the caller — this
 *     utility just returns the plan.
 *
 *  2. `findPendingUpdates` — compare each built-in habit's
 *     `source_version` against the registry's current version. Returns
 *     a list of `PendingBuiltInUpdate` records the banner UI renders.
 *     Skips rows that are detached, already current, or have been
 *     dismissed for the proposed version.
 *
 *  3. Dismiss bookkeeping — `localStorage`-backed mirror of Android's
 *     `BuiltInSyncPreferences.DISMISSED_BUILT_IN_UPDATES`. Per-device,
 *     never synced cross-device (matches Android's intentional
 *     non-sync). Repair-completed flags live in localStorage too
 *     (`prism.builtInRepair.{key}` keys).
 */

const LOCAL_STORAGE_DISMISSED_KEY = 'prism.builtInDismissed';
const LOCAL_STORAGE_REPAIR_PREFIX = 'prism.builtInRepair.';

export const REPAIR_FLAGS = {
  DRIFT_CLEANUP_DONE: 'driftCleanupDone',
  BUILT_INS_RECONCILED: 'builtInsReconciled',
} as const;

type RepairFlag = (typeof REPAIR_FLAGS)[keyof typeof REPAIR_FLAGS];

/**
 * Field-level habit diff between a built-in habit row and the
 * registry's current definition. Mirrors Android's `FieldChange`
 * (`domain/model/TemplateDiff.kt`) restricted to the parent habit
 * row — web has no `self_care_steps` table, so step-level diffs
 * never reach this layer.
 */
export interface HabitFieldChange {
  fieldName: 'name' | 'description' | 'frequency' | 'targetCount' | 'activeDays';
  currentValue: string | null;
  proposedValue: string | null;
  /**
   * True when the habit row carries `is_user_modified = true`. The UI
   * uses this to default the per-field accept checkbox to unchecked
   * so user edits aren't silently overwritten.
   */
  userModified: boolean;
}

/**
 * Lightweight view of "you have a pending update for this template"
 * used by the banner UI. Built from a [HabitTemplateDiff] but does
 * not carry the diff payload so the banner stays cheap.
 */
export interface PendingBuiltInUpdate {
  templateKey: string;
  displayName: string;
  fromVersion: number;
  toVersion: number;
  habitFieldChangeCount: number;
  habitId: string;
}

/**
 * Full diff between a habit row and the registry's proposed shape.
 */
export interface HabitTemplateDiff {
  templateKey: string;
  fromVersion: number;
  toVersion: number;
  habitFieldChanges: HabitFieldChange[];
  habitId: string;
}

/**
 * One group of duplicate built-in habit rows that should be merged.
 * `keeperId` keeps its completions; `loserIds` should be deleted
 * after the caller reassigns their completions to the keeper.
 */
export interface DuplicateBuiltInGroup {
  groupKey: string;
  keeperId: string;
  loserIds: string[];
}

/**
 * Compare a single habit row against its registry definition. Returns
 * `null` when there is nothing to surface: detached, registry doesn't
 * know the key, already at the proposed version (or newer), or every
 * field already matches. Mirrors Android's
 * `BuiltInTemplateDiffer.diff` shape.
 */
export function diffHabitAgainstRegistry(
  habit: Habit,
): HabitTemplateDiff | null {
  if (habit.is_detached_from_template) return null;
  const templateKey = habit.template_key;
  if (!templateKey) return null;
  const proposed = builtInDefinition(templateKey);
  if (!proposed) return null;
  // source_version 0 = pre-versioning rows (Android's same heuristic).
  const fromVersion = Math.max(habit.source_version ?? 0, 1);
  if (fromVersion >= proposed.version) return null;

  const userModified = habit.is_user_modified === true;
  const changes: HabitFieldChange[] = [];

  if (habit.name !== proposed.name) {
    changes.push({
      fieldName: 'name',
      currentValue: habit.name,
      proposedValue: proposed.name,
      userModified,
    });
  }
  if ((habit.description ?? '') !== (proposed.description ?? '')) {
    changes.push({
      fieldName: 'description',
      currentValue: habit.description,
      proposedValue: proposed.description,
      userModified,
    });
  }
  if (habit.frequency !== proposed.frequency) {
    changes.push({
      fieldName: 'frequency',
      currentValue: habit.frequency,
      proposedValue: proposed.frequency,
      userModified,
    });
  }
  if (habit.target_count !== proposed.targetCount) {
    changes.push({
      fieldName: 'targetCount',
      currentValue: String(habit.target_count),
      proposedValue: String(proposed.targetCount),
      userModified,
    });
  }
  // active_days_json on web is the canonical "1,2,3" ISO-day CSV stored
  // as a JSON array. Android stores it as a CSV string. Normalize both
  // sides to a plain CSV string before comparison so the diff doesn't
  // false-positive on representation skew.
  const currentActiveDays = normalizeActiveDays(habit.active_days_json);
  if (currentActiveDays !== proposed.activeDaysCsv) {
    changes.push({
      fieldName: 'activeDays',
      currentValue: currentActiveDays,
      proposedValue: proposed.activeDaysCsv,
      userModified,
    });
  }

  if (changes.length === 0) return null;

  return {
    templateKey,
    fromVersion,
    toVersion: proposed.version,
    habitFieldChanges: changes,
    habitId: habit.id,
  };
}

/**
 * Walk every habit and surface pending built-in template updates.
 * Skips habits where the (templateKey, toVersion) pair has been
 * dismissed in localStorage. Returns the result sorted by display
 * name so the banner ordering is stable across re-renders.
 */
export function findPendingUpdates(habits: Habit[]): PendingBuiltInUpdate[] {
  const dismissed = readDismissedSet();
  const out: PendingBuiltInUpdate[] = [];
  for (const habit of habits) {
    if (!habit.is_built_in) continue;
    const diff = diffHabitAgainstRegistry(habit);
    if (!diff) continue;
    const token = dismissalToken(diff.templateKey, diff.toVersion);
    if (dismissed.has(token)) continue;
    const def = builtInDefinition(diff.templateKey);
    if (!def) continue;
    out.push({
      templateKey: diff.templateKey,
      displayName: def.name,
      fromVersion: diff.fromVersion,
      toVersion: diff.toVersion,
      habitFieldChangeCount: diff.habitFieldChanges.length,
      habitId: habit.id,
    });
  }
  out.sort((a, b) => a.displayName.localeCompare(b.displayName));
  return out;
}

/**
 * Identify groups of duplicate built-in habits. Groups by
 * `template_key` when present, falling back to a normalized name
 * when the row pre-dates the templateKey column. Each group's
 * keeper is the row with the most completions (defensive fallback:
 * the oldest `created_at` if completion counts tie). Single-row
 * groups are dropped — there's nothing to reconcile.
 *
 * The caller is responsible for the actual delete + reassign
 * (web has no DAO layer; deletion happens via
 * `firestoreHabits.deleteHabit` after the caller reassigns any
 * completions).
 */
export function findDuplicateBuiltIns(
  habits: Habit[],
  completionsByHabitId: Record<string, number>,
): DuplicateBuiltInGroup[] {
  const builtIns = habits.filter((h) => h.is_built_in);
  if (builtIns.length <= 1) return [];

  const groups = new Map<string, Habit[]>();
  for (const habit of builtIns) {
    const key = habit.template_key ?? normalizeForGrouping(habit.name);
    const bucket = groups.get(key);
    if (bucket) bucket.push(habit);
    else groups.set(key, [habit]);
  }

  const out: DuplicateBuiltInGroup[] = [];
  for (const [groupKey, members] of groups) {
    if (members.length <= 1) continue;
    const keeper = pickKeeper(members, completionsByHabitId);
    const loserIds = members.filter((m) => m.id !== keeper.id).map((m) => m.id);
    out.push({ groupKey, keeperId: keeper.id, loserIds });
  }
  return out;
}

function pickKeeper(
  members: Habit[],
  completionsByHabitId: Record<string, number>,
): Habit {
  return [...members].sort((a, b) => {
    const aCount = completionsByHabitId[a.id] ?? 0;
    const bCount = completionsByHabitId[b.id] ?? 0;
    if (aCount !== bCount) return bCount - aCount;
    // Tie-break: prefer the row with the lower `created_at` (older =
    // more likely to be the original seed). Matches Android's bias
    // toward the locally-seeded row when neither has completions yet.
    return a.created_at.localeCompare(b.created_at);
  })[0];
}

function normalizeForGrouping(name: string): string {
  return name.trim().toLowerCase();
}

/**
 * Convert a JSON-array active-days payload (web's storage shape)
 * into a CSV string (Android's storage shape) so the two systems
 * can be compared. Null / empty / invalid all yield `""` —
 * matching Android's seed shape for "active every day".
 */
function normalizeActiveDays(json: string | null | undefined): string {
  if (!json) return '';
  try {
    const parsed = JSON.parse(json);
    if (!Array.isArray(parsed)) return '';
    if (parsed.length === 0) return '';
    return [...parsed]
      .filter((d): d is number => typeof d === 'number')
      .sort((a, b) => a - b)
      .join(',');
  } catch {
    return '';
  }
}

// ── Dismissal bookkeeping (localStorage) ──────────────────────

function dismissalToken(templateKey: string, version: number): string {
  return `${templateKey}@${version}`;
}

function readDismissedSet(): Set<string> {
  if (typeof window === 'undefined') return new Set();
  try {
    const raw = window.localStorage.getItem(LOCAL_STORAGE_DISMISSED_KEY);
    if (!raw) return new Set();
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return new Set();
    return new Set(parsed.filter((s): s is string => typeof s === 'string'));
  } catch {
    return new Set();
  }
}

function writeDismissedSet(set: Set<string>): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(
      LOCAL_STORAGE_DISMISSED_KEY,
      JSON.stringify([...set]),
    );
  } catch {
    // localStorage quota / private mode — silently best-effort.
  }
}

/**
 * Record a dismissal for `(templateKey, version)`. The banner won't
 * surface that pair again on this device until the registry bumps
 * the version, at which point the new (templateKey, toVersion+1)
 * pair surfaces fresh.
 */
export function dismissBuiltInUpdate(
  templateKey: string,
  version: number,
): void {
  const set = readDismissedSet();
  set.add(dismissalToken(templateKey, version));
  writeDismissedSet(set);
}

/**
 * Clear every dismissal recorded for `templateKey`. Used when the
 * user explicitly detaches a habit from its template — once detached,
 * any future re-attach should surface every update fresh rather than
 * carrying over the dismissals from a prior life.
 */
export function clearDismissalsFor(templateKey: string): void {
  const set = readDismissedSet();
  const prefix = `${templateKey}@`;
  const filtered = new Set<string>();
  for (const token of set) {
    if (!token.startsWith(prefix)) filtered.add(token);
  }
  writeDismissedSet(filtered);
}

export function isDismissed(templateKey: string, version: number): boolean {
  return readDismissedSet().has(dismissalToken(templateKey, version));
}

// ── Repair-completed flags (localStorage) ─────────────────────

function repairKey(flag: RepairFlag): string {
  return `${LOCAL_STORAGE_REPAIR_PREFIX}${flag}`;
}

export function isRepairDone(flag: RepairFlag): boolean {
  if (typeof window === 'undefined') return false;
  try {
    return window.localStorage.getItem(repairKey(flag)) === 'true';
  } catch {
    return false;
  }
}

export function markRepairDone(flag: RepairFlag): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(repairKey(flag), 'true');
  } catch {
    // localStorage quota / private mode — silently best-effort.
  }
}

// ── Exposed helpers for tests / banner consumers ──────────────

export function builtInRegistryVersionFor(templateKey: string): number {
  return builtInVersionFor(templateKey);
}

export function allBuiltInTemplates(): BuiltInHabitDefinition[] {
  return allBuiltInDefinitions();
}
