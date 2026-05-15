import { describe, expect, it } from 'vitest';
import {
  buildTaskModeClassifierWithCustomKeywords,
  classifyTaskMode,
} from '@/utils/taskModeClassifier';

// Parity mirror of
// `app/src/test/java/com/averycorp/prismtask/domain/usecase/TaskModeClassifierTest.kt`.
// Cases are ported verbatim so a regression on either platform surfaces
// in CI.

describe('classifyTaskMode', () => {
  it('returns UNCATEGORIZED on empty input', () => {
    expect(classifyTaskMode('')).toBe('UNCATEGORIZED');
    expect(classifyTaskMode('   ')).toBe('UNCATEGORIZED');
  });

  it('classifies WORK from common keywords', () => {
    expect(classifyTaskMode('Send the invoice')).toBe('WORK');
    expect(classifyTaskMode('Finish quarterly report')).toBe('WORK');
  });

  it('classifies PLAY from common keywords', () => {
    expect(classifyTaskMode('Hike with friends')).toBe('PLAY');
    expect(classifyTaskMode('Paint the spare room mural')).toBe('PLAY');
  });

  it('classifies RELAX from common keywords', () => {
    expect(classifyTaskMode('Take a nap')).toBe('RELAX');
    expect(classifyTaskMode('Long bath after dinner')).toBe('RELAX');
  });

  it('returns UNCATEGORIZED on unrelated text', () => {
    expect(classifyTaskMode('Random unrelated string')).toBe('UNCATEGORIZED');
  });

  it('breaks ties in RELAX > PLAY > WORK order', () => {
    // "play" + "rest" both match — tie-break wins for RELAX.
    expect(classifyTaskMode('play and rest')).toBe('RELAX');
    // "play" + "ship" both match — tie-break wins for PLAY (over WORK).
    expect(classifyTaskMode('play and ship')).toBe('PLAY');
  });

  it('includes description in haystack', () => {
    expect(
      classifyTaskMode('After-work activity', 'lie down and breathe'),
    ).toBe('RELAX');
  });

  it('is case-insensitive', () => {
    expect(classifyTaskMode('REVIEW the PR')).toBe('WORK');
  });

  it('matches whole words only (not fragments)', () => {
    // "ship" must not match inside "shipment" — verifies the word-boundary
    // path that mirrors Android's isLetterOrDigit gate.
    expect(classifyTaskMode('Track the shipment')).toBe('UNCATEGORIZED');
  });
});

describe('buildTaskModeClassifierWithCustomKeywords', () => {
  it('merges user CSV keywords with the defaults', () => {
    const classify = buildTaskModeClassifierWithCustomKeywords({
      work: 'deck, slides',
      play: 'lego',
    });
    expect(classify('Update the deck')).toBe('WORK');
    expect(classify('Build a Lego set')).toBe('PLAY');
  });

  it('handles empty CSV gracefully', () => {
    const classify = buildTaskModeClassifierWithCustomKeywords({ work: '' });
    expect(classify('Send the invoice')).toBe('WORK');
  });
});
