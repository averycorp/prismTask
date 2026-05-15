import type { CognitiveLoad } from '@/types/task';

/**
 * Fast, offline keyword-based classifier that guesses a CognitiveLoad for a
 * task from its title + optional description. Mirrors Android's
 * `CognitiveLoadClassifier.kt` (parity audit § Phase 2 #3 — WEB_PILLARS_PHILOSOPHY_AUDIT).
 *
 * Matching rules:
 *  - Case-insensitive.
 *  - The load with the most keyword hits wins.
 *  - Ties are broken in priority order: EASY > MEDIUM > HARD. This is the
 *    "never inflate difficulty" bias documented in
 *    `docs/COGNITIVE_LOAD.md` § Inference rules — over-classifying as HARD
 *    triggers procrastination preemptively.
 *  - No keyword hits → `UNCATEGORIZED`.
 *
 * Keyword bag is identical to the Android default so the two platforms
 * produce the same output for the same input.
 */

export const DEFAULT_KEYWORDS: Record<Exclude<CognitiveLoad, 'UNCATEGORIZED'>, string[]> = {
  EASY: [
    'quick', 'brief', 'simple', 'reply', 'confirm', 'check',
    'glance', 'skim', 'archive', 'clean', 'tidy', 'clear',
    'sort', 'dust', 'water', 'refill', 'restock', 'trivial',
  ],
  MEDIUM: [
    'review', 'edit', 'compose', 'draft', 'organize', 'prepare',
    'schedule', 'book', 'register', 'log', 'summarize',
    'transcribe', 'tidy-up', 'follow-up',
  ],
  HARD: [
    'start', 'create', 'build', 'design', 'research', 'decide',
    'negotiate', 'confront', 'debug', 'refactor', 'investigate',
    'diagnose', 'present', 'interview', 'rewrite', 'difficult',
    'tough', 'blocker', 'refuse', 'complicated',
  ],
};

/** Stable tie-break order. EASY first — never inflate difficulty. */
const TIE_BREAK_ORDER: CognitiveLoad[] = ['EASY', 'MEDIUM', 'HARD'];

export interface CognitiveLoadCustomKeywords {
  easy?: string;
  medium?: string;
  hard?: string;
}

/**
 * Classify a task's text into Easy / Medium / Hard, or UNCATEGORIZED when
 * nothing matches.
 */
export function classifyCognitiveLoad(
  title: string,
  description: string | null = null,
  keywords: Record<Exclude<CognitiveLoad, 'UNCATEGORIZED'>, string[]> = DEFAULT_KEYWORDS,
): CognitiveLoad {
  const haystack = (
    title.toLowerCase() + (description ? ' ' + description.toLowerCase() : '')
  ).trim();
  if (!haystack) return 'UNCATEGORIZED';

  const scores: Partial<Record<CognitiveLoad, number>> = {};
  (Object.keys(keywords) as Array<keyof typeof keywords>).forEach((load) => {
    let hits = 0;
    for (const word of keywords[load]) {
      if (containsWholeWord(haystack, word.toLowerCase())) hits++;
    }
    if (hits > 0) scores[load as CognitiveLoad] = hits;
  });
  const entries = Object.entries(scores) as Array<[CognitiveLoad, number]>;
  if (entries.length === 0) return 'UNCATEGORIZED';

  const maxScore = Math.max(...entries.map(([, v]) => v));
  const top = entries.filter(([, v]) => v === maxScore).map(([k]) => k);
  return TIE_BREAK_ORDER.find((l) => top.includes(l)) ?? 'UNCATEGORIZED';
}

/**
 * Word-boundary match: matches "review" in "Review PR" but not in
 * "previewing". Hyphens are word boundaries (per isAlnum), so hyphenated
 * keywords like "tidy-up" and "follow-up" are matched correctly by the
 * boundary path. Multi-word keywords fall through to substring search.
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
 * are dropped. Mirrors Android `CognitiveLoadClassifier.withCustomKeywords`.
 */
export function buildCognitiveLoadClassifierWithCustomKeywords(
  custom: CognitiveLoadCustomKeywords,
): (title: string, description?: string | null) => CognitiveLoad {
  const split = (csv: string | undefined): string[] =>
    (csv ?? '')
      .split(',')
      .map((s) => s.trim().toLowerCase())
      .filter((s) => s.length > 0);
  const merged: typeof DEFAULT_KEYWORDS = {
    EASY: [...DEFAULT_KEYWORDS.EASY, ...split(custom.easy)],
    MEDIUM: [...DEFAULT_KEYWORDS.MEDIUM, ...split(custom.medium)],
    HARD: [...DEFAULT_KEYWORDS.HARD, ...split(custom.hard)],
  };
  return (title, description = null) => classifyCognitiveLoad(title, description, merged);
}
