import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act, cleanup } from '@testing-library/react';
import { fireEvent } from '@testing-library/react';

// In-memory localStorage shim so the settings store doesn't blow up on
// init. Mirrors `habitStore.startOfDayHour.test.ts`'s hoisted shim.
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

const { patchMock } = vi.hoisted(() => ({
  patchMock: vi.fn(async () => undefined),
}));

vi.mock('@/api/firestore/advancedTuningPreferences', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/advancedTuningPreferences')
  >('@/api/firestore/advancedTuningPreferences');
  return {
    ...actual,
    getAdvancedTuningPreferences: vi.fn(async () => actual.DEFAULT_ADVANCED_TUNING_PREFERENCES),
    patchAdvancedTuningPreferences: patchMock,
    subscribeToAdvancedTuningPreferences: vi.fn(() => () => undefined),
  };
});

vi.mock('@/stores/authStore', () => ({
  useAuthStore: <T,>(selector: (s: { firebaseUid: string }) => T) =>
    selector({ firebaseUid: 'uid-123' }),
}));

import { AdvancedTuningSection } from '@/features/settings/sections/AdvancedTuningSection';
import { useAdvancedTuningStore } from '@/stores/advancedTuningStore';
import { DEFAULT_ADVANCED_TUNING_PREFERENCES } from '@/api/firestore/advancedTuningPreferences';

describe('AdvancedTuningSection', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    patchMock.mockClear();
    useAdvancedTuningStore.setState({
      prefs: DEFAULT_ADVANCED_TUNING_PREFERENCES,
      loaded: true,
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    cleanup();
  });

  it('renders forgiveness sliders with current values', () => {
    render(<AdvancedTuningSection />);
    // gracePeriodDays default 7 → "7 days"
    expect(screen.getByText('7 days')).toBeTruthy();
    // allowedMisses default 1 → "1 miss"
    expect(screen.getByText('1 miss')).toBeTruthy();
  });

  it('debounces the gracePeriodDays slider before persisting', () => {
    render(<AdvancedTuningSection />);
    const slider = screen.getByLabelText('Grace Window') as HTMLInputElement;
    expect(slider).toBeTruthy();

    // Drag the slider — the local readout updates instantly but the
    // Firestore push waits for the debounce.
    fireEvent.change(slider, { target: { value: '14' } });
    expect(patchMock).not.toHaveBeenCalled();
    expect(screen.getByText('14 days')).toBeTruthy();

    // Advance through the 200ms debounce.
    act(() => {
      vi.advanceTimersByTime(250);
    });
    expect(patchMock).toHaveBeenCalledTimes(1);
    expect(patchMock).toHaveBeenCalledWith('uid-123', { gracePeriodDays: 14 });
    expect(useAdvancedTuningStore.getState().prefs.gracePeriodDays).toBe(14);
  });

  it('persists allowedMisses through the debounced slider path', () => {
    render(<AdvancedTuningSection />);
    const slider = screen.getByLabelText('Allowed Misses') as HTMLInputElement;
    fireEvent.change(slider, { target: { value: '3' } });
    act(() => {
      vi.advanceTimersByTime(250);
    });
    expect(patchMock).toHaveBeenCalledWith('uid-123', { allowedMisses: 3 });
    expect(useAdvancedTuningStore.getState().prefs.allowedMisses).toBe(3);
  });

  it('persists a per-tier task-mode keyword without clobbering siblings', () => {
    useAdvancedTuningStore.setState({
      prefs: {
        ...DEFAULT_ADVANCED_TUNING_PREFERENCES,
        taskModeKeywords: { work: 'a', play: 'b', relax: 'c' },
      },
      loaded: true,
    });
    render(<AdvancedTuningSection />);

    const workArea = screen.getByLabelText('Work keywords') as HTMLTextAreaElement;
    fireEvent.change(workArea, { target: { value: 'meeting, deck' } });
    act(() => {
      vi.advanceTimersByTime(450);
    });

    expect(patchMock).toHaveBeenCalledWith('uid-123', {
      taskModeKeywords: { work: 'meeting, deck' },
    });
    // Local merge preserves the siblings even though only `work` was patched.
    const prefs = useAdvancedTuningStore.getState().prefs;
    expect(prefs.taskModeKeywords).toEqual({
      work: 'meeting, deck',
      play: 'b',
      relax: 'c',
    });
  });

  it('disables the sliders when forgiveness is off', () => {
    useAdvancedTuningStore.setState({
      prefs: { ...DEFAULT_ADVANCED_TUNING_PREFERENCES, forgivenessEnabled: false },
      loaded: true,
    });
    render(<AdvancedTuningSection />);
    const grace = screen.getByLabelText('Grace Window') as HTMLInputElement;
    const misses = screen.getByLabelText('Allowed Misses') as HTMLInputElement;
    expect(grace.disabled).toBe(true);
    expect(misses.disabled).toBe(true);
  });
});
