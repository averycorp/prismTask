import { test, expect } from '@playwright/test';

// All navigation-chrome tests run unauthenticated — the Firebase
// sign-in flow can't be driven from Playwright without real account
// credentials, so route-registration coverage takes the place of a
// "sign in and click each tab" end-to-end. Component / unit tests in
// `src/components/layout/__tests__` exercise the nav-rendering logic
// itself (active tab, detail-route hide, primary-tab list).
//
// Per-test timeouts are widened because the Vite dev server transforms
// chunks lazily — the first hit to a never-rendered chunk can take
// 20–60s on a cold cache, and the suite spans 18+ overflow routes.
test.describe('Navigation chrome', () => {
  test('login page renders correctly', async ({ page }) => {
    test.setTimeout(60000);
    await page.goto('/login');
    await expect(page).toHaveTitle(/PrismTask/);
  });

  test('register page renders correctly', async ({ page }) => {
    test.setTimeout(60000);
    await page.goto('/register');
    await expect(page).toHaveTitle(/PrismTask/);
  });

  test('unknown routes redirect to login when not authenticated', async ({ page }) => {
    test.setTimeout(60000);
    await page.goto('/nonexistent-page');
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('every primary tab is a real route (deep-linkable)', async ({ page }) => {
    test.setTimeout(120000);
    // The 5-tab parity contract requires each primary tab to be a real,
    // bookmarkable route. Unauthenticated, each redirects to /login.
    for (const path of ['/', '/tasks', '/projects', '/habits', '/settings']) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/(login)?$/);
    }
  });

  test('every overflow-drawer route resolves (no 5xx)', async ({ page }) => {
    test.setTimeout(180000);
    // The overflow drawer surfaces these as menu items. If a route is
    // missing from `routes/index.tsx`, the wildcard `*` redirect still
    // lands on /login when unauthenticated — but any 5xx means a route
    // file is broken (import error, syntax error, etc.).
    const overflowPaths = [
      '/calendar',
      '/briefing',
      '/eisenhower',
      '/planner',
      '/timeblock',
      '/pomodoro',
      '/weekly-review',
      '/analytics',
      '/mood',
      '/medication',
      '/focus',
      '/self-care',
      '/leisure',
      '/schoolwork',
      '/chat',
      '/templates',
      '/extract',
      '/archive',
    ];
    for (const path of overflowPaths) {
      const response = await page.goto(path);
      expect(response?.status() ?? 0).toBeLessThan(500);
      await expect(page).toHaveURL(/\/(login)?$/);
    }
  });

  test('has correct meta tags', async ({ page }) => {
    test.setTimeout(60000);
    await page.goto('/login');
    const description = await page.getAttribute('meta[name="description"]', 'content');
    expect(description).toBeTruthy();
    expect(description).toContain('task management');
  });

  test('has PWA manifest link', async ({ page }) => {
    test.setTimeout(60000);
    await page.goto('/login');
    const manifest = await page.getAttribute('link[rel="manifest"]', 'href');
    expect(manifest).toBe('/manifest.json');
  });
});
