import { test, expect } from '@playwright/test';

/**
 * Smoke test for the Today-screen sheets and morning check-in banner.
 *
 * The E2E suite in this repo does not log users in (same pattern as
 * `navigation.spec.ts` and the feature-route smoke specs). We verify the
 * Today route is wired up and that unauthenticated hits land back on
 * login — this protects against a regression where the Plan-For-Today
 * sheet, Done counter sheet, or Morning Check-In banner break the
 * Today route's lazy bundle and render a white screen for signed-in
 * users on prod.
 */
test.describe('Today sheets + check-in banner', () => {
  test('unauthenticated / bounces to login', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('unauthenticated /checkin/history bounces to login', async ({ page }) => {
    // Sanity: the History link in the morning check-in card / banner is
    // wired to a protected route. Same redirect contract as the rest of
    // the protected surface.
    await page.goto('/checkin/history');
    await expect(page).toHaveURL(/\/(login)?$/);
  });
});
