import { render, screen, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { OfflineBanner } from '../OfflineBanner';

describe('OfflineBanner', () => {
  let originalOnLine: boolean;

  beforeEach(() => {
    originalOnLine = window.navigator.onLine;
  });

  afterEach(() => {
    Object.defineProperty(window.navigator, 'onLine', {
      value: originalOnLine,
      writable: true,
    });
    vi.restoreAllMocks();
  });

  it('does not render when online', () => {
    Object.defineProperty(window.navigator, 'onLine', { value: true, writable: true });
    const { container } = render(<OfflineBanner />);
    expect(container.firstChild).toBeNull();
  });

  it('renders when offline initially', () => {
    Object.defineProperty(window.navigator, 'onLine', { value: false, writable: true });
    render(<OfflineBanner />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(/You're offline/)).toBeInTheDocument();
  });

  it('shows banner when going offline', () => {
    Object.defineProperty(window.navigator, 'onLine', { value: true, writable: true });
    render(<OfflineBanner />);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    act(() => {
      window.dispatchEvent(new Event('offline'));
    });

    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('hides banner when going online', () => {
    Object.defineProperty(window.navigator, 'onLine', { value: false, writable: true });
    render(<OfflineBanner />);
    expect(screen.getByRole('alert')).toBeInTheDocument();

    act(() => {
      window.dispatchEvent(new Event('online'));
    });

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
