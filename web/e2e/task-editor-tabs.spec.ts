import { test, expect } from '@playwright/test';

/**
 * Smoke tests for the refactored 3-tab task editor (parity unit 9 of
 * 23). Same pattern as the other specs in this folder — no sign-in,
 * just confirms the protected route + bundle wires up correctly. The
 * full open-editor / swipe-tabs / fill-fields flow is exercised by the
 * vitest tab tests + the upstream Android instrumentation suite; an
 * unauthenticated Playwright spec has no Firebase session to mount a
 * real TaskEditor against.
 */
test.describe('Task Editor Tabs route', () => {
  test('unauthenticated /tasks bounces to login', async ({ page }) => {
    await page.goto('/tasks');
    await expect(page).toHaveURL(/\/login/);
  });

  test('login page is reachable from the task list deep link', async ({
    page,
  }) => {
    await page.goto('/tasks/some-id');
    await expect(page).toHaveURL(/\/login/);
    await expect(
      page.getByRole('heading', { name: /sign in|log in|welcome/i }),
    ).toBeVisible();
  });
});
