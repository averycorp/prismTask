import { describe, expect, it } from 'vitest';
import {
  buildCognitiveLoadClassifierWithCustomKeywords,
  classifyCognitiveLoad,
} from '@/utils/cognitiveLoadClassifier';

// Parity mirror of
// `app/src/test/java/com/averycorp/prismtask/domain/usecase/CognitiveLoadClassifierTest.kt`.
// Cases are ported verbatim so a regression on either platform surfaces
// in CI.

describe('classifyCognitiveLoad', () => {
  it('returns UNCATEGORIZED on empty input', () => {
    expect(classifyCognitiveLoad('')).toBe('UNCATEGORIZED');
    expect(classifyCognitiveLoad('   ')).toBe('UNCATEGORIZED');
  });

  it('classifies EASY from common keywords', () => {
    expect(classifyCognitiveLoad('Quick reply to mom')).toBe('EASY');
    expect(classifyCognitiveLoad("Archive yesterday's drafts")).toBe('EASY');
  });

  it('classifies MEDIUM from common keywords', () => {
    expect(classifyCognitiveLoad('Review the PR comments')).toBe('MEDIUM');
    expect(classifyCognitiveLoad('Compose the standup notes')).toBe('MEDIUM');
  });

  it('classifies HARD from common keywords', () => {
    expect(classifyCognitiveLoad('Start the recommendation letter')).toBe('HARD');
    expect(classifyCognitiveLoad('Debug the Firestore listener leak')).toBe(
      'HARD',
    );
  });

  it('returns UNCATEGORIZED on unrelated text', () => {
    expect(classifyCognitiveLoad('Random unrelated string')).toBe(
      'UNCATEGORIZED',
    );
  });

  it('breaks ties in EASY > MEDIUM > HARD order', () => {
    // "quick" + "review" both match — tie-break wins for EASY (over MEDIUM).
    expect(classifyCognitiveLoad('quick review')).toBe('EASY');
    // "review" + "start" both match — tie-break wins for MEDIUM (over HARD).
    expect(classifyCognitiveLoad('review and start')).toBe('MEDIUM');
  });

  it('includes description in haystack', () => {
    expect(
      classifyCognitiveLoad("Tomorrow's blocker", 'investigate the regression'),
    ).toBe('HARD');
  });

  it('is case-insensitive', () => {
    expect(classifyCognitiveLoad('REVIEW the PR')).toBe('MEDIUM');
  });

  it('matches hyphenated keywords as whole words', () => {
    // "follow-up" must match through the boundary path (hyphen is not
    // alphanumeric, so it's treated as a word boundary just like a space).
    // Mirrors Android's `isLetterOrDigit` gate.
    expect(classifyCognitiveLoad('Follow-up with Sam')).toBe('MEDIUM');
  });

  it('matches whole words only (not fragments)', () => {
    // "start" must not match inside "starts" via boundary (s is alnum
    // after), but in "start it" it should match. Verifies the
    // isLetterOrDigit gate.
    expect(classifyCognitiveLoad('restart later')).toBe('UNCATEGORIZED');
  });
});

describe('buildCognitiveLoadClassifierWithCustomKeywords', () => {
  it('merges user CSV keywords with the defaults', () => {
    const classify = buildCognitiveLoadClassifierWithCustomKeywords({
      easy: 'ack, ok',
      hard: 'spike',
    });
    expect(classify('Ack the email')).toBe('EASY');
    expect(classify('Spike on the new framework')).toBe('HARD');
  });

  it('handles empty CSV gracefully', () => {
    const classify = buildCognitiveLoadClassifierWithCustomKeywords({
      easy: '',
    });
    expect(classify('Quick reply to mom')).toBe('EASY');
  });
});
