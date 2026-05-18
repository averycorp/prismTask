import { BUILT_IN_TEMPLATE_KEYS } from '@/data/builtInHabitTemplates';
import type { Habit } from '@/types/habit';

/**
 * The legacy "Medication" built-in habit was promoted to its own top-level
 * destination on Android in v1.6 (`MedicationScreen`). Android's
 * `HabitListViewModel` filters this row out of every habit-list surface so
 * users don't see Medication double-counted as a habit. Web previously
 * missed this filter, so the row leaked onto the Habits screen, the Today
 * habit section, the done-counter sheet, and the today-progress count.
 *
 * The predicate matches on `template_key === 'builtin_medication'` first
 * (canonical key written by both Android and web reconcilers), and falls
 * back to `is_built_in === true && name === 'Medication'` for legacy rows
 * that were seeded before the `templateKey` column existed (parity audit
 * § B.4). User-created habits literally named "Medication" without the
 * built-in flag are left alone — that's a user choice, not the system
 * built-in.
 */
export function isMedicationBuiltInHabit(habit: Habit): boolean {
  if (habit.template_key === BUILT_IN_TEMPLATE_KEYS.MEDICATION) return true;
  return habit.is_built_in === true && habit.name === 'Medication';
}
