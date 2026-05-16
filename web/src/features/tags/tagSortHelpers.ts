import type { Tag } from '@/types/tag';

/**
 * Visual-order sort for tags shown in the Tag Management list.
 *
 * Falls back to `created_at` descending for any tag that lacks an
 * explicit `sort_order` (i.e. legacy rows written by the pre-reorder
 * Android client or older web builds), preserving the previous default
 * ordering while honouring user-defined positions when they exist.
 */
export function sortTagsForDisplay(tags: Tag[]): Tag[] {
  return [...tags].sort((a, b) => {
    const aOrder = a.sort_order || 0;
    const bOrder = b.sort_order || 0;
    if (aOrder !== bOrder) return aOrder - bOrder;
    // Newer first as the historical default.
    return b.created_at.localeCompare(a.created_at);
  });
}

/**
 * Pure reorder helper: given a tag list and the drag start/end indices,
 * return the new ordered ID list to persist via `reorderTags`.
 *
 * Extracted so the Tag Management drag handler can be unit-tested
 * without a DOM + dnd-kit harness.
 */
export function reorderedTagIds(
  tags: Tag[],
  fromIndex: number,
  toIndex: number,
): string[] {
  if (fromIndex === toIndex) return tags.map((t) => t.id);
  if (fromIndex < 0 || toIndex < 0) return tags.map((t) => t.id);
  if (fromIndex >= tags.length || toIndex >= tags.length) {
    return tags.map((t) => t.id);
  }
  const next = [...tags];
  const [moved] = next.splice(fromIndex, 1);
  next.splice(toIndex, 0, moved);
  return next.map((t) => t.id);
}
