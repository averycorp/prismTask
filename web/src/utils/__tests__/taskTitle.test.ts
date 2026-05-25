import { describe, it, expect } from 'vitest';
import {
  MAX_TASK_TITLE_LENGTH,
  clampTitle,
  deriveQuickAddTitle,
  titleExceedsMax,
} from '../taskTitle';
import { parseQuickAdd } from '../nlp';

describe('clampTitle / titleExceedsMax (B-03)', () => {
  it('leaves short titles untouched', () => {
    expect(clampTitle('Buy milk')).toBe('Buy milk');
    expect(titleExceedsMax('Buy milk')).toBe(false);
  });

  it('clamps over-long titles to the max length', () => {
    const long = 'x'.repeat(330);
    expect(clampTitle(long)).toHaveLength(MAX_TASK_TITLE_LENGTH);
    expect(titleExceedsMax(long)).toBe(true);
  });

  it('clamps exactly at the boundary', () => {
    const exact = 'y'.repeat(MAX_TASK_TITLE_LENGTH);
    expect(clampTitle(exact)).toBe(exact);
    expect(titleExceedsMax(exact)).toBe(false);
    expect(titleExceedsMax(exact + 'z')).toBe(true);
  });
});

describe('deriveQuickAddTitle (B-03 no silent truncation / B-04 no HTML stripping)', () => {
  it('does NOT silently truncate a long title (caller surfaces the cap)', () => {
    const long =
      'Plan the entire quarterly offsite agenda including travel, lodging, ' +
      'catering, breakout sessions, and the closing dinner reservation for forty people';
    expect(long.length).toBeGreaterThan(MAX_TASK_TITLE_LENGTH);
    const derived = deriveQuickAddTitle(long, 'Plan the entire quarterly offsite');
    // The full text is preserved here; clamping happens visibly at the input
    // (maxLength + counter) / on commit, never silently inside the parser.
    expect(derived).toBe(long);
    expect(derived.length).toBeGreaterThan(MAX_TASK_TITLE_LENGTH);
  });

  it('preserves angle-bracket content instead of stripping it', () => {
    const input = 'QA TEST <b>foo</b> & "quotes" \'apos\'';
    const derived = deriveQuickAddTitle(input, 'QA TEST & "quotes" \'apos\'');
    expect(derived).toContain('<b>foo</b>');
  });

  it('round-trips a <script> payload as literal text (no stripping)', () => {
    const input = 'QA TEST <script>alert(1)</script>';
    const derived = deriveQuickAddTitle(input, 'QA TEST');
    expect(derived).toBe('QA TEST <script>alert(1)</script>');
  });

  it('still strips recognised metadata tokens from the title', () => {
    const derived = deriveQuickAddTitle('Buy milk tomorrow !high #shopping');
    expect(derived).toBe('Buy milk');
  });

  it('falls back to the backend title when local parse is empty', () => {
    const derived = deriveQuickAddTitle('#shopping', 'Shopping run');
    expect(derived).toBe('Shopping run');
  });
});

describe('parseQuickAdd title preservation (B-04)', () => {
  it('keeps <b>foo</b> intact in the parsed title', () => {
    expect(parseQuickAdd('<b>foo</b>').title).toBe('<b>foo</b>');
  });

  it('does not truncate a long plain title', () => {
    const long = 'a long task title '.repeat(20).trim();
    expect(parseQuickAdd(long).title.length).toBe(long.length);
  });
});
