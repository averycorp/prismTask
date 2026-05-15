/**
 * Heuristic detector for multi-task QuickAdd input (parity B.9).
 *
 * Mirrors `app/.../domain/usecase/MultiCreateDetector.kt`. Single-task
 * NLP is the default — false positives would route normal users
 * (`buy milk, eggs, bread`) into a heavier preview flow they did not
 * opt into. The detector is deliberately conservative: it requires
 * either an explicit newline-separated list OR a comma-separated list
 * with strong time-marker density.
 *
 * Rules (audit doc Item 2 — clears the named adversarial set):
 *
 *  - **(a) newline rule:** the input contains >=1 newline AND has >=2
 *    non-empty trimmed lines, where each line is >=4 chars and does not
 *    start with a continuation conjunction
 *    (`then|or|and|but|so|because|while|if`). Newlines win over commas:
 *    if rule (a) matches, we never check rule (b).
 *
 *  - **(b) comma rule:** the input contains >=3 comma-separated segments
 *    AND >=50% of segments contain a recognized date/time marker
 *    (`today`, `tonight`, `tomorrow`, weekday names, `this week`,
 *    `next week`, `\d+\s*(am|pm)`, `by <day>`, `\d{1,2}:\d{2}`).
 */

const MIN_SEGMENT_LENGTH = 4;

const CONTINUATION_CONJUNCTIONS = new Set([
  'then',
  'or',
  'and',
  'but',
  'so',
  'because',
  'while',
  'if',
]);

const TIME_MARKER_REGEX = new RegExp(
  '\\b(?:' +
    // Single-word tokens.
    'today|tonight|tomorrow|' +
    'monday|tuesday|wednesday|thursday|friday|saturday|sunday|' +
    // Multi-word horizons.
    'this\\s+week|next\\s+week|' +
    // "by monday", "by next week", "by tomorrow".
    'by\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|next\\s+week|tomorrow)|' +
    // Standalone clock times: "5pm", "5 pm", "5:30", "17:00".
    '\\d{1,2}\\s*(?:am|pm)|' +
    '\\d{1,2}:\\d{2}' +
    ')\\b',
  'i',
);

export type MultiCreateResult =
  | { kind: 'not-multi' }
  | { kind: 'multi-create'; rawText: string; segments: string[] };

function isValidNewlineSegment(line: string): boolean {
  if (line.length < MIN_SEGMENT_LENGTH) return false;
  const firstWord = line.split(' ', 1)[0].toLowerCase();
  return !CONTINUATION_CONJUNCTIONS.has(firstWord);
}

function hasTimeMarker(segment: string): boolean {
  return TIME_MARKER_REGEX.test(segment);
}

/**
 * Detect whether [rawText] should be routed into the multi-task creation
 * flow. See module docstring for the rule shape; tests in
 * `__tests__/multiCreateDetector.test.ts` carry the adversarial truth
 * table.
 */
export function detectMultiCreate(rawText: string): MultiCreateResult {
  const text = rawText.trim();
  if (text.length === 0) return { kind: 'not-multi' };

  // Rule (a): newlines win over commas. If we see >=2 valid lines,
  // emit multi-create and skip rule (b).
  if (text.includes('\n')) {
    const lines = text.split('\n').map((s) => s.trim());
    const candidateLines = lines.filter((s) => s.length > 0);
    const acceptedLines = candidateLines.filter(isValidNewlineSegment);
    if (acceptedLines.length >= 2) {
      return { kind: 'multi-create', rawText: text, segments: acceptedLines };
    }
    // Newline present but didn't pass rule (a) — fall through to
    // rule (b). A user typing `buy milk\n` (one line then enter)
    // is still a single task.
  }

  // Rule (b): >=3 comma segments AND >=50% have a time marker.
  if (text.includes(',')) {
    const segments = text
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    if (segments.length >= 3) {
      const withMarker = segments.filter(hasTimeMarker).length;
      // Strict majority: a 3-segment list needs 2 markers, a
      // 4-segment list needs 2, a 5-segment list needs 3, etc.
      if (withMarker * 2 >= segments.length) {
        return { kind: 'multi-create', rawText: text, segments };
      }
    }
  }

  return { kind: 'not-multi' };
}
