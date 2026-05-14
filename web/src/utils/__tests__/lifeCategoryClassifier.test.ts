import { describe, expect, it } from 'vitest';
import {
  buildClassifierWithCustomKeywords,
  classifyLifeCategory,
} from '@/utils/lifeCategoryClassifier';

describe('classifyLifeCategory', () => {
  it('returns UNCATEGORIZED on empty input', () => {
    expect(classifyLifeCategory('')).toBe('UNCATEGORIZED');
    expect(classifyLifeCategory('   ')).toBe('UNCATEGORIZED');
  });

  it('classifies WORK from common keywords', () => {
    expect(classifyLifeCategory('1:1 with client')).toBe('WORK');
    expect(classifyLifeCategory('Quarterly report deadline')).toBe('WORK');
  });

  it('classifies HEALTH for doctor/therapy/meds', () => {
    expect(classifyLifeCategory('Pickup prescription')).toBe('HEALTH');
    expect(classifyLifeCategory('Doctor appointment 3pm')).toBe('HEALTH');
  });

  it('classifies SELF_CARE for movement/hobby/rest', () => {
    expect(classifyLifeCategory('Morning yoga')).toBe('SELF_CARE');
    expect(classifyLifeCategory('Read fiction')).toBe('SELF_CARE');
  });

  it('classifies PERSONAL for chores/errands', () => {
    expect(classifyLifeCategory('Grocery shopping')).toBe('PERSONAL');
    expect(classifyLifeCategory('Laundry')).toBe('PERSONAL');
  });

  it('breaks ties in HEALTH > SELF_CARE > WORK > PERSONAL order', () => {
    // "walk" (SELF_CARE) + "client" (WORK) → SELF_CARE wins on tie
    expect(classifyLifeCategory('walk with client')).toBe('SELF_CARE');
    // "doctor" (HEALTH) + "walk" (SELF_CARE) → HEALTH wins on tie
    expect(classifyLifeCategory('walk with doctor')).toBe('HEALTH');
  });

  it('matches multi-word keywords as substrings', () => {
    expect(classifyLifeCategory('self care evening')).toBe('SELF_CARE');
  });

  it('does not match word fragments (yogawear ≠ yoga)', () => {
    expect(classifyLifeCategory('buy yogawear')).toBe('UNCATEGORIZED');
  });

  it('includes description in haystack', () => {
    expect(classifyLifeCategory('Daily task', 'pick up meds')).toBe('HEALTH');
  });
});

describe('buildClassifierWithCustomKeywords', () => {
  it('merges user CSV keywords with the defaults', () => {
    const classify = buildClassifierWithCustomKeywords({ selfCare: 'tea, bath' });
    expect(classify('Hot tea time')).toBe('SELF_CARE');
    expect(classify('Take a bath')).toBe('SELF_CARE');
  });

  it('handles empty CSV gracefully', () => {
    const classify = buildClassifierWithCustomKeywords({ work: '' });
    expect(classify('client meeting')).toBe('WORK');
  });
});
