import { describe, it, expect } from 'vitest';
import {
  parse,
  formatRulePreview,
  formatHourMinute,
  WEEKDAYS,
  WEEKEND,
  ALL_DAYS,
  type BoundaryRule,
  type ParseError,
} from '@/lib/boundaryRuleParser';

function assertRule(result: BoundaryRule | ParseError): BoundaryRule {
  if ('error' in result) {
    throw new Error(`Expected rule, got error: ${result.error}`);
  }
  return result;
}

describe('boundaryRuleParser — work-hours', () => {
  it('parses "No work after 18:00"', () => {
    const r = assertRule(parse('No work after 18:00'));
    expect(r.ruleType).toBe('work-hours');
    if (r.ruleType !== 'work-hours') throw new Error('shape');
    expect(r.endHour).toBe(18);
    expect(r.endMinute).toBe(0);
    expect(r.startHour).toBe(0);
    expect(r.startMinute).toBe(0);
    expect(r.activeDays).toEqual(ALL_DAYS);
  });

  it('parses "No tasks before 09:00"', () => {
    const r = assertRule(parse('No tasks before 09:00'));
    if (r.ruleType !== 'work-hours') throw new Error('shape');
    expect(r.startHour).toBe(9);
    expect(r.startMinute).toBe(0);
    expect(r.endHour).toBe(23);
    expect(r.endMinute).toBe(59);
  });

  it('parses "Block work after 7pm on weekdays"', () => {
    const r = assertRule(parse('Block work after 7pm on weekdays'));
    if (r.ruleType !== 'work-hours') throw new Error('shape');
    expect(r.endHour).toBe(19);
    expect(r.activeDays).toEqual(WEEKDAYS);
  });

  it('parses "Don\'t schedule work after 8am on weekends"', () => {
    const r = assertRule(parse("Don't schedule work after 8am on weekends"));
    if (r.ruleType !== 'work-hours') throw new Error('shape');
    expect(r.endHour).toBe(8);
    expect(r.activeDays).toEqual(WEEKEND);
  });

  it('handles "noon" and "midnight" keywords', () => {
    const noon = assertRule(parse('No work after noon'));
    if (noon.ruleType !== 'work-hours') throw new Error('shape');
    expect(noon.endHour).toBe(12);

    const mid = assertRule(parse('No work before midnight'));
    if (mid.ruleType !== 'work-hours') throw new Error('shape');
    expect(mid.startHour).toBe(0);
  });

  it('parses single-day clause "on Monday"', () => {
    const r = assertRule(parse('No work after 17:00 on Monday'));
    if (r.ruleType !== 'work-hours') throw new Error('shape');
    expect(r.activeDays).toEqual([1]);
  });

  it('falls back to ALL_DAYS when no day clause is present', () => {
    const r = assertRule(parse('No work after 18:00'));
    if (r.ruleType !== 'work-hours') throw new Error('shape');
    expect(r.activeDays).toEqual(ALL_DAYS);
  });
});

describe('boundaryRuleParser — category-limit', () => {
  it('parses "Max 4 hours/day on Work"', () => {
    const r = assertRule(parse('Max 4 hours/day on Work'));
    if (r.ruleType !== 'category-limit') throw new Error('shape');
    expect(r.bound).toBe('max');
    expect(r.hoursPerDay).toBe(4);
    expect(r.category).toBe('WORK');
  });

  it('parses "At least 1 hour/day on Self-Care"', () => {
    const r = assertRule(parse('At least 1 hour/day on Self-Care'));
    if (r.ruleType !== 'category-limit') throw new Error('shape');
    expect(r.bound).toBe('min');
    expect(r.hoursPerDay).toBe(1);
    expect(r.category).toBe('SELF_CARE');
  });

  it('parses "At most 2.5 h per day on Health"', () => {
    const r = assertRule(parse('At most 2.5 h per day on Health'));
    if (r.ruleType !== 'category-limit') throw new Error('shape');
    expect(r.bound).toBe('max');
    expect(r.hoursPerDay).toBe(2.5);
    expect(r.category).toBe('HEALTH');
  });

  it('errors on out-of-range hours', () => {
    const r = parse('Max 99 hours/day on Work');
    expect('error' in r).toBe(true);
  });

  it('errors when no category is present', () => {
    const r = parse('Max 4 hours/day on something');
    expect('error' in r).toBe(true);
  });
});

describe('boundaryRuleParser — escalation', () => {
  it('parses a two-stage chain', () => {
    const r = assertRule(
      parse('Escalate Work-Hours to Focus then Quiet after 10 minutes'),
    );
    if (r.ruleType !== 'escalation') throw new Error('shape');
    expect(r.profiles).toEqual(['Focus', 'Quiet']);
    expect(r.delayMinutes).toBe(10);
    expect(r.trigger).toBe('Work-Hours');
  });

  it('parses a comma-separated chain', () => {
    const r = assertRule(
      parse('Escalate boundary to Focus, Quiet, DND after 5 mins'),
    );
    if (r.ruleType !== 'escalation') throw new Error('shape');
    expect(r.profiles).toEqual(['Focus', 'Quiet', 'Dnd']);
    expect(r.delayMinutes).toBe(5);
  });

  it('errors on zero or negative delay', () => {
    const r = parse('Escalate rule to Focus after 0 minutes');
    expect('error' in r).toBe(true);
  });
});

describe('boundaryRuleParser — error paths', () => {
  it('returns an error for empty input', () => {
    expect('error' in parse('')).toBe(true);
    expect('error' in parse('   ')).toBe(true);
  });

  it('returns an error for an unrecognized phrase', () => {
    const r = parse('I want to be more productive');
    expect('error' in r).toBe(true);
  });

  it('returns an error for malformed time', () => {
    const r = parse('No work after 25:99');
    expect('error' in r).toBe(true);
  });
});

describe('formatRulePreview', () => {
  it('formats work-hours rules', () => {
    expect(
      formatRulePreview({
        ruleType: 'work-hours',
        startHour: 9,
        startMinute: 0,
        endHour: 18,
        endMinute: 0,
        activeDays: ALL_DAYS,
      }),
    ).toBe('Work window 09:00–18:00');
  });

  it('formats category-limit rules', () => {
    expect(
      formatRulePreview({
        ruleType: 'category-limit',
        category: 'WORK',
        bound: 'max',
        hoursPerDay: 4,
        activeDays: ALL_DAYS,
      }),
    ).toBe('Max 4h/day on Work');
  });

  it('formats escalation rules', () => {
    expect(
      formatRulePreview({
        ruleType: 'escalation',
        trigger: 'Work-Hours',
        profiles: ['Focus', 'Quiet'],
        delayMinutes: 10,
        activeDays: ALL_DAYS,
      }),
    ).toBe('Escalate Work-Hours rule: Focus → Quiet (every 10m)');
  });
});

describe('formatHourMinute', () => {
  it('zero-pads single digit values', () => {
    expect(formatHourMinute(9, 5)).toBe('09:05');
    expect(formatHourMinute(0, 0)).toBe('00:00');
    expect(formatHourMinute(23, 59)).toBe('23:59');
  });
});
