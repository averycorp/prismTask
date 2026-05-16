import { test, expect } from '@playwright/test';

/**
 * Morning Check-In routed screen (parity unit 11 of 23).
 *
 * Auth is gated by Firebase — without test credentials the route bounces
 * to /login. We assert the bounce here so the route exists and is
 * protected. The full sign-in + stepper happy-path is covered in
 * Android instrumented tests (`MorningCheckInScreenTest`); the parity
 * audit doesn't require a duplicate sign-in flow in Playwright since
 * the underlying check-in API is shared cross-device.
 */
test.describe('Morning Check-In route', () => {
  test('unauthenticated /checkin bounces to login', async ({ page }) => {
    await page.goto('/checkin');
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('unauthenticated /checkin/history bounces to login', async ({ page }) => {
    await page.goto('/checkin/history');
    await expect(page).toHaveURL(/\/(login)?$/);
  });
});
