import { test, expect } from '@playwright/test';

/**
 * Smoke coverage for the Weekly Review surface added in parity unit 12.
 *
 * The e2e harness runs unauthenticated, so we can't exercise the
 * Generate Now -> Firestore round-trip. We *can* assert that the new
 * routes are wired up: hitting any of them deep-links a logged-out
 * visitor to /login (via ProtectedRoute) without hitting the catch-all
 * "redirect to /" rule. That guards against a regression where the
 * history / detail routes silently 404 or render the wrong screen.
 */

test.describe('Weekly Review routes', () => {
  test('main screen redirects to login for unauthenticated visitor', async ({ page }) => {
    await page.goto('/weekly-review');
    await expect(page).toHaveURL(/\/login/);
  });

  test('history screen redirects to login for unauthenticated visitor', async ({ page }) => {
    await page.goto('/weekly-review/history');
    await expect(page).toHaveURL(/\/login/);
  });

  test('detail screen redirects to login for unauthenticated visitor', async ({ page }) => {
    await page.goto('/weekly-review/detail/2026-05-04');
    await expect(page).toHaveURL(/\/login/);
  });
});
