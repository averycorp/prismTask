import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// Vitest 4 + jsdom 29 ship a non-functional `localStorage` stub. The
// settingsStore writes to localStorage on import, so we install the
// same in-memory shim other store tests use before any module loads.
vi.hoisted(() => {
  const existing = (globalThis as { localStorage?: Storage }).localStorage;
  if (existing && typeof existing.clear === 'function') return;
  const backing = new Map<string, string>();
  const shim: Storage = {
    get length() {
      return backing.size;
    },
    clear: () => backing.clear(),
    getItem: (key: string) => (backing.has(key) ? backing.get(key)! : null),
    key: (index: number) => Array.from(backing.keys())[index] ?? null,
    removeItem: (key: string) => {
      backing.delete(key);
    },
    setItem: (key: string, value: string) => {
      backing.set(key, String(value));
    },
  };
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: shim,
  });
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: shim,
    });
  }
});

// vi.hoisted because vi.mock factories are hoisted to the top of the
// file and can't capture closure-level `const`s otherwise.
const { markSpy, unmarkSpy, subscribeSpy } = vi.hoisted(() => ({
  markSpy: vi.fn(async () => undefined),
  unmarkSpy: vi.fn(async () => undefined),
  subscribeSpy: vi.fn(() => () => undefined),
}));
vi.mock('@/api/firestore/restDays', () => ({
  markRestDay: markSpy,
  unmarkRestDay: unmarkSpy,
  subscribeToRestDays: subscribeSpy,
}));
vi.mock('@/api/firestore/aiPreferences', () => ({
  DEFAULT_AI_FEATURES_ENABLED: true,
  getAiFeaturesEnabled: vi.fn(),
  setAiFeaturesEnabled: vi.fn(),
}));
vi.mock('@/api/firestore/taskBehaviorPreferences', () => ({
  DEFAULT_DAY_START_HOUR: 0,
  getDayStartHour: vi.fn(),
  setDayStartHour: vi.fn(),
  subscribeToDayStartHour: vi.fn(),
}));
vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: vi.fn(() => 'test-uid'),
  setFirebaseUid: vi.fn(),
}));
vi.mock('@/api/client', () => ({
  default: {},
  setAiFeaturesEnabledProvider: vi.fn(),
}));

import { RestDayBanner } from '@/features/today/RestDayBanner';
import { useRestDayStore } from '@/stores/restDayStore';
import { useSettingsStore } from '@/stores/settingsStore';

/**
 * Component tests for the Today-screen Rest Day banner. Verifies the
 * two render states + the dialog copy. The copy assertions are
 * deliberately strict against `docs/REST_DAY.md` § *Copy guidelines* —
 * the prescriptive / clinical / shaming variants listed there must
 * NEVER appear, even after a future refactor.
 */
