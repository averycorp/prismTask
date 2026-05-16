import { test, expect } from '@playwright/test';

/**
 * Brain Mode settings sub-screen (web parity unit 21 of 23).
 *
 * The unprotected route should bounce to login — consistent with every
 * other authed surface (`mood.spec.ts`, etc.). We don't have a signed-in
 * fixture in the web e2e suite today, so this spec mirrors the existing
 * smoke shape rather than driving the full signed-in flow.
 */

test.describe('Brain Mode settings route', () => {
  test('unauthenticated /settings/brain-mode bounces to login', async ({
    page,
  }) => {
    await page.goto('/settings/brain-mode');
    await expect(page).toHaveURL(/\/(login)?$/);
  });
});
