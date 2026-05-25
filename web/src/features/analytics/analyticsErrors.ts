/**
 * Statuses that are expected for analytics calls and must NOT be surfaced
 * as failures: 429 (the AI habit-correlation endpoint is rate-limited to
 * once per day), 451 (AI features disabled in Settings), and 404 (no data
 * yet). See bug B-07.
 */
const EXPECTED_ANALYTICS_STATUSES = new Set([404, 429, 451]);

export function isExpectedAnalyticsError(reason: unknown): boolean {
  const status = (reason as { response?: { status?: number } } | undefined)
    ?.response?.status;
  return status !== undefined && EXPECTED_ANALYTICS_STATUSES.has(status);
}
