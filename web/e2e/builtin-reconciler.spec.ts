import { test, expect } from '@playwright/test';

/**
 * Smoke for the built-in habit reconciler wiring.
 *
 * The deep dedup pass requires a signed-in Firestore session, which the
 * Playwright suite doesn't provision today (auth lives behind Google
 * Sign-In). What we can verify here is that:
 *
 *   1. The reconciler module loads in the production bundle (no import
 *      side-effects, no console errors on `/login`).
 *   2. The `BuiltInUpdateBanner` does not render on the login screen
 *      (soft-hides when there are no habits + no template versions).
 *
 * Deeper coverage lives in the Vitest suite
 * (`src/lib/__tests__/builtInHabitReconciler.test.ts`) which can
 * deterministically inject duplicates without standing up Firebase.
 */
test.describe('Built-in Habit Reconciler', () => {
  test('login page loads without reconciler console errors', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (err) => errors.push(err.message));
    page.on('console', (msg) => {
      if (msg.type() === 'error') errors.push(msg.text());
    });
    await page.goto('/login');
    await expect(page).toHaveTitle(/PrismTask/);
    // Allow time for any async reconciler kicks to surface.
    await page.waitForTimeout(500);
    const reconcilerErrors = errors.filter((e) =>
      /reconciler|builtIn|habit/i.test(e),
    );
    expect(reconcilerErrors).toEqual([]);
  });

  test('built-in update banner is hidden on the login surface', async ({
    page,
  }) => {
    await page.goto('/login');
    await expect(page.getByText(/Update Available:/i)).toHaveCount(0);
  });
});
