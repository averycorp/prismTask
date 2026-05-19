import { BUILT_IN_TEMPLATE_KEYS } from '@/data/builtInHabitTemplates';
import type { Habit } from '@/types/habit';

/**
 * Names of the six "meta-habit" rows Android keeps in Room but excludes
 * from the regular Habits list. Each one is surfaced elsewhere — as a
 * Self-Care / Schoolwork / Leisure / Daily-Essentials card on Today, or
 * as a top-level Medication destination. Mirrors the literal constants
 * in `SelfCareRepository.kt`, `SchoolworkRepository.kt`, and
 * `LeisureBudgetRepository.kt`; Android's filter is at
 * `HabitListViewModel.kt:213-221`.
 */
const META_HABIT_NAMES: ReadonlySet<string> = new Set([
  'Morning Self-Care',
  'Bedtime Self-Care',
  'Medication',
  'Housework',
  'School',
  'Leisure',
]);

const META_TEMPLATE_KEYS: ReadonlySet<string> = new Set([
  BUILT_IN_TEMPLATE_KEYS.MORNING_SELFCARE,
  BUILT_IN_TEMPLATE_KEYS.BEDTIME_SELFCARE,
  BUILT_IN_TEMPLATE_KEYS.MEDICATION,
  BUILT_IN_TEMPLATE_KEYS.HOUSEWORK,
  BUILT_IN_TEMPLATE_KEYS.SCHOOL,
  BUILT_IN_TEMPLATE_KEYS.LEISURE,
]);

/**
 * True when the habit is one of the six built-in meta-habits that should
 * be hidden from every regular habit list (Habits screen, Today habit
 * section, done-counter sheet, weekly summary, today-progress count).
 *
 * Match order:
 *  1. `template_key` is the canonical built-in identity — both Android
 *     and the web reconciler write it. Catches habits a user has
 *     renamed.
 *  2. `is_built_in === true && name === <meta name>` covers legacy rows
 *     written before the `templateKey` column existed (parity audit §
 *     B.4).
 *
 * User-created habits that happen to share a meta name (e.g. a custom
 * habit literally named "Leisure" with `is_built_in === false`) are
 * deliberately left alone — that's the user's habit, not the system
 * built-in.
 */
export function isMetaBuiltInHabit(habit: Habit): boolean {
  if (habit.template_key && META_TEMPLATE_KEYS.has(habit.template_key)) {
    return true;
  }
  return habit.is_built_in === true && META_HABIT_NAMES.has(habit.name);
}
