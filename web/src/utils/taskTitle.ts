import { parseQuickAdd } from './nlp';

/**
 * Maximum task-title length surfaced in the quick-add preview. The cap is
 * enforced *visibly* — the title inputs set `maxLength` and render a live
 * `NN/100` counter — so a long paste is never silently truncated the way
 * the backend LLM "cleaned up" title used to be (bug B-03).
 */
export const MAX_TASK_TITLE_LENGTH = 100;

export function titleExceedsMax(title: string): boolean {
  return title.length > MAX_TASK_TITLE_LENGTH;
}

/** Clamp a title to {@link MAX_TASK_TITLE_LENGTH}. */
export function clampTitle(title: string): string {
  return title.length > MAX_TASK_TITLE_LENGTH
    ? title.slice(0, MAX_TASK_TITLE_LENGTH)
    : title;
}

/**
 * Derive the title shown in the quick-add confirmation preview.
 *
 * The web quick-add parses via the backend Claude endpoint, which is
 * instructed to return a "cleaned up" title. In practice that cleanup is
 * destructive and non-deterministic: it silently strips `<...>` content
 * (bug B-04) and truncates long titles mid-sentence (bug B-03).
 *
 * To keep what the user typed, we re-derive the title locally with
 * {@link parseQuickAdd} — which strips only the metadata tokens it
 * recognises (`#tags`, `@project`, `!priority`, dates) and otherwise
 * preserves the literal text, including angle-bracket content, at full
 * length. The backend result is still used for the scheduling fields
 * (due date / priority / project) where the LLM is strong. React escapes
 * the value on render, so preserved `<script>` text shows as literal text
 * and never executes.
 *
 * Falls back to the backend title, then the raw input, if the local parse
 * yields an empty title (e.g. the input was nothing but metadata tokens).
 */
export function deriveQuickAddTitle(
  originalText: string,
  parsedTitle?: string | null,
): string {
  const local = parseQuickAdd(originalText).title.trim();
  if (local) return local;
  if (parsedTitle && parsedTitle.trim()) return parsedTitle.trim();
  return originalText.trim();
}
