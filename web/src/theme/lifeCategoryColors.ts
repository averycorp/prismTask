import type { LifeCategory } from '@/types/task';

/**
 * Shared LifeCategory palette. Mirrors Android's `LifeCategoryColor.kt`
 * exactly — these tokens are the single source of truth for the Work-Life
 * Balance surfaces (Today balance bar, Weekly Balance Report, Morning
 * check-in summary card).
 *
 * Per Android's palette:
 *   - WORK         #2E7BE5 (blue)
 *   - PERSONAL     #37A669 (green)
 *   - SELF_CARE    #8A4FCF (purple)
 *   - HEALTH       #E05353 (red)
 *   - UNCATEGORIZED #9E9E9E (neutral gray)
 *
 * Keep these in sync with
 *   `app/src/main/java/com/averycorp/prismtask/ui/theme/LifeCategoryColors.kt`.
 */
export const LIFE_CATEGORY_COLOR: Record<LifeCategory, string> = {
  WORK: '#2E7BE5',
  PERSONAL: '#37A669',
  SELF_CARE: '#8A4FCF',
  HEALTH: '#E05353',
  UNCATEGORIZED: '#9E9E9E',
};

/** Title-cased label for each category (matches Android `LifeCategory.label`). */
export const LIFE_CATEGORY_LABEL: Record<LifeCategory, string> = {
  WORK: 'Work',
  PERSONAL: 'Personal',
  SELF_CARE: 'Self-Care',
  HEALTH: 'Health',
  UNCATEGORIZED: 'Uncategorized',
};

/** Returns the Android-matched hex color for a given LifeCategory. */
export function lifeCategoryColor(category: LifeCategory): string {
  return LIFE_CATEGORY_COLOR[category];
}

/** Returns the Title-Cased label for a given LifeCategory. */
export function lifeCategoryLabel(category: LifeCategory): string {
  return LIFE_CATEGORY_LABEL[category];
}
