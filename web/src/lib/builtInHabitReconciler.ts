/**
 * Web parity port of Android's
 * `data/remote/BuiltInHabitReconciler.kt`.
 *
 * Built-in habits seeded on both web and Android can duplicate after
 * cloud sync — the user pulls Android's cloud copy of a habit they
 * already seeded locally, ending up with two rows that share the same
 * `templateKey`. Android collapses them via `BuiltInHabitReconciler`;
 * this module does the same on the web side.
 *
 * Merge rule (matches Android `BuiltInHabitReconciler.kt:75-123`):
 *   1. Group every habit with `is_built_in === true` by `template_key`
 *      (fallback to `name` for legacy rows lacking a templateKey).
 *   2. Within each group >1, pick the keeper as the habit with the most
 *      completions. Tie-broken by earliest `created_at`.
 *   3. Reassign every loser's completions to the keeper (drop dupes on
 *      dates the keeper already has), then delete the loser habit doc.
 *
 * One-shot guard: the heavy initial sweep is gated by the
 * `RECONCILER_FLAG_V1` IndexedDB flag (see `builtInSyncPreferences.ts`).
 * Once the flag is set, subsequent app loads skip the sweep — mirrors
 * Android's `BUILT_INS_RECONCILED` DataStore key.
 *
 * The reconciler does NOT mutate the in-memory habit-store directly:
 * the deletes flow through the Firestore real-time `subscribeToHabits`
 * listener which the store already wires in `useFirestoreSync.ts`. That
 * keeps the store as the single source of truth and matches how every
 * other cross-device mutation propagates on web.
 */

import type { Habit, HabitCompletion } from '@/types/habit';
import { deleteHabit, reassignCompletions } from '@/api/firestore/habits';
import {
  RECONCILER_FLAG_V1,
  isFlagSet,
  setFlag,
} from '@/lib/builtInSyncPreferences';

/**
 * Pure planning function used by the reconciler and by unit tests.
 * Returns the dedup actions to apply without touching Firestore — keeps
 * the side-effecting code in `reconcile(...)` thin and the merge rule
 * isolated for tests.
 */
export interface DedupAction {
  groupKey: string;
  keeperId: string;
  loserIds: string[];
}

export function planBuiltInDedup(
  habits: Habit[],
  completionsByHabit: Record<string, HabitCompletion[]>,
): DedupAction[] {
  const builtIns = habits.filter((h) => h.is_built_in === true);
  if (builtIns.length <= 1) return [];

  // Group by templateKey when present, fall back to name for legacy
  // rows (matches Android's `it.templateKey ?: it.name`).
  const groups = new Map<string, Habit[]>();
  for (const habit of builtIns) {
    const key = habit.template_key ?? habit.name;
    const bucket = groups.get(key);
    if (bucket) bucket.push(habit);
    else groups.set(key, [habit]);
  }

  const actions: DedupAction[] = [];
  for (const [groupKey, group] of groups) {
    if (group.length <= 1) continue;

    // Keeper: most completions. Tie-broken by earliest `created_at` so
    // we prefer the originally-seeded local row when both sides happen
    // to have zero completions.
    const ranked = group
      .map((h) => ({
        habit: h,
        count: (completionsByHabit[h.id] ?? []).reduce(
          (sum, c) => sum + (c.count > 0 ? 1 : 0),
          0,
        ),
        createdAt: h.created_at,
      }))
      .sort((a, b) => {
        if (b.count !== a.count) return b.count - a.count;
        return a.createdAt < b.createdAt ? -1 : 1;
      });

    const keeper = ranked[0].habit;
    const losers = ranked.slice(1).map((r) => r.habit);
    actions.push({
      groupKey,
      keeperId: keeper.id,
      loserIds: losers.map((l) => l.id),
    });
  }
  return actions;
}

/**
 * Side-effecting reconciler. Runs the planning function then, for each
 * dedup action, reassigns loser completions to the keeper and deletes
 * the loser habit docs in Firestore. Guarded by a one-shot IDB flag —
 * subsequent calls under the same `uid` return early.
 *
 * `force === true` skips the flag (used by tests).
 */
export async function reconcileBuiltInHabits(
  uid: string,
  habits: Habit[],
  completionsByHabit: Record<string, HabitCompletion[]>,
  options?: { force?: boolean },
): Promise<DedupAction[]> {
  if (!uid) return [];
  if (!options?.force) {
    const alreadyRan = await isFlagSet(uid, RECONCILER_FLAG_V1);
    if (alreadyRan) return [];
  }

  const actions = planBuiltInDedup(habits, completionsByHabit);
  if (actions.length === 0) {
    // Still mark the flag so the next load skips the sweep — there's
    // nothing to do, but Android's reconciler also flips the flag on a
    // no-op pass (it only retries on exception).
    await setFlag(uid, RECONCILER_FLAG_V1);
    return [];
  }

  for (const action of actions) {
    for (const loserId of action.loserIds) {
      try {
        await reassignCompletions(uid, loserId, action.keeperId);
        await deleteHabit(uid, loserId);
      } catch {
        // Per-loser failure shouldn't take the rest of the sweep
        // down — every other loser still gets a chance. Matches
        // Android's tolerant for-loop in `mergeDuplicateBuiltIns`.
      }
    }
  }

  await setFlag(uid, RECONCILER_FLAG_V1);
  return actions;
}
