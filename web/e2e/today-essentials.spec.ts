import { test, expect } from '@playwright/test';

/**
 * Parity unit 6 of 23 — Today screen A: balance bar + daily essentials +
 * dashboard reorder.
 *
 * Web's e2e harness does not yet have an authenticated-user fixture,
 * so this spec exercises the routes/redirects without a signed-in
 * session — same shape as `auth.spec.ts` / `navigation.spec.ts`. The
 * full sign-in + reorder flow (asserting BalanceBar renders, a Daily
 * Essentials card is present, Settings → Layout reorder reflects on
 * Today) is left for the auth-fixture follow-up tracked in the parity
 * audit.
 */
test.describe('Today — balance bar + daily essentials + dashboard reorder (parity unit 6)', () => {
  test('Today route redirects unauthenticated users to login', async ({ page }) => {
    await page.goto('/today');
    await expect(page).toHaveURL(/\/login/);
  });

  test('Settings route redirects unauthenticated users to login', async ({ page }) => {
    await page.goto('/settings');
    await expect(page).toHaveURL(/\/login/);
  });

  test('login page loads — gate for any signed-in flow', async ({ page }) => {
    await page.goto('/login');
    await expect(page).toHaveTitle(/PrismTask/);
    await expect(
      page.getByRole('heading', { name: /sign in|log in|welcome/i }),
    ).toBeVisible();
  });
});
