import { test, expect } from '@playwright/test';

/**
 * The unit 23 spec asks for a signed-in flow that reorders + renames a
 * tag and then types a query into Search. The web test harness has no
 * Firebase emulator wired in (see `playwright.config.ts` — auth tests
 * stop at the login page), so this spec covers the parts of the
 * surface that *don't* require credentials:
 *
 *   1. /tags is registered as a protected route (redirects to /login).
 *   2. /search is registered as a protected route (redirects to /login).
 *
 * Once a sign-in helper exists (tracked in the parity epic), this spec
 * should be extended to drive the actual reorder + query flow.
 */

test.describe('Tag Management + Search routes', () => {
  test('redirects unauthenticated /tags to login', async ({ page }) => {
    await page.goto('/tags');
    await expect(page).toHaveURL(/\/login/);
  });

  test('redirects unauthenticated /search to login', async ({ page }) => {
    await page.goto('/search');
    await expect(page).toHaveURL(/\/login/);
  });

  test('login page still loads (smoke for the protected redirect path)', async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByLabel(/email/i)).toBeVisible();
  });
});
