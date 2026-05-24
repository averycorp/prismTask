import { describe, it, expect } from 'vitest';
import { isExpectedAnalyticsError } from '../analyticsErrors';

function axiosError(status: number) {
  return { response: { status } };
}

describe('isExpectedAnalyticsError (B-07)', () => {
  it('treats 429 (rate-limited habit correlations) as expected', () => {
    expect(isExpectedAnalyticsError(axiosError(429))).toBe(true);
  });

  it('treats 451 (AI features disabled) as expected', () => {
    expect(isExpectedAnalyticsError(axiosError(451))).toBe(true);
  });

  it('treats 404 (no data yet) as expected', () => {
    expect(isExpectedAnalyticsError(axiosError(404))).toBe(true);
  });

  it('treats a genuine 500 as an unexpected failure', () => {
    expect(isExpectedAnalyticsError(axiosError(500))).toBe(false);
  });

  it('treats a network error (no response) as an unexpected failure', () => {
    expect(isExpectedAnalyticsError(new Error('Network Error'))).toBe(false);
  });
});
