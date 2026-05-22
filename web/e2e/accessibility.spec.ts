import { test, expect } from '@playwright/test';

test.describe('Accessibility', () => {
  test('login page has proper heading structure', async ({ page }) => {
    await page.goto('/login');
    const h1 = page.locator('h1');
    await expect(h1).toBeVisible();
  });

  test('page has lang attribute', async ({ page }) => {
    await page.goto('/login');
    const lang = await page.getAttribute('html', 'lang');
    expect(lang).toBe('en');
  });

  test('interactive elements are keyboard focusable', async ({ page }) => {
    await page.goto('/login');
    // Wait for the page to render
    await expect(page.locator('h1')).toBeVisible();
    // Tab to next interactive element (email input is auto-focused on mount)
    await page.keyboard.press('Tab');
    const focused = await page.evaluate(() => document.activeElement?.tagName);
    expect(focused).toBeTruthy();
    expect(['INPUT', 'BUTTON', 'A', 'SELECT']).toContain(focused);
  });
});
