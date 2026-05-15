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
    // gracePeriodDays default 7 → "7 days". The screen now also renders
    // per-mode strictness sliders so we anchor on the base slider's
    // accessible name to disambiguate.
    const base = screen.getByLabelText('Grace Window') as HTMLInputElement;
    expect(base.value).toBe('7');
    // allowedMisses default 1 → "1 miss" via the base slider.
    const misses = screen.getByLabelText('Allowed Misses') as HTMLInputElement;
    expect(misses.value).toBe('1');
  });

  it('debounces the gracePeriodDays slider before persisting', () => {
    render(<AdvancedTuningSection />);
    const slider = screen.getByLabelText('Grace Window') as HTMLInputElement;
    expect(slider).toBeTruthy();

    // Drag the slider — the local readout updates instantly but the
    // Firestore push waits for the debounce.
    fireEvent.change(slider, { target: { value: '14' } });
    expect(patchMock).not.toHaveBeenCalled();
    // The base slider's local readout updated even though per-mode
    // sliders may also display "14 days". Check the slider value
    // directly rather than via getByText to avoid the per-mode
    // duplicate-text collision.
    expect(slider.value).toBe('14');

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
    // Per-mode sliders also disable when the master switch is off.
    const playGrace = screen.getByLabelText(
      'Play grace window',
    ) as HTMLInputElement;
    const relaxMisses = screen.getByLabelText(
      'Relax allowed misses',
    ) as HTMLInputElement;
    expect(playGrace.disabled).toBe(true);
    expect(relaxMisses.disabled).toBe(true);
  });

  it('renders per-mode strictness sliders with the wider Play / Relax defaults', () => {
    render(<AdvancedTuningSection />);
    // Work falls back to the base 7/1 window.
    const workGrace = screen.getByLabelText(
      'Work grace window',
    ) as HTMLInputElement;
    const workMisses = screen.getByLabelText(
      'Work allowed misses',
    ) as HTMLInputElement;
    expect(workGrace.value).toBe('7');
    expect(workMisses.value).toBe('1');

    // Play + Relax get the wider 14/2 defaults from
    // `docs/WORK_PLAY_RELAX.md` § *Streak strictness*.
    const playGrace = screen.getByLabelText(
      'Play grace window',
    ) as HTMLInputElement;
    const playMisses = screen.getByLabelText(
      'Play allowed misses',
    ) as HTMLInputElement;
    const relaxGrace = screen.getByLabelText(
      'Relax grace window',
    ) as HTMLInputElement;
    const relaxMisses = screen.getByLabelText(
      'Relax allowed misses',
    ) as HTMLInputElement;
    expect(playGrace.value).toBe('14');
    expect(playMisses.value).toBe('2');
    expect(relaxGrace.value).toBe('14');
    expect(relaxMisses.value).toBe('2');
  });

  it('persists a per-mode slider tweak without clobbering siblings', () => {
    render(<AdvancedTuningSection />);
    const playGrace = screen.getByLabelText(
      'Play grace window',
    ) as HTMLInputElement;
    fireEvent.change(playGrace, { target: { value: '21' } });
    act(() => {
      vi.advanceTimersByTime(250);
    });
    expect(patchMock).toHaveBeenCalledWith('uid-123', {
      forgivenessByMode: { play: { gracePeriodDays: 21 } },
    });
    // Local merge: only Play.grace changed; Play.misses + Work + Relax
    // all keep their seed values.
    const prefs = useAdvancedTuningStore.getState().prefs;
    expect(prefs.forgivenessByMode.play.gracePeriodDays).toBe(21);
    expect(prefs.forgivenessByMode.play.allowedMisses).toBe(2);
    expect(prefs.forgivenessByMode.work.gracePeriodDays).toBe(7);
    expect(prefs.forgivenessByMode.relax.gracePeriodDays).toBe(14);
  });
});
