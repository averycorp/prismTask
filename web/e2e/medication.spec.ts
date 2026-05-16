import { test, expect } from '@playwright/test';

test.describe('Medication routes', () => {
  test('unauthenticated /medication bounces to login', async ({ page }) => {
    await page.goto('/medication');
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('unauthenticated /medication/refills bounces to login', async ({
    page,
  }) => {
    await page.goto('/medication/refills');
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('unauthenticated /medication/history bounces to login', async ({
    page,
  }) => {
    await page.goto('/medication/history');
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('unauthenticated /medication/clinical-report bounces to login', async ({
    page,
  }) => {
    await page.goto('/medication/clinical-report');
    await expect(page).toHaveURL(/\/(login)?$/);
  });
});
