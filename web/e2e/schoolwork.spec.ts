import { test, expect } from '@playwright/test';

test.describe('Schoolwork route', () => {
  test('unauthenticated /schoolwork bounces to login', async ({ page }) => {
    await page.goto('/schoolwork');
    await expect(page).toHaveURL(/\/(login)?$/);
  });
});
