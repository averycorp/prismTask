import { describe, it, expect, beforeEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { useThemeStore } from '@/stores/themeStore';
import {
  PrismThemeProvider,
  useTheme,
} from '@/theme/PrismThemeProvider';

/**
 * React-Context wiring tests for [PrismThemeProvider]. Asserts:
 *  - context value tracks the zustand store
 *  - `setTheme` from context flips CSS custom properties on `<html>`
 *  - calling `useTheme()` outside a provider falls back to VOID
 */

function Probe() {
  const { themeKey, tokens, setTheme } = useTheme();
  return (
    <div>
      <span data-testid="key">{themeKey}</span>
      <span data-testid="primary">{tokens.primary}</span>
      <button onClick={() => setTheme('CYBERPUNK')}>flip</button>
    </div>
  );
}

describe('PrismThemeProvider', () => {
  beforeEach(() => {
    localStorage.clear();
    // Reset store to a known starting state. Use the public API so the
    // applyTheme side-effect fires.
    act(() => {
      useThemeStore.getState().setThemeKey('VOID');
    });
  });

  it('exposes the active themeKey + tokens via useTheme()', () => {
    render(
      <PrismThemeProvider>
        <Probe />
      </PrismThemeProvider>,
    );
    expect(screen.getByTestId('key').textContent).toBe('VOID');
    // Void's primary per Android Color.kt + ThemeColors.kt.
    expect(screen.getByTestId('primary').textContent).toBe('#C8B8FF');
  });

  it('flipping the theme through context updates document.documentElement', () => {
    render(
      <PrismThemeProvider>
        <Probe />
      </PrismThemeProvider>,
    );
    act(() => {
      screen.getByText('flip').click();
    });
    expect(screen.getByTestId('key').textContent).toBe('CYBERPUNK');
    expect(document.documentElement.getAttribute('data-theme')).toBe('CYBERPUNK');
    expect(document.documentElement.style.getPropertyValue('--color-accent')).toBe(
      '#00F5FF',
    );
    expect(document.documentElement.getAttribute('data-personality')).toBe('brackets');
  });

  it('useTheme() outside a provider falls back to VOID tokens (no crash)', () => {
    render(<Probe />);
    expect(screen.getByTestId('key').textContent).toBe('VOID');
    expect(screen.getByTestId('primary').textContent).toBe('#C8B8FF');
  });
});
