import type { LifeCategory } from '@/types/task';

/**
 * Fast, offline keyword-based classifier that guesses a LifeCategory
 * for a task from its title + optional description. Mirrors Android's
 * `LifeCategoryClassifier.kt` (parity audit item C.2a).
 *
 * Matching rules:
 *  - Case-insensitive.
 *  - The category with the most keyword hits wins.
 *  - Ties are broken in a stable priority order: HEALTH > SELF_CARE > WORK > PERSONAL.
 *    (Health wins ties because missing it is the most harmful misclassification.)
 *  - No keyword hits → `UNCATEGORIZED`.
 */

export const DEFAULT_KEYWORDS: Record<Exclude<LifeCategory, 'UNCATEGORIZED'>, string[]> = {
  WORK: [
    'meeting', 'report', 'deadline', 'client', 'project', 'email',
    'presentation', 'sprint', 'deploy', 'review', 'standup', '1:1',
    'pr', 'bug', 'ticket', 'jira', 'invoice',
  ],
  PERSONAL: [
    'grocery', 'clean', 'laundry', 'cook', 'call', 'birthday',
    'errand', 'bank', 'rent', 'bills', 'mail', 'package', 'shopping',
  ],
  SELF_CARE: [
    'yoga', 'meditate', 'meditation', 'exercise', 'walk', 'read',
    'journal', 'rest', 'hobby', 'music', 'stretch', 'nap',
    'self-care', 'self care', 'hike', 'breathe',
  ],
  HEALTH: [
    'medication', 'meds', 'doctor', 'therapy', 'prescription',
    'refill', 'appointment', 'lab', 'pharmacy', 'vitamins',
    'dentist', 'dental', 'checkup', 'physical', 'therapist',
  ],
};

/** Stable tie-break order. HEALTH first because missing it is most harmful. */
const TIE_BREAK_ORDER: LifeCategory[] = ['HEALTH', 'SELF_CARE', 'WORK', 'PERSONAL'];

export interface LifeCategoryCustomKeywords {
  work?: string;
  personal?: string;
  selfCare?: string;
  health?: string;
}

/**
 * Classify a task's text into one of the four tracked LifeCategory values,
 * or UNCATEGORIZED when nothing matches.
 */
export function classifyLifeCategory(
  title: string,
  description: string | null = null,
  keywords: Record<Exclude<LifeCategory, 'UNCATEGORIZED'>, string[]> = DEFAULT_KEYWORDS,
): LifeCategory {
  const haystack = (
    title.toLowerCase() + (description ? ' ' + description.toLowerCase() : '')
  ).trim();
  if (!haystack) return 'UNCATEGORIZED';

  const scores: Partial<Record<LifeCategory, number>> = {};
  (Object.keys(keywords) as Array<keyof typeof keywords>).forEach((cat) => {
    let hits = 0;
    for (const word of keywords[cat]) {
      if (containsWholeWord(haystack, word.toLowerCase())) hits++;
    }
    if (hits > 0) scores[cat as LifeCategory] = hits;
  });
  const entries = Object.entries(scores) as Array<[LifeCategory, number]>;
  if (entries.length === 0) return 'UNCATEGORIZED';

  const maxScore = Math.max(...entries.map(([, v]) => v));
  const top = entries.filter(([, v]) => v === maxScore).map(([k]) => k);
  return TIE_BREAK_ORDER.find((c) => top.includes(c)) ?? 'UNCATEGORIZED';
}

/**
 * Word-boundary match: matches "yoga" in "do yoga" but not in "yogawear".
 * Keeps multi-word keywords like "self care" working as a substring.
 */
function containsWholeWord(haystack: string, needle: string): boolean {
  if (!needle) return false;
  if (needle.includes(' ')) return haystack.includes(needle);
  let idx = 0;
  while (idx <= haystack.length - needle.length) {
    const found = haystack.indexOf(needle, idx);
    if (found < 0) return false;
    const before = found === 0 ? ' ' : haystack[found - 1];
    const afterIdx = found + needle.length;
    const after = afterIdx >= haystack.length ? ' ' : haystack[afterIdx];
    if (!isAlnum(before) && !isAlnum(after)) return true;
    idx = found + 1;
  }
  return false;
}

function isAlnum(ch: string): boolean {
  return /[a-zA-Z0-9]/.test(ch);
}

/**
 * Build a classifier whose keyword lists are DEFAULT_KEYWORDS augmented by
 * user-supplied CSV strings. CSV is trimmed and lowercased; blank entries
 * are dropped.
 */
export function buildClassifierWithCustomKeywords(
  custom: LifeCategoryCustomKeywords,
): (title: string, description?: string | null) => LifeCategory {
  const split = (csv: string | undefined): string[] =>
    (csv ?? '')
      .split(',')
      .map((s) => s.trim().toLowerCase())
      .filter((s) => s.length > 0);
  const merged: typeof DEFAULT_KEYWORDS = {
    WORK: [...DEFAULT_KEYWORDS.WORK, ...split(custom.work)],
    PERSONAL: [...DEFAULT_KEYWORDS.PERSONAL, ...split(custom.personal)],
    SELF_CARE: [...DEFAULT_KEYWORDS.SELF_CARE, ...split(custom.selfCare)],
    HEALTH: [...DEFAULT_KEYWORDS.HEALTH, ...split(custom.health)],
  };
  return (title, description = null) => classifyLifeCategory(title, description, merged);
}
