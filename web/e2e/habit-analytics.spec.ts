import { test, expect } from '@playwright/test';

test.describe('Habit analytics route', () => {
  test('unauthenticated /habits/:id/analytics bounces to login', async ({
    page,
  }) => {
    await page.goto('/habits/test-habit-id/analytics');
    await expect(page).toHaveURL(/\/(login)?$/);
  });
});
