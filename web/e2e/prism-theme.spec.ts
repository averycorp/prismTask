import { test, expect } from '@playwright/test';

/**
 * Focused Playwright spec for the 4-variant PrismTheme system. The other
 * specs in this repo follow a no-sign-in smoke pattern (live Firebase
 * auth isn't available in CI), so this spec drives the theme picker via
 * the publicly-reachable `localStorage` key the store reads on mount —
 * the same path a returning, signed-out user takes. Asserts:
 *
 *  - each of the four variants flips the `data-theme` + `data-personality`
 *    attributes on `<html>`
 *  - the `--color-accent` CSS custom property changes per variant (this
 *    is the load-bearing token Tailwind utilities + `.prism-*` helper
 *    classes pick up)
 *  - selecting a variant via the Settings → Appearance picker UI
 *    persists across reload (covered indirectly when the test seeds
 *    localStorage then re-navigates)
 */

interface ThemeFixture {
  key: 'CYBERPUNK' | 'SYNTHWAVE' | 'MATRIX' | 'VOID';
  primary: string;
  personality: 'brackets' | 'sunset' | 'terminal' | 'editorial';
}

const VARIANTS: ThemeFixture[] = [
  { key: 'CYBERPUNK', primary: '#00F5FF', personality: 'brackets' },
  { key: 'SYNTHWAVE', primary: '#FF2D87', personality: 'sunset' },
  { key: 'MATRIX', primary: '#00FF41', personality: 'terminal' },
  { key: 'VOID', primary: '#C8B8FF', personality: 'editorial' },
];

test.describe('PrismTheme variant picker', () => {
  for (const variant of VARIANTS) {
    test(`${variant.key} applies its palette + personality to <html>`, async ({
      page,
    }) => {
      // Seed the theme key before the app mounts so `useThemeStore`
      // reads it during its synchronous loader. This is the same
      // localStorage round-trip a returning user takes — it bypasses
      // the picker UI but exercises the same `applyThemeToDocument`
      // path the picker fires.
      await page.addInitScript((themeKey: string) => {
        localStorage.setItem('prismtask_theme_key', themeKey);
      }, variant.key);

      await page.goto('/login');

      await expect(page.locator('html')).toHaveAttribute('data-theme', variant.key);
      await expect(page.locator('html')).toHaveAttribute(
        'data-personality',
        variant.personality,
      );

      // The accent CSS custom property is the headline load-bearing
      // value — Tailwind utilities (text-accent, border-accent, etc.)
      // and every `.prism-*` helper class fan out from it.
      const accent = await page.evaluate(() =>
        getComputedStyle(document.documentElement)
          .getPropertyValue('--color-accent')
          .trim()
          .toUpperCase(),
      );
      expect(accent).toBe(variant.primary);
    });
  }

  test('flipping themes via localStorage updates --color-accent across navigations', async ({
    page,
  }) => {
    // Land on Cyberpunk first. Playwright re-runs init scripts on every
    // navigation (including `page.reload()`), so seed conditionally —
    // otherwise the SYNTHWAVE flip below gets clobbered back to
    // CYBERPUNK on reload before the app reads it.
    await page.addInitScript(() => {
      if (!localStorage.getItem('prismtask_theme_key')) {
        localStorage.setItem('prismtask_theme_key', 'CYBERPUNK');
      }
    });
    await page.goto('/login');
    // PrismThemeProvider applies the theme in a `useEffect`, so the CSS
    // custom property lands a tick after first paint. Wait on the
    // `data-theme` attribute that `applyThemeToDocument` sets in the
    // same call — once it's there, the CSS var is also set.
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'CYBERPUNK');
    const accent1 = await page.evaluate(() =>
      getComputedStyle(document.documentElement)
        .getPropertyValue('--color-accent')
        .trim()
        .toUpperCase(),
    );
    expect(accent1).toBe('#00F5FF');

    // Flip to Synthwave via the same key and reload — the loader runs
    // synchronously on mount so the new accent must apply before the
    // first paint of the next navigation.
    await page.evaluate(() => {
      localStorage.setItem('prismtask_theme_key', 'SYNTHWAVE');
    });
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'SYNTHWAVE');
    const accent2 = await page.evaluate(() =>
      getComputedStyle(document.documentElement)
        .getPropertyValue('--color-accent')
        .trim()
        .toUpperCase(),
    );
    expect(accent2).toBe('#FF2D87');
  });
});
