import { describe, it, expect } from 'vitest';
import { detectMultiCreate } from '@/utils/multiCreateDetector';

/**
 * Adversarial coverage for `detectMultiCreate` (parity B.9). Mirrors
 * Android `MultiCreateDetectorTest`: the load-bearing trap is
 * `buy milk, eggs, bread` — three comma-separated segments, all
 * task-shaped by length, that the user wants treated as a SINGLE task.
 * The rule clears that case by requiring >=50% of segments to carry a
 * recognized time marker.
 */
describe('detectMultiCreate', () => {
  // ----------------------------------------------------------------
  // Adversarial false-positives the rule must NOT match
  // ----------------------------------------------------------------

  it('single task with no punctuation stays not-multi', () => {
    expect(detectMultiCreate('pick up groceries 5pm').kind).toBe('not-multi');
  });

  it('two segments with a continuation conjunction stays not-multi', () => {
    // 2 segments fails the >=3 rule even with continuation conjunction.
    expect(detectMultiCreate('email Bob, then call Mary').kind).toBe(
      'not-multi',
    );
  });

  it('parenthetical with continuation stays not-multi', () => {
    expect(
      detectMultiCreate('finish report (the long one), or skip if blocked')
        .kind,
    ).toBe('not-multi');
  });

  it('three nouns without time markers stays not-multi (load-bearing trap)', () => {
    // The load-bearing trap. 3 segments, all task-shaped, but zero
    // time markers — must remain a single task.
    expect(detectMultiCreate('buy milk, eggs, bread').kind).toBe('not-multi');
  });

  it('four segments below 50% markers stays not-multi', () => {
    // 4 segments, only 1 has a marker (25%) — fails the >=50% rule.
    expect(
      detectMultiCreate('buy milk, eggs, bread, ice cream tomorrow').kind,
    ).toBe('not-multi');
  });

  it('newline with leading continuation conjunction stays not-multi', () => {
    // Two lines but the second starts with a continuation conjunction —
    // the user is writing a continuation, not a second task.
    expect(
      detectMultiCreate('email Bob today\nor skip if Bob unavailable').kind,
    ).toBe('not-multi');
  });

  // ----------------------------------------------------------------
  // True-positives the rule MUST match
  // ----------------------------------------------------------------

  it('three comma-separated segments all with markers is multi-create', () => {
    const result = detectMultiCreate(
      'pick up groceries 5pm, call mom tomorrow, finish report by Friday',
    );
    expect(result.kind).toBe('multi-create');
    if (result.kind === 'multi-create') {
      expect(result.segments).toHaveLength(3);
    }
  });

  it('two newline-separated lines is multi-create', () => {
    const result = detectMultiCreate('email Bob today\ncall Mary tomorrow');
    expect(result.kind).toBe('multi-create');
    if (result.kind === 'multi-create') {
      expect(result.segments).toHaveLength(2);
    }
  });

  it('three newline-separated lines is multi-create', () => {
    const result = detectMultiCreate(
      'email Bob today\ncall Mary tomorrow\nwrite notes Friday',
    );
    expect(result.kind).toBe('multi-create');
    if (result.kind === 'multi-create') {
      expect(result.segments).toHaveLength(3);
    }
  });

  it('mixed comma+newline: newlines win', () => {
    // Newline wins over comma. The first line itself contains
    // commas but the detector accepts it as one valid segment.
    const result = detectMultiCreate(
      'email Bob, then call Mary today\nwrite notes Friday',
    );
    expect(result.kind).toBe('multi-create');
  });

  it('lines with leading whitespace still count', () => {
    // Trim is applied per-segment; indented lines still count.
    const result = detectMultiCreate(
      '  email Bob today\n  call Mary tomorrow',
    );
    expect(result.kind).toBe('multi-create');
  });

  it('three segments with clock times is multi-create', () => {
    // "5pm", "9:30", "10am" — all caught by the time-marker regex.
    const result = detectMultiCreate(
      'review PR at 5pm, standup at 9:30, lunch at 10am',
    );
    expect(result.kind).toBe('multi-create');
  });

  // ----------------------------------------------------------------
  // Sanity / boundary
  // ----------------------------------------------------------------

  it('empty input is not-multi', () => {
    expect(detectMultiCreate('').kind).toBe('not-multi');
    expect(detectMultiCreate('   ').kind).toBe('not-multi');
  });

  it('single newline at end is not-multi', () => {
    // `buy milk\n` is still one task; second "line" is empty.
    expect(detectMultiCreate('buy milk\n').kind).toBe('not-multi');
  });
});
