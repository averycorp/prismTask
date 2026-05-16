import { describe, it, expect } from 'vitest';
import { reorderedTagIds, sortTagsForDisplay } from '../tagSortHelpers';
import type { Tag } from '@/types/tag';

function tag(overrides: Partial<Tag> & { id: string }): Tag {
  return {
    user_id: 'u1',
    name: overrides.name ?? overrides.id,
    color: '#6B7280',
    sort_order: 0,
    archived: false,
    created_at: '2026-01-01T00:00:00.000Z',
    ...overrides,
  };
}

describe('sortTagsForDisplay', () => {
  it('orders by sort_order ascending when present', () => {
    const tags: Tag[] = [
      tag({ id: 'a', sort_order: 300 }),
      tag({ id: 'b', sort_order: 100 }),
      tag({ id: 'c', sort_order: 200 }),
    ];
    expect(sortTagsForDisplay(tags).map((t) => t.id)).toEqual(['b', 'c', 'a']);
  });

  it('falls back to created_at desc for ties', () => {
    const tags: Tag[] = [
      tag({ id: 'old', sort_order: 0, created_at: '2026-01-01T00:00:00.000Z' }),
      tag({ id: 'new', sort_order: 0, created_at: '2026-02-01T00:00:00.000Z' }),
    ];
    expect(sortTagsForDisplay(tags).map((t) => t.id)).toEqual(['new', 'old']);
  });

  it('does not mutate the input array', () => {
    const tags: Tag[] = [
      tag({ id: 'a', sort_order: 200 }),
      tag({ id: 'b', sort_order: 100 }),
    ];
    const original = tags.map((t) => t.id);
    sortTagsForDisplay(tags);
    expect(tags.map((t) => t.id)).toEqual(original);
  });
});

describe('reorderedTagIds', () => {
  const tags: Tag[] = ['a', 'b', 'c', 'd'].map((id) => tag({ id }));

  it('moves item forwards', () => {
    expect(reorderedTagIds(tags, 0, 2)).toEqual(['b', 'c', 'a', 'd']);
  });

  it('moves item backwards', () => {
    expect(reorderedTagIds(tags, 3, 0)).toEqual(['d', 'a', 'b', 'c']);
  });

  it('returns identity for same index', () => {
    expect(reorderedTagIds(tags, 1, 1)).toEqual(['a', 'b', 'c', 'd']);
  });

  it('returns identity for out-of-range indices', () => {
    expect(reorderedTagIds(tags, -1, 2)).toEqual(['a', 'b', 'c', 'd']);
    expect(reorderedTagIds(tags, 0, 9)).toEqual(['a', 'b', 'c', 'd']);
  });

  it('handles empty list safely', () => {
    expect(reorderedTagIds([], 0, 0)).toEqual([]);
  });
});
