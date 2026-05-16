import { test, expect } from '@playwright/test';

/**
 * Smoke spec for the Boundary Rules editor. Mirrors the shape of
 * `medication.spec.ts` — the playwright env doesn't sign in, so we
 * verify the unauthenticated routes bounce to /login. This catches
 * route-wiring regressions (e.g. forgetting to register the route
 * inside ProtectedRoute) without needing a real Firebase user.
 */
test.describe('Boundary Rules routes', () => {
  test('unauthenticated /boundaries bounces to login', async ({ page }) => {
    await page.goto('/boundaries');
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('unauthenticated /boundaries/new bounces to login', async ({ page }) => {
    await page.goto('/boundaries/new');
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('unauthenticated /boundaries/:id bounces to login', async ({ page }) => {
    await page.goto('/boundaries/abc123');
    await expect(page).toHaveURL(/\/(login)?$/);
  });
});
