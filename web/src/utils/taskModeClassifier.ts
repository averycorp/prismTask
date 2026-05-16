import type { TaskMode } from '@/types/task';

/**
 * Fast, offline keyword-based classifier that guesses a TaskMode for a task
 * from its title + optional description. Mirrors Android's
 * `TaskModeClassifier.kt`.
 *
 * Matching rules:
 *  - Case-insensitive.
 *  - The mode with the most keyword hits wins.
 *  - Ties are broken in priority order: RELAX > PLAY > WORK. This is the
 *    "lean toward the restorative read" bias documented in
 *    `docs/WORK_PLAY_RELAX.md` § Inference rules — when the classifier
 *    can't tell, it deliberately does not inflate Work.
 *  - No keyword hits → `UNCATEGORIZED`.
 *
 * Keyword bag is identical to the Android default so the two platforms
 * produce the same output for the same input.
 */

export const DEFAULT_KEYWORDS: Record<Exclude<TaskMode, 'UNCATEGORIZED'>, string[]> = {
  WORK: [
    'ship', 'finish', 'fix', 'send', 'write', 'review',
    'file', 'submit', 'deliver', 'deadline', 'meeting',
    'call', 'invoice', 'email', 'report', 'draft',
    'schedule', 'present', 'prepare', 'plan', 'complete',
  ],
  PLAY: [
    'play', 'game', 'hobby', 'bake', 'climb', 'hike',
    'paint', 'draw', 'watch', 'listen', 'jam', 'dance',
    'visit', 'party', 'picnic', 'brunch',
  ],
  RELAX: [
    'rest', 'nap', 'sleep', 'breathe', 'meditate',
    'stretch', 'soak', 'bath', 'spa', 'sunbathe', 'tea',
    'unwind', 'recover', 'decompress',
  ],
};

/** Stable tie-break order. RELAX first — never inflate to Work. */
const TIE_BREAK_ORDER: TaskMode[] = ['RELAX', 'PLAY', 'WORK'];

export interface TaskModeCustomKeywords {
  work?: string;
  play?: string;
  relax?: string;
}

/**
 * Classify a task's text into Work / Play / Relax, or UNCATEGORIZED when
 * nothing matches.
 */
export function classifyTaskMode(
  title: string,
  description: string | null = null,
  keywords: Record<Exclude<TaskMode, 'UNCATEGORIZED'>, string[]> = DEFAULT_KEYWORDS,
): TaskMode {
  const haystack = (
    title.toLowerCase() + (description ? ' ' + description.toLowerCase() : '')
  ).trim();
  if (!haystack) return 'UNCATEGORIZED';

  const scores: Partial<Record<TaskMode, number>> = {};
  (Object.keys(keywords) as Array<keyof typeof keywords>).forEach((mode) => {
    let hits = 0;
    for (const word of keywords[mode]) {
      if (containsWholeWord(haystack, word.toLowerCase())) hits++;
    }
    if (hits > 0) scores[mode as TaskMode] = hits;
  });
  const entries = Object.entries(scores) as Array<[TaskMode, number]>;
  if (entries.length === 0) return 'UNCATEGORIZED';

  const maxScore = Math.max(...entries.map(([, v]) => v));
  const top = entries.filter(([, v]) => v === maxScore).map(([k]) => k);
  return TIE_BREAK_ORDER.find((m) => top.includes(m)) ?? 'UNCATEGORIZED';
}

/**
 * Word-boundary match: matches "rest" in "take a rest" but not in "wrestle".
 * Keeps multi-word keywords working as a substring (none today, but kept
 * for parity with the Android implementation).
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
 * are dropped. Mirrors Android `TaskModeClassifier.withCustomKeywords`.
 */
export function buildTaskModeClassifierWithCustomKeywords(
  custom: TaskModeCustomKeywords,
): (title: string, description?: string | null) => TaskMode {
  const split = (csv: string | undefined): string[] =>
    (csv ?? '')
      .split(',')
      .map((s) => s.trim().toLowerCase())
      .filter((s) => s.length > 0);
  const merged: typeof DEFAULT_KEYWORDS = {
    WORK: [...DEFAULT_KEYWORDS.WORK, ...split(custom.work)],
    PLAY: [...DEFAULT_KEYWORDS.PLAY, ...split(custom.play)],
    RELAX: [...DEFAULT_KEYWORDS.RELAX, ...split(custom.relax)],
  };
  return (title, description = null) => classifyTaskMode(title, description, merged);
}
