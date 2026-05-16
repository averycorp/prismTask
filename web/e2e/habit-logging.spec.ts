import { test, expect } from '@playwright/test';

/**
 * Smoke-coverage for the habit-logging UI parity bundle (unit 14 of 23).
 *
 * Like the rest of the e2e suite, these tests run against the un-signed-in
 * shell — full Firestore-backed flows (seed a habit, sign in, open the
 * dialog, write a `habit_logs` doc, assert it shows up on the logs screen)
 * require infra that the CI run-tier doesn't have today. The Playwright
 * job stays informational on the unauthenticated surface: any of these
 * habit routes must redirect to /login, never 500, and never wedge the
 * client-side router on a missing /habits/:id/logs route.
 */

test.describe('Habit Logging', () => {
  test('habit list redirects to login when unauthenticated', async ({ page }) => {
    await page.goto('/habits');
    await expect(page).toHaveURL(/\/login/);
  });

  test('habit logs screen redirects to login when unauthenticated', async ({ page }) => {
    await page.goto('/habits/some-fake-id/logs');
    await expect(page).toHaveURL(/\/login/);
  });

  test('habit analytics screen redirects to login when unauthenticated', async ({ page }) => {
    await page.goto('/habits/some-fake-id/analytics');
    await expect(page).toHaveURL(/\/login/);
  });
});
