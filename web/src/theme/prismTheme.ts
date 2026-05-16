/**
 * Public entry point for the 4-variant PrismTheme system (parity unit 4 of
 * 23). Re-exports the variant catalog, ThemeKey type, default, and runtime
 * helpers from the underlying `themes.ts` module so consumers can import a
 * single, stable surface:
 *
 *     import { PRISM_THEMES, type PrismThemeTokens } from '@/theme/prismTheme';
 *
 * The token type and variant defs intentionally live in `themes.ts` (the
 * "ported from `themesets/themes.js`" parity record). This file gives the
 * variant system a clean Android-style name (`prismTheme`) and adds opt-in
 * convenience helpers used by [PrismThemeProvider] and the appearance
 * picker UI.
 *
 * Mirrors Android's `app/.../ui/theme/PrismTheme.kt` +
 * `ThemeColors.kt` + `ThemeFonts.kt` + `PrismThemeAttrs.kt`.
 */

import {
  applyThemeToDocument,
  DEFAULT_THEME_KEY,
  migrateLegacyAccentToThemeKey,
  THEMES,
  THEME_ORDER,
  type ThemeKey,
  type ThemeTokens,
} from './themes';

export {
  applyThemeToDocument,
  DEFAULT_THEME_KEY,
  migrateLegacyAccentToThemeKey,
  THEMES as PRISM_THEMES,
  THEME_ORDER as PRISM_THEME_ORDER,
};

export type PrismThemeKey = ThemeKey;
export type PrismThemeTokens = ThemeTokens;

/**
 * Resolve the variant tokens for a [PrismThemeKey]. Stable across calls —
 * callers can `===`-compare results. Falls back to [DEFAULT_THEME_KEY]
 * when given an unknown key so out-of-range Firestore values never crash
 * the app.
 */
export function resolvePrismTheme(key: PrismThemeKey | string): PrismThemeTokens {
  if (key in THEMES) return THEMES[key as PrismThemeKey];
  return THEMES[DEFAULT_THEME_KEY];
}

/**
 * Lookup table for tokens that are broadcast to document-root CSS custom
 * properties by [applyThemeToDocument]. Surface palette + accent map onto
 * the legacy `--color-*` names so existing Tailwind utilities pick them
 * up; everything else lives under the namespaced `--prism-*`. Tokens
 * absent from the map (id, label, tagline, palette array, shape flags)
 * fall back to a derived `--prism-<name>` name — components that need
 * those read directly from the resolved `tokens` object instead.
 */
const PRISM_CSS_VAR_MAP: Partial<Record<keyof PrismThemeTokens, string>> = {
  background: '--color-bg-primary',
  surface: '--color-bg-card',
  surfaceVariant: '--color-bg-secondary',
  onBackground: '--color-text-primary',
  onSurface: '--color-text-secondary',
  border: '--color-border',
  primary: '--color-accent',
  secondary: '--prism-secondary',
  muted: '--prism-muted',
  urgentAccent: '--prism-urgent-accent',
  urgentSurface: '--prism-urgent-surface',
  tagSurface: '--prism-tag-surface',
  tagText: '--prism-tag-text',
  successColor: '--prism-success',
  warningColor: '--prism-warning',
  destructiveColor: '--prism-destructive',
  infoColor: '--prism-info',
  quadrantQ1: '--prism-quadrant-q1',
  quadrantQ2: '--prism-quadrant-q2',
  quadrantQ3: '--prism-quadrant-q3',
  quadrantQ4: '--prism-quadrant-q4',
  fontBody: '--prism-font-body',
  fontDisplay: '--prism-font-display',
  fontMono: '--prism-font-mono',
};

/**
 * Compute the CSS custom-property name for a token field. Useful when a
 * component wants to read a token via `getComputedStyle` for canvas
 * rendering or pixel measurements (Tailwind v4 picks the same names up via
 * `var(--prism-*)`).
 */
export function prismCssVar(name: keyof PrismThemeTokens): string {
  return PRISM_CSS_VAR_MAP[name] ?? `--prism-${String(name)}`;
}
