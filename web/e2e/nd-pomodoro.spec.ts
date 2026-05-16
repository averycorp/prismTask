import { test, expect } from '@playwright/test';

/**
 * Smoke test for the ND-friendly Pomodoro variants
 * (GoodEnoughTimer + ShipItCelebration + EnergyAwarePomodoro).
 *
 * Auth-gated screens bounce to /login when unauthenticated. The unit
 * tests in `src/utils/__tests__/{energyAwarePomodoro,goodEnoughTimerManager,shipItCelebrationManager}.test.ts`
 * cover the actual ND state-transition logic (energy → duration buckets,
 * 70% good-enough threshold, celebration intensity / streak milestones).
 *
 * Mirrors the auth-bounce pattern used by `mood.spec.ts` /
 * `analytics.spec.ts` — the deeper "sign in, open Pomodoro, mark Good
 * Enough, verify focus_release_logs row" path requires Firebase Auth
 * test infra that this repo's Playwright suite doesn't currently
 * stand up. Documented as a follow-up next to the analytics smoke.
 */
test.describe('ND Pomodoro route', () => {
  test('unauthenticated /pomodoro bounces to login', async ({ page }) => {
    await page.goto('/pomodoro');
    await expect(page).toHaveURL(/\/(login)?$/);
  });
});
