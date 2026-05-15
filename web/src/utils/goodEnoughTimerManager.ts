/**
 * Good-Enough Timer Manager for Pomodoro sessions. Mirrors Android's
 * `domain/usecase/GoodEnoughTimerManager.kt` at capability level —
 * exposes a pure helper that decides whether the user is allowed to
 * end a Pomodoro session early as "good enough".
 *
 * The UX rule: when Focus & Release Mode is on (or Forgiveness Streaks
 * are enabled under ADHD Mode), the user can ship at 70%+ of the
 * scheduled session length without it counting as "abandoned". The
 * existing `goodEnoughTimer.ts` helper handles the 80% unlock for the
 * dedicated Focus Release screen; this manager is the Pomodoro variant
 * (70% threshold, ND-gate aware).
 *
 * Pure / stateless helpers — the component owns the interval.
 */
import type { NdPreferences } from '@/api/firestore/ndPreferences';

/** Default threshold (fraction of planned duration) for Pomodoro "good enough". */
export const POMODORO_GOOD_ENOUGH_THRESHOLD = 0.7;

export interface PomodoroTimerSnapshot {
  plannedSeconds: number;
  elapsedSeconds: number;
}

export interface PomodoroTimerStatus {
  /** 0..1 — fraction of planned duration elapsed (clamped). */
  progressRatio: number;
  /** True once ≥70% has elapsed AND ND prefs allow early-ship. */
  goodEnoughUnlocked: boolean;
  /** True once 100% has elapsed (normal completion). */
  fullyElapsed: boolean;
  /** Seconds remaining until full completion (never negative). */
  remainingSeconds: number;
}

/**
 * Decide whether the user's ND preferences allow ending a Pomodoro
 * session early at the good-enough threshold. Mirrors the Android
 * gate `focusReleaseModeEnabled && goodEnoughTimersEnabled` — also
 * unlocked under ADHD-mode forgiveness streaks since that toggle
 * embodies the same "partial credit" philosophy.
 */
export function isGoodEnoughEnabled(prefs: NdPreferences): boolean {
  if (prefs.focusReleaseModeEnabled && prefs.goodEnoughTimersEnabled) {
    return true;
  }
  if (prefs.adhdModeEnabled && prefs.forgivenessStreaks) {
    return true;
  }
  return false;
}

/**
 * Compute the timer status for a Pomodoro snapshot under the given
 * ND preferences. `goodEnoughUnlocked` requires both the threshold AND
 * the ND gate; once the planned duration is fully elapsed the unlock is
 * implicit (the timer is already done).
 */
export function computePomodoroTimerStatus(
  snapshot: PomodoroTimerSnapshot,
  prefs: NdPreferences,
  threshold: number = POMODORO_GOOD_ENOUGH_THRESHOLD,
): PomodoroTimerStatus {
  const planned = Math.max(0, snapshot.plannedSeconds);
  const elapsed = Math.max(0, snapshot.elapsedSeconds);
  const ratio = planned === 0 ? 1 : Math.min(1, elapsed / planned);
  const clampedThreshold = Math.max(0, Math.min(1, threshold));
  const fullyElapsed = ratio >= 1;
  const ndAllows = isGoodEnoughEnabled(prefs);
  const goodEnoughUnlocked = fullyElapsed || (ndAllows && ratio >= clampedThreshold);
  return {
    progressRatio: ratio,
    goodEnoughUnlocked,
    fullyElapsed,
    remainingSeconds: Math.max(0, planned - elapsed),
  };
}

/**
 * Human-readable message for the "you can ship now" affordance —
 * mirrors Android's nudge copy. The UI surfaces this when the user
 * crosses the threshold so they know the early-end button is live.
 */
export function goodEnoughUnlockMessage(elapsedMinutes: number): string {
  const safeMinutes = Math.max(0, Math.floor(elapsedMinutes));
  return `You've put in ${safeMinutes} minute${safeMinutes === 1 ? '' : 's'} — good enough is unlocked.`;
}
