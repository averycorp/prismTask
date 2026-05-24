import { test, expect } from '@playwright/test';

test('click habit row', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (err) => {
    errors.push(err.message);
  });
  
  await page.goto('/');
  await page.fill('input[type="email"]', 'test@example.com');
  await page.fill('input[type="password"]', 'password123');
  await page.click('button[type="submit"]');
  
  await page.waitForURL('/dashboard');
  
  // Wait for habits to load
  await page.waitForSelector('text=Habits');
  
  // Try to click a habit checkbox
  const checkboxes = await page.locator('button[aria-label^="Toggle"]').all();
  if (checkboxes.length > 0) {
    await checkboxes[0].click();
    await page.waitForTimeout(500);
  }
  
  // Try to click the habit row
  const rows = await page.locator('button[aria-label^="Open"]').all();
  if (rows.length > 0) {
    await rows[0].click();
    await page.waitForTimeout(500);
  }
  
  console.log("ERRORS:", errors);
  expect(errors.length).toBe(0);
});
