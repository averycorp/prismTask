import { test, expect } from '@playwright/test';

/**
 * Smoke test for the Weekly Balance Report route. Same pattern as the
 * other specs in this folder — no sign-in, just confirms the route is
 * wired and the protected-route redirect behaves.
 *
 * The deeper "renders chart, fires overload alert with seeded categorized
 * tasks" assertion lives in the unit tests under
 * `web/src/features/balance/__tests__/weeklyBalanceReport.test.ts` and
 * `web/src/utils/__tests__/balanceTracker.test.ts`, which exercise the
 * computation layer the chart consumes.
 */
test.describe('Weekly Balance Report route', () => {
  test('unauthenticated /balance/weekly-report bounces to login', async ({ page }) => {
    await page.goto('/balance/weekly-report');
    await expect(page).toHaveURL(/\/(login)?$/);
  });
});
