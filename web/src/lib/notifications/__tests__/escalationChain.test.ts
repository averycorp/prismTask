import { describe, it, expect } from 'vitest';
import {
  DEFAULT_AGGRESSIVE_ESCALATION_CHAIN,
  DEFAULT_ESCALATION_CHAIN,
  DEFAULT_QUIET_HOURS,
  decodeEscalationChain,
  decodeQuietHours,
  encodeEscalationChain,
  encodeQuietHours,
  type EscalationChain,
  type QuietHoursWindow,
} from '../escalationChain';

describe('escalationChain codec', () => {
  it('round-trips DEFAULT_AGGRESSIVE_ESCALATION_CHAIN', () => {
    const encoded = encodeEscalationChain(DEFAULT_AGGRESSIVE_ESCALATION_CHAIN);
    const decoded = decodeEscalationChain(encoded);
    expect(decoded).toEqual(DEFAULT_AGGRESSIVE_ESCALATION_CHAIN);
  });

  it('round-trips a hand-rolled chain', () => {
    const chain: EscalationChain = {
      enabled: true,
      steps: [
        { action: 'gentle', delayMs: 0, triggerTiers: [] },
        {
          action: 'standard',
          delayMs: 60_000,
          triggerTiers: ['medium', 'high'],
        },
      ],
      stopOnInteraction: false,
      maxAttempts: 3,
    };
    const decoded = decodeEscalationChain(encodeEscalationChain(chain));
    expect(decoded).toEqual(chain);
  });

  it('returns DEFAULT_ESCALATION_CHAIN for null / undefined / empty', () => {
    expect(decodeEscalationChain(null)).toEqual(DEFAULT_ESCALATION_CHAIN);
    expect(decodeEscalationChain(undefined)).toEqual(DEFAULT_ESCALATION_CHAIN);
    expect(decodeEscalationChain('')).toEqual(DEFAULT_ESCALATION_CHAIN);
  });

  it('returns DEFAULT_ESCALATION_CHAIN for malformed JSON', () => {
    expect(decodeEscalationChain('not json')).toEqual(DEFAULT_ESCALATION_CHAIN);
    expect(decodeEscalationChain('[]')).toEqual(DEFAULT_ESCALATION_CHAIN);
    expect(decodeEscalationChain('null')).toEqual(DEFAULT_ESCALATION_CHAIN);
  });

  it('drops malformed steps but keeps valid ones', () => {
    const json = JSON.stringify({
      enabled: true,
      steps: [
        { action: 'gentle', delayMs: 0 },
        { action: 'unknown', delayMs: 100 }, // dropped
        { action: 'loud', delayMs: -50 }, // negative delay clamped to 0
        { action: 'standard', delayMs: 60_000, triggerTiers: ['bogus', 'high'] },
      ],
      stopOnInteraction: true,
      maxAttempts: 5,
    });
    const decoded = decodeEscalationChain(json);
    expect(decoded.steps).toHaveLength(3);
    expect(decoded.steps[0]).toEqual({
      action: 'gentle',
      delayMs: 0,
      triggerTiers: [],
    });
    expect(decoded.steps[1]).toEqual({
      action: 'loud',
      delayMs: 0,
      triggerTiers: [],
    });
    expect(decoded.steps[2]).toEqual({
      action: 'standard',
      delayMs: 60_000,
      triggerTiers: ['high'],
    });
  });

  it('defaults stopOnInteraction to true when omitted', () => {
    const decoded = decodeEscalationChain(
      JSON.stringify({ enabled: true, steps: [], maxAttempts: 4 }),
    );
    expect(decoded.stopOnInteraction).toBe(true);
  });

  it('defaults maxAttempts to 5 when omitted or negative', () => {
    expect(
      decodeEscalationChain(JSON.stringify({ enabled: false })).maxAttempts,
    ).toBe(5);
    expect(
      decodeEscalationChain(
        JSON.stringify({ enabled: false, maxAttempts: -1 }),
      ).maxAttempts,
    ).toBe(5);
  });
});

describe('quietHours codec', () => {
  it('round-trips DEFAULT_QUIET_HOURS', () => {
    const encoded = encodeQuietHours(DEFAULT_QUIET_HOURS);
    const decoded = decodeQuietHours(encoded);
    expect(decoded).toEqual(DEFAULT_QUIET_HOURS);
  });

  it('round-trips a hand-rolled window', () => {
    const window: QuietHoursWindow = {
      enabled: true,
      startHour: 21,
      startMinute: 30,
      endHour: 6,
      endMinute: 45,
      days: [1, 2, 3, 4, 5],
      priorityOverrideTiers: ['high', 'critical'],
    };
    const decoded = decodeQuietHours(encodeQuietHours(window));
    expect(decoded).toEqual(window);
  });

  it('returns DEFAULT_QUIET_HOURS for null / malformed input', () => {
    expect(decodeQuietHours(null)).toEqual(DEFAULT_QUIET_HOURS);
    expect(decodeQuietHours('not json')).toEqual(DEFAULT_QUIET_HOURS);
    expect(decodeQuietHours('[]')).toEqual(DEFAULT_QUIET_HOURS);
  });

  it('clamps out-of-range hour/minute to defaults', () => {
    const decoded = decodeQuietHours(
      JSON.stringify({
        enabled: true,
        start: { hour: 99, minute: -5 },
        end: { hour: 7, minute: 0 },
      }),
    );
    // start clamped to defaults
    expect(decoded.startHour).toBe(DEFAULT_QUIET_HOURS.startHour);
    expect(decoded.startMinute).toBe(DEFAULT_QUIET_HOURS.startMinute);
    // end stayed at the supplied value
    expect(decoded.endHour).toBe(7);
    expect(decoded.endMinute).toBe(0);
  });

  it('drops bogus tiers in priorityOverrideTiers', () => {
    const decoded = decodeQuietHours(
      JSON.stringify({
        enabled: true,
        start: { hour: 22, minute: 0 },
        end: { hour: 7, minute: 0 },
        days: [1, 2],
        priorityOverrideTiers: ['critical', 'super-critical', 99],
      }),
    );
    expect(decoded.priorityOverrideTiers).toEqual(['critical']);
  });

  it('falls back to ISO 1..7 when days is empty / invalid', () => {
    const decoded = decodeQuietHours(
      JSON.stringify({
        enabled: true,
        start: { hour: 22, minute: 0 },
        end: { hour: 7, minute: 0 },
        days: [],
      }),
    );
    expect(decoded.days).toEqual([1, 2, 3, 4, 5, 6, 7]);
  });
});
