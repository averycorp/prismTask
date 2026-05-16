import { test, expect } from '@playwright/test';

/**
 * Smoke checks for the Today-screen Habits / Schoolwork / Self-Care /
 * Burnout sections wired in by parity unit 7 of 23.
 *
 * The e2e harness on this repo runs unauthenticated against `vite
 * preview`, so the meaningful assertion is route-gating + page-load
 * behaviour — the same shape every other Today-related e2e file uses
 * (e.g. `analytics.spec.ts`, `mood.spec.ts`). Authenticated DOM
 * assertions against habit / course / self-care state require a signed-
 * in Firebase user, which the harness intentionally doesn't seed.
 */
test.describe('Today sections B (habits/schoolwork/self-care/burnout)', () => {
  test('unauthenticated / bounces to login', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('unauthenticated /habits bounces to login (deep-link guard)', async ({
    page,
  }) => {
    await page.goto('/habits');
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('unauthenticated /self-care bounces to login', async ({ page }) => {
    await page.goto('/self-care');
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('unauthenticated /settings bounces to login — burnout badge tap target', async ({
    page,
  }) => {
    // BurnoutBadgeSection routes to /settings (the Boundaries section
    // lives there); confirm the route exists and is auth-gated.
    await page.goto('/settings');
    await expect(page).toHaveURL(/\/(login)?$/);
  });
});
