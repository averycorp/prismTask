/**
 * Pure-function self-care nudge selector. Mirrors Android's
 * `SelfCareNudgeEngine.kt` (parity audit C.1e).
 *
 * Inputs:
 *  - `burnoutScore` (0..100; produced by `burnoutScorer.ts`)
 *  - `selfCareRatio` (0..1; from `BalanceTracker` currentRatios)
 *  - `selfCareTarget` (0..1; user-configured)
 *  - `hourOfDay` (0..23)
 *  - `lastShownId` (used to rotate away from the same kind back-to-back)
 *
 * Returns a nudge to show right now, or `null` if neither overload nor a
 * low-self-care signal warrants one.
 */

export type SelfCareNudgeKind =
  | 'rest_break'
  | 'burnout_warning'
  | 'movement'
  | 'wind_down';

export interface SelfCareNudge {
  id: string;
  kind: SelfCareNudgeKind;
  message: string;
}

const BURNOUT_NUDGE_THRESHOLD = 50;
const LOW_SELF_CARE_BUFFER = 0.1;

export function selectSelfCareNudge(opts: {
  burnoutScore: number;
  selfCareRatio: number;
  selfCareTarget: number;
  hourOfDay: number;
  lastShownId?: string | null;
}): SelfCareNudge | null {
  const { burnoutScore, selfCareRatio, selfCareTarget, hourOfDay, lastShownId } = opts;

  const ratioBelowTarget = selfCareRatio < selfCareTarget - LOW_SELF_CARE_BUFFER;
  const burnoutElevated = burnoutScore > BURNOUT_NUDGE_THRESHOLD;
  if (!ratioBelowTarget && !burnoutElevated) return null;

  const candidates: SelfCareNudge[] = [];

  candidates.push({
    id: 'rest_break',
    kind: 'rest_break',
    message:
      "You haven't logged any self-care today — how about a 15-minute break?",
  });
  if (burnoutElevated) {
    candidates.push({
      id: 'burnout_warning',
      kind: 'burnout_warning',
      message:
        'Your burnout score is rising. Block 30 minutes for something you enjoy.',
    });
  }
  candidates.push({
    id: 'movement',
    kind: 'movement',
    message: 'Movement reminder: a short walk can reset your focus.',
  });
  if (hourOfDay >= 18) {
    candidates.push({
      id: 'wind_down',
      kind: 'wind_down',
      message:
        'Wind-down suggestion: consider stopping work tasks for the evening.',
    });
  }

  // Rotate away from the same id back-to-back.
  const filtered = candidates.filter((c) => c.id !== lastShownId);
  return filtered[0] ?? candidates[0] ?? null;
}
