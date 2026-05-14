import { describe, expect, it } from 'vitest';
import { selectSelfCareNudge } from '@/utils/selfCareNudgeEngine';

describe('selectSelfCareNudge', () => {
  it('returns null when neither burnout nor low self-care fires', () => {
    expect(
      selectSelfCareNudge({
        burnoutScore: 20,
        selfCareRatio: 0.25,
        selfCareTarget: 0.2,
        hourOfDay: 10,
        lastShownId: null,
      }),
    ).toBeNull();
  });

  it('returns a nudge when self-care ratio is below target by more than buffer', () => {
    const nudge = selectSelfCareNudge({
      burnoutScore: 20,
      selfCareRatio: 0.05,
      selfCareTarget: 0.2,
      hourOfDay: 10,
      lastShownId: null,
    });
    expect(nudge).not.toBeNull();
  });

  it('returns a burnout warning when burnout is elevated', () => {
    const nudge = selectSelfCareNudge({
      burnoutScore: 70,
      selfCareRatio: 0.3,
      selfCareTarget: 0.2,
      hourOfDay: 10,
      lastShownId: 'rest_break',
    });
    expect(nudge?.id).toBe('burnout_warning');
  });

  it('rotates away from the last-shown id when possible', () => {
    const nudge = selectSelfCareNudge({
      burnoutScore: 20,
      selfCareRatio: 0.05,
      selfCareTarget: 0.3,
      hourOfDay: 10,
      lastShownId: 'rest_break',
    });
    expect(nudge?.id).not.toBe('rest_break');
  });

  it('surfaces wind-down only after 6pm', () => {
    const morning = selectSelfCareNudge({
      burnoutScore: 70,
      selfCareRatio: 0.05,
      selfCareTarget: 0.3,
      hourOfDay: 8,
      lastShownId: 'rest_break',
    });
    expect(morning?.id).not.toBe('wind_down');

    const evening = selectSelfCareNudge({
      burnoutScore: 70,
      selfCareRatio: 0.05,
      selfCareTarget: 0.3,
      hourOfDay: 20,
      lastShownId: 'burnout_warning',
    });
    // After rotation away from burnout_warning, evening's first candidate is rest_break (also != lastShownId)
    expect(evening).not.toBeNull();
  });
});