describe('RestDayBanner', () => {
  beforeEach(() => {
    useSettingsStore.setState({ startOfDayHour: 0 });
    useRestDayStore.setState({ restDates: new Set<string>() });
    markSpy.mockClear();
    unmarkSpy.mockClear();
  });

  it('default state: renders the mark-as-rest-day affordance, not the takeover', () => {
    render(<RestDayBanner />);
    expect(
      screen.getByRole('button', { name: /mark today as a rest day/i }),
    ).toBeInTheDocument();
    // Takeover heading is not rendered when not resting.
    expect(
      screen.queryByText(/resting today — see you tomorrow/i),
    ).not.toBeInTheDocument();
  });

  it('takeover state: renders the soft header with the rest-day copy + End Rest Day action', () => {
    // Use the current logical date so todayIso() inside the component
    // agrees with the set-state we install before rendering. No need
    // to mock the clock — `Date.now()` reads the real wall clock and
    // the component is only mounted once.
    const todayIso = useRestDayStore.getState().todayIso();
    useRestDayStore.setState({ restDates: new Set([todayIso]) });

    render(<RestDayBanner />);

    // Headline copy (Title-cased to match the rest of the app's copy
    // convention, but the docs say the lowercase version is the
    // canonical exemplar — both shapes are acceptable as long as the
    // substring "Resting" + "see you tomorrow" appears).
    expect(
      screen.getByRole('heading', { name: /resting today/i }),
    ).toHaveTextContent(/see you tomorrow/i);
    // Streak-safe secondary line.
    expect(screen.getByText(/habit streaks stay safe/i)).toBeInTheDocument();
    // End-rest-day button.
    expect(
      screen.getByRole('button', { name: /end rest day/i }),
    ).toBeInTheDocument();
  });

  it('opens the confirmation dialog with the non-leading question + Not Yet / Yes pair', async () => {
    const user = userEvent.setup();
    render(<RestDayBanner />);

    await user.click(
      screen.getByRole('button', { name: /mark today as a rest day/i }),
    );

    // Dialog title — non-leading question (matches REST_DAY.md:153).
    expect(
      screen.getByRole('heading', { name: /mark today as a rest day\?/i }),
    ).toBeInTheDocument();
    // Symmetric, descriptive button copy (matches REST_DAY.md:162-164).
    expect(
      screen.getByRole('button', { name: /yes, rest today/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /not yet/i }),
    ).toBeInTheDocument();
  });

  it('rejects banned copy: nothing prescriptive / clinical / shaming / gamified', () => {
    // Render both states and verify the bad-copy list from
    // REST_DAY.md never appears regardless of which state we're in.

    // Default state.
    const { unmount: unmountDefault } = render(<RestDayBanner />);
    const bad = [
      /you should rest today/i,
      /you need a break/i,
      /take a sick day/i,
      /don't be lazy/i,
      /you earned this/i,
      /you missed too many days/i,
    ];
    for (const re of bad) {
      expect(screen.queryByText(re)).not.toBeInTheDocument();
    }
    unmountDefault();

    // Takeover state.
    const todayIso = useRestDayStore.getState().todayIso();
    useRestDayStore.setState({ restDates: new Set([todayIso]) });
    render(<RestDayBanner />);
    for (const re of bad) {
      expect(screen.queryByText(re)).not.toBeInTheDocument();
    }
  });

  it('Yes, rest today calls markToday and closes the dialog', async () => {
    const user = userEvent.setup();
    render(<RestDayBanner />);

    await user.click(
      screen.getByRole('button', { name: /mark today as a rest day/i }),
    );
    await user.click(screen.getByRole('button', { name: /yes, rest today/i }));

    await waitFor(() => {
      expect(markSpy).toHaveBeenCalledTimes(1);
    });
    // Optimistic local update + dialog dismissal.
    expect(
      screen.queryByRole('heading', { name: /mark today as a rest day\?/i }),
    ).not.toBeInTheDocument();
  });

  it('Not yet dismisses the dialog without writing', async () => {
    const user = userEvent.setup();
    render(<RestDayBanner />);

    await user.click(
      screen.getByRole('button', { name: /mark today as a rest day/i }),
    );
    await user.click(screen.getByRole('button', { name: /not yet/i }));

    expect(markSpy).not.toHaveBeenCalled();
    expect(
      screen.queryByRole('heading', { name: /mark today as a rest day\?/i }),
    ).not.toBeInTheDocument();
  });

  it('End Rest Day calls unmarkToday from the takeover state', async () => {
    // Use the current real-time logical date so todayIso() inside the
    // component agrees with the set-state we install before rendering.
    // The component is only mounted once and doesn't re-tick the clock,
    // so we don't need fake timers here.
    const todayIso = useRestDayStore.getState().todayIso();
    useRestDayStore.setState({ restDates: new Set([todayIso]) });

    const user = userEvent.setup();
    render(<RestDayBanner />);

    await user.click(screen.getByRole('button', { name: /end rest day/i }));

    await waitFor(() => {
      expect(unmarkSpy).toHaveBeenCalledTimes(1);
    });
  });
});
