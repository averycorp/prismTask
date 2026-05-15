/**
 * Ship-It Celebration helpers. Mirrors Android's
 * `domain/usecase/ShipItCelebrationManager.kt` — generates a small
 * celebration when the user completes (or "ships") a task under
 * Focus & Release Mode, with copy varied by trigger type so the
 * acknowledgement matches what the user actually did.
 *
 * Pure helpers — the UI owns the toast / animation surface.
 */
import {
  effectiveCelebrationIntensity,
  shouldFireShipItCelebration,
  type CelebrationIntensity,
  type NdPreferences,
} from '@/api/firestore/ndPreferences';

/**
 * What action just happened — each trigger gets its own copy bank so
 * the celebration matches the user's actual move (normal ship vs.
 * good-enough early release vs. resisting a rework etc.).
 */
export type CelebrationTrigger =
  | 'NORMAL_COMPLETION'
  | 'GOOD_ENOUGH_SHIP'
  | 'RESISTED_REWORK'
  | 'LOCKED_AT_MAX_REVISIONS';

/**
 * Resolved celebration payload — the UI reads `message` for the toast,
 * `intensity` to pick the animation strength (LOW = no animation, just
 * toast; MEDIUM = standard toast + sparkle; HIGH = confetti).
 */
export interface ShipItCelebration {
  trigger: CelebrationTrigger;
  intensity: CelebrationIntensity;
  message: string;
  isStreakMilestone: boolean;
  streakDays: number;
}

const NORMAL_MESSAGES = [
  'Shipped!',
  'Done is beautiful.',
  "That's a wrap.",
  'Out the door.',
  'Progress > perfection.',
] as const;

const GOOD_ENOUGH_MESSAGES = [
  'Beat the clock!',
  'Good enough IS good enough.',
  "Time's up — and so is this task.",
  'Finished, not perfect. Exactly right.',
] as const;

const RESISTED_REWORK_MESSAGES = [
  'Self-control unlocked.',
  "You left it alone. That's growth.",
  'Resisted the urge. Respect.',
  'It was already done. You knew that.',
] as const;

const LOCKED_MESSAGES = [
  'Final version. No take-backs.',
  'Locked and loaded.',
  'The masterpiece is complete.',
  "No more tweaks. It's perfect because it's done.",
] as const;

const MILESTONE_DAYS = new Set([3, 7, 14, 30]);

function pickRandom<T>(items: readonly T[]): T {
  if (items.length === 0) throw new Error('pickRandom: empty list');
  const idx = Math.floor(Math.random() * items.length);
  return items[Math.min(idx, items.length - 1)];
}

function messagesFor(trigger: CelebrationTrigger): readonly string[] {
  switch (trigger) {
    case 'NORMAL_COMPLETION':
      return NORMAL_MESSAGES;
    case 'GOOD_ENOUGH_SHIP':
      return GOOD_ENOUGH_MESSAGES;
    case 'RESISTED_REWORK':
      return RESISTED_REWORK_MESSAGES;
    case 'LOCKED_AT_MAX_REVISIONS':
      return LOCKED_MESSAGES;
  }
}

/**
 * Create a celebration for the given trigger if Focus & Release mode
 * + celebrations are enabled. Returns null if celebrations shouldn't
 * fire — the caller can fall back to the standard completion toast.
 */
export function createShipItCelebration(
  trigger: CelebrationTrigger,
  prefs: NdPreferences,
  releaseStreakDays = 0,
): ShipItCelebration | null {
  if (!shouldFireShipItCelebration(prefs)) return null;

  return {
    trigger,
    intensity: effectiveCelebrationIntensity(prefs),
    message: pickRandom(messagesFor(trigger)),
    isStreakMilestone: MILESTONE_DAYS.has(releaseStreakDays),
    streakDays: releaseStreakDays,
  };
}

/**
 * Whether Ship-It celebrations should pre-empt the ADHD completion
 * celebration path. Mirrors Android's
 * `ShipItCelebrationManager.shouldFireInsteadOfAdhd` — avoids
 * double-firing when both modes are active.
 */
export function shouldFireInsteadOfAdhd(prefs: NdPreferences): boolean {
  return shouldFireShipItCelebration(prefs);
}
