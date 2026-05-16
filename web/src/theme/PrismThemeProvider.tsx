import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  type ReactNode,
} from 'react';
import { useThemeStore } from '@/stores/themeStore';
import {
  applyThemeToDocument,
  DEFAULT_THEME_KEY,
  resolvePrismTheme,
  type PrismThemeKey,
  type PrismThemeTokens,
} from '@/theme/prismTheme';

/**
 * Shape exposed by [useTheme]. Mirrors Android's three CompositionLocals
 * collapsed into a single record so React consumers don't need a separate
 * hook per axis (colors / fonts / attrs):
 *  - `themeKey`:  the active variant identifier (CYBERPUNK | SYNTHWAVE | MATRIX | VOID)
 *  - `tokens`:    the full token bundle (colors + typography + shape + density + glow + personality)
 *  - `setTheme`:  zustand-backed setter that persists locally, fires the
 *                 Firestore write, and re-applies CSS custom properties to
 *                 the document root.
 */
export interface PrismThemeContextValue {
  themeKey: PrismThemeKey;
  tokens: PrismThemeTokens;
  setTheme: (key: PrismThemeKey) => void;
}

const PrismThemeContext = createContext<PrismThemeContextValue | null>(null);

/**
 * React provider that wires the 4-variant PrismTheme system into the app.
 * On mount + whenever the user picks a new variant, the provider:
 *  1. Reads the active key from [useThemeStore] (zustand v5 stable selectors).
 *  2. Re-applies every CSS custom property to `<html>` so Tailwind utilities
 *     and `.prism-*` helper classes pick up the new palette + fonts + shape
 *     + glow + density.
 *  3. Resolves the matching [PrismThemeTokens] bundle and broadcasts it
 *     through React Context for components that need the raw values
 *     (e.g. `<svg>` strokes, canvas charts).
 *
 * Drop it once around `<RouterProvider>` in `App.tsx`. The legacy
 * `useTheme()` hook in `web/src/hooks/useTheme.ts` continues to work but
 * is now redundant — prefer importing from `@/theme/PrismThemeProvider`.
 */
export function PrismThemeProvider({ children }: { children: ReactNode }) {
  const themeKey = useThemeStore((s) => s.themeKey);
  const setTheme = useThemeStore((s) => s.setThemeKey);

  // Apply CSS custom properties on mount and whenever the key flips. Each
  // of the four named themes is dark-first with no system/light variant,
  // so we don't need a `prefers-color-scheme` listener.
  useEffect(() => {
    applyThemeToDocument(themeKey);
  }, [themeKey]);

  // Zustand v5 selectors must return stable refs (see
  // feedback_zustand_selector_must_return_stable_ref). We build the
  // context value inside `useMemo` so identity only changes when
  // `themeKey` or the setter actually flips — otherwise every render
  // would invalidate consumers and trigger React #185 in production.
  const value = useMemo<PrismThemeContextValue>(
    () => ({
      themeKey,
      tokens: resolvePrismTheme(themeKey),
      setTheme,
    }),
    [themeKey, setTheme],
  );

  return (
    <PrismThemeContext.Provider value={value}>
      {children}
    </PrismThemeContext.Provider>
  );
}

/**
 * Read the active theme. Falls back to the [DEFAULT_THEME_KEY] tokens when
 * called outside a [PrismThemeProvider] so unit tests, Storybook, and
 * isolated previews don't crash.
 */
// eslint-disable-next-line react-refresh/only-export-components -- parity batch follow-up; see #1573
export function useTheme(): PrismThemeContextValue {
  const ctx = useContext(PrismThemeContext);
  if (ctx) return ctx;
  return {
    themeKey: DEFAULT_THEME_KEY,
    tokens: resolvePrismTheme(DEFAULT_THEME_KEY),
    setTheme: () => {
      // No-op outside a provider. Prefer wrapping the app in
      // <PrismThemeProvider> in App.tsx; the standalone-context default
      // exists only so `useTheme()` doesn't throw under jsdom / RTL.
    },
  };
}
