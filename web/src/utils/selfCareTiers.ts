/**
 * Self-care routine tier model. Mirrors Android's
 * `domain/model/SelfCareRoutine.kt` (`SelfCareRoutines` object).
 *
 * Each routine carries an ordered tier list (lowest → highest); a step is
 * visible at the active tier when its own tier sits at or below the active
 * one in the order. `medication.skipped` is intentionally absent from the
 * tier order so it never marks any medication as visible — matches the
 * comment on `medicationTierOrder` in `SelfCareRoutine.kt`.
 *
 * `resolveSelectedTier` picks the active tier for a routine on a given
 * day, with the precedence Android's `DailyEssentialsUseCase` uses:
 *
 *   1. `logTier` from today's `SelfCareLog` if it appears in `tierOrder`.
 *   2. `defaultTier` from the user's `selfcare_default_tier_<routine>`
 *      preference if it appears in `tierOrder`.
 *   3. The penultimate entry in `tierOrder` (the historical implicit
 *      default — e.g. `solid` for morning/bedtime, `regular` for
 *      housework), falling back to the last entry if `tierOrder` has
 *      fewer than two elements.
 *
 * Returns `null` only when `tierOrder` is empty.
 */

const ROUTINE_TIER_ORDER: Readonly<Record<string, readonly string[]>> = {
  morning: ['survival', 'solid', 'full'],
  bedtime: ['survival', 'basic', 'solid', 'full'],
  housework: ['quick', 'regular', 'deep'],
  medication: ['essential', 'prescription', 'complete'],
  workday: ['quick', 'standard', 'deep'],
  winddown: ['light', 'solid', 'full'],
  errands: ['quick', 'regular', 'full'],
};

export function getTierOrder(routineType: string): readonly string[] {
  return ROUTINE_TIER_ORDER[routineType] ?? ROUTINE_TIER_ORDER.bedtime;
}

export function tierIncludes(
  tierOrder: readonly string[],
  activeTier: string,
  stepTier: string,
): boolean {
  const stepIdx = tierOrder.indexOf(stepTier);
  const activeIdx = tierOrder.indexOf(activeTier);
  if (stepIdx === -1 || activeIdx === -1) return false;
  return stepIdx <= activeIdx;
}

export function resolveSelectedTier(
  logTier: string | null | undefined,
  tierOrder: readonly string[],
  defaultTier: string | null | undefined,
): string | null {
  if (logTier && tierOrder.includes(logTier)) return logTier;
  if (tierOrder.length === 0) return null;
  if (defaultTier && tierOrder.includes(defaultTier)) return defaultTier;
  return tierOrder[tierOrder.length - 2] ?? tierOrder[tierOrder.length - 1];
}
