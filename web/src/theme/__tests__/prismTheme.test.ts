import { describe, it, expect, beforeEach } from 'vitest';
import {
  applyThemeToDocument,
  DEFAULT_THEME_KEY,
  PRISM_THEMES,
  PRISM_THEME_ORDER,
  prismCssVar,
  resolvePrismTheme,
  type PrismThemeKey,
} from '@/theme/prismTheme';

/**
 * Variant resolution + CSS custom-property application tests for the
 * 4-variant PrismTheme system. Mirrors the Android contract in
 * `app/.../ui/theme/ThemeColors.kt` + `ThemeFonts.kt` + `PrismThemeAttrs.kt`:
 * each named theme is a self-contained color + font + shape + density
 * + glow bundle, and applying it broadcasts every token to document-root
 * CSS custom properties for Tailwind / `.prism-*` utilities to consume.
 */

describe('PrismTheme variant catalog', () => {
  it('ships exactly four variants in the documented order', () => {
    expect(PRISM_THEME_ORDER).toEqual(['CYBERPUNK', 'SYNTHWAVE', 'MATRIX', 'VOID']);
    expect(Object.keys(PRISM_THEMES)).toHaveLength(4);
  });

  it('defaults to VOID for unset / new users', () => {
    expect(DEFAULT_THEME_KEY).toBe('VOID');
  });

  it.each(PRISM_THEME_ORDER)(
    'variant %s carries a full token bundle (color + typography + shape + density + glow + personality)',
    (key) => {
      const tokens = PRISM_THEMES[key];
      expect(tokens.id).toBe(key);
      expect(tokens.label.length).toBeGreaterThan(0);
      expect(tokens.tagline.length).toBeGreaterThan(0);
      // Color axes
      expect(tokens.primary).toMatch(/^#/);
      expect(tokens.background).toMatch(/^#/);
      expect(tokens.dataVisualizationPalette).toHaveLength(8);
      // Typography axes
      expect(tokens.fontBody).toContain('"');
      expect(tokens.fontDisplay).toContain('"');
      expect(tokens.fontMono).toContain('"');
      // Shape + density + glow
      expect(typeof tokens.radius).toBe('number');
      expect(['sharp', 'pill']).toContain(tokens.chipShape);
      expect(['tight', 'airy']).toContain(tokens.density);
      expect(['none', 'soft', 'strong', 'heavy']).toContain(tokens.glow);
      expect(['brackets', 'terminal', 'editorial', 'sunset']).toContain(
        tokens.personality,
      );
    },
  );

  it('matches the Android Cyberpunk fingerprint (sharp / strong glow / brackets / tight)', () => {
    const t = PRISM_THEMES.CYBERPUNK;
    expect(t.primary).toBe('#00F5FF');
    expect(t.chipShape).toBe('sharp');
    expect(t.glow).toBe('strong');
    expect(t.personality).toBe('brackets');
    expect(t.density).toBe('tight');
    expect(t.displayUpper).toBe(true);
  });

  it('matches the Android Synthwave fingerprint (pill / heavy glow / sunset / airy)', () => {
    const t = PRISM_THEMES.SYNTHWAVE;
    expect(t.primary).toBe('#FF2D87');
    expect(t.chipShape).toBe('pill');
    expect(t.glow).toBe('heavy');
    expect(t.personality).toBe('sunset');
    expect(t.density).toBe('airy');
  });

  it('matches the Android Matrix fingerprint (zero radius / terminal / tight)', () => {
    const t = PRISM_THEMES.MATRIX;
    expect(t.primary).toBe('#00FF41');
    expect(t.radius).toBe(0);
    expect(t.cardRadius).toBe(0);
    expect(t.personality).toBe('terminal');
    expect(t.glow).toBe('soft');
  });

  it('matches the Android Void fingerprint (no glow / editorial / airy)', () => {
    const t = PRISM_THEMES.VOID;
    expect(t.primary).toBe('#C8B8FF');
    expect(t.personality).toBe('editorial');
    expect(t.glow).toBe('none');
    expect(t.displayUpper).toBe(false);
  });
});

describe('resolvePrismTheme', () => {
  it('returns the matching variant for a valid key', () => {
    for (const key of PRISM_THEME_ORDER) {
      expect(resolvePrismTheme(key).id).toBe(key);
    }
  });

  it('falls back to VOID for unknown keys (defensive against Firestore drift)', () => {
    expect(resolvePrismTheme('NOPE' as PrismThemeKey).id).toBe('VOID');
    expect(resolvePrismTheme('').id).toBe('VOID');
  });
});

describe('prismCssVar', () => {
  it('maps core color tokens to the legacy --color-* names used by Tailwind utilities', () => {
    expect(prismCssVar('primary')).toBe('--color-accent');
    expect(prismCssVar('background')).toBe('--color-bg-primary');
    expect(prismCssVar('surface')).toBe('--color-bg-card');
    expect(prismCssVar('border')).toBe('--color-border');
    expect(prismCssVar('onBackground')).toBe('--color-text-primary');
  });

  it('maps namespaced tokens to --prism-* custom properties', () => {
    expect(prismCssVar('successColor')).toBe('--prism-success');
    expect(prismCssVar('warningColor')).toBe('--prism-warning');
    expect(prismCssVar('destructiveColor')).toBe('--prism-destructive');
    expect(prismCssVar('fontDisplay')).toBe('--prism-font-display');
  });
});

describe('applyThemeToDocument', () => {
  beforeEach(() => {
    // jsdom hands back the same document across tests; reset the data
    // attribute + inline style so we observe only the change under test.
    document.documentElement.removeAttribute('data-theme');
    document.documentElement.removeAttribute('data-personality');
    document.documentElement.removeAttribute('data-density');
    document.documentElement.style.cssText = '';
  });

  it.each(PRISM_THEME_ORDER)(
    'broadcasts %s tokens to document-root CSS custom properties',
    (key) => {
      applyThemeToDocument(key);
      const root = document.documentElement;
      const tokens = PRISM_THEMES[key];

      expect(root.getAttribute('data-theme')).toBe(key);
      expect(root.getAttribute('data-personality')).toBe(tokens.personality);
      expect(root.getAttribute('data-density')).toBe(tokens.density);

      // Core palette wired to legacy --color-* names so existing
      // Tailwind classes pick it up without changes.
      expect(root.style.getPropertyValue('--color-bg-primary')).toBe(tokens.background);
      expect(root.style.getPropertyValue('--color-accent')).toBe(tokens.primary);
      expect(root.style.getPropertyValue('--color-text-primary')).toBe(tokens.onBackground);

      // Namespaced --prism-* tokens cover the Android-only axes.
      expect(root.style.getPropertyValue('--prism-success')).toBe(tokens.successColor);
      expect(root.style.getPropertyValue('--prism-font-body')).toBe(tokens.fontBody);
      expect(root.style.getPropertyValue('--prism-font-display')).toBe(tokens.fontDisplay);
      expect(root.style.getPropertyValue('--prism-card-radius')).toBe(`${tokens.cardRadius}px`);
    },
  );

  it('keeps the `.dark` class on so Tailwind `dark:` utilities still resolve', () => {
    applyThemeToDocument('CYBERPUNK');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });

  it('encodes glow intensity as a box-shadow color-mix() expression for `none` → "none"', () => {
    applyThemeToDocument('VOID');
    expect(document.documentElement.style.getPropertyValue('--prism-glow')).toBe('none');
  });

  it('encodes non-none glow intensity as a tinted box-shadow', () => {
    applyThemeToDocument('SYNTHWAVE');
    const glow = document.documentElement.style.getPropertyValue('--prism-glow');
    expect(glow).toContain('color-mix');
    // Primary color should be inlined (not a CSS var reference) so the
    // shadow re-tints when the theme key flips, not when the var
    // cascades — matches Android's static-per-theme glow constant.
    expect(glow).not.toContain('var(');
  });
});
