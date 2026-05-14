import { describe, it, expect, vi, beforeEach } from 'vitest';

// Vitest 4 + jsdom 29 ship `localStorage` as a non-functional stub
// (the `--localstorage-file` regression — emits a warning at startup).
// The settingsStore writes to localStorage on every `setSetting` call
// and reads from it on module load, so without a shim every
// `setSetting` path would crash with `localStorage.setItem is not a
// function`. Install a minimal in-memory shim via `vi.hoisted` so it
// runs BEFORE the store import below evaluates. The upstream
// `src/test/setup.ts` doesn't ship this fix on `main`; landing it
// in-file here keeps the scope of this PR tight (parity audit A.5a).
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

const {
  setDayStartHourMock,
  getDayStartHourMock,
  subscribeToDayStartHourMock,
  getFirebaseUidMock,
} = vi.hoisted(() => ({
  setDayStartHourMock: vi.fn(),
  getDayStartHourMock: vi.fn(),
  subscribeToDayStartHourMock: vi.fn(),
  getFirebaseUidMock: vi.fn(),
}));

vi.mock('@/api/firestore/taskBehaviorPreferences', () => ({
  DEFAULT_DAY_START_HOUR: 0,
  getDayStartHour: getDayStartHourMock,
  setDayStartHour: setDayStartHourMock,
  subscribeToDayStartHour: subscribeToDayStartHourMock,
}));
// The AI-features Firestore module is imported at module load; stub it so we
// don't spin up Firebase on test startup.
vi.mock('@/api/firestore/aiPreferences', () => ({
  DEFAULT_AI_FEATURES_ENABLED: true,
  getAiFeaturesEnabled: vi.fn(),
  setAiFeaturesEnabled: vi.fn(),
}));
vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: getFirebaseUidMock,
  setFirebaseUid: vi.fn(),
}));
// Don't need real axios in these tests.
vi.mock('@/api/client', () => ({
  default: {},
  setAiFeaturesEnabledProvider: vi.fn(),
}));

import { useSettingsStore } from '@/stores/settingsStore';

beforeEach(() => {
  setDayStartHourMock.mockReset();
  getDayStartHourMock.mockReset();
  subscribeToDayStartHourMock.mockReset();
  getFirebaseUidMock.mockReset();
  useSettingsStore.setState({ startOfDayHour: 0 });
});

describe('settingsStore — startOfDayHour', () => {
  it('defaults to 0 (midnight — matches Android DAY_START_HOUR)', () => {
    expect(useSettingsStore.getState().startOfDayHour).toBe(0);
  });

  it('setSetting updates local state immediately (optimistic)', () => {
    useSettingsStore.getState().setSetting('startOfDayHour', 5);
    expect(useSettingsStore.getState().startOfDayHour).toBe(5);
  });

  it('setSetting pushes the new value to Firestore when signed in', async () => {
    getFirebaseUidMock.mockReturnValue('uid-1');
    setDayStartHourMock.mockResolvedValueOnce(undefined);

    useSettingsStore.getState().setSetting('startOfDayHour', 7);

    // Fire-and-forget via microtask + dynamic import; flush.
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));

    expect(setDayStartHourMock).toHaveBeenCalledTimes(1);
    expect(setDayStartHourMock).toHaveBeenCalledWith('uid-1', 7);
  });

  it('does NOT push to Firestore when signed out (local-only)', async () => {
    getFirebaseUidMock.mockImplementation(() => {
      throw new Error('Not authenticated');
    });

    useSettingsStore.getState().setSetting('startOfDayHour', 4);
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));

    expect(setDayStartHourMock).not.toHaveBeenCalled();
    expect(useSettingsStore.getState().startOfDayHour).toBe(4);
  });

  it('Firestore push failure does NOT roll back the local update', async () => {
    getFirebaseUidMock.mockReturnValue('uid-1');
    setDayStartHourMock.mockRejectedValueOnce(new Error('network down'));
    const consoleWarnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    useSettingsStore.getState().setSetting('startOfDayHour', 9);
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));

    expect(useSettingsStore.getState().startOfDayHour).toBe(9);
    expect(consoleWarnSpy).toHaveBeenCalled();
    consoleWarnSpy.mockRestore();
  });

  it('loadStartOfDayHourFromFirestore pulls remote value and updates local state', async () => {
    getDayStartHourMock.mockResolvedValueOnce(11);

    await useSettingsStore.getState().loadStartOfDayHourFromFirestore('uid-99');

    expect(getDayStartHourMock).toHaveBeenCalledWith('uid-99');
    expect(useSettingsStore.getState().startOfDayHour).toBe(11);
  });

  it('loadStartOfDayHourFromFirestore swallows read errors (offline tolerance)', async () => {
    getDayStartHourMock.mockRejectedValueOnce(new Error('offline'));
    const consoleWarnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    useSettingsStore.setState({ startOfDayHour: 3 });

    await expect(
      useSettingsStore.getState().loadStartOfDayHourFromFirestore('uid-99'),
    ).resolves.toBeUndefined();

    expect(useSettingsStore.getState().startOfDayHour).toBe(3);
    expect(consoleWarnSpy).toHaveBeenCalled();
    consoleWarnSpy.mockRestore();
  });

  it('subscribeToStartOfDayHour applies remote updates to local state', () => {
    let listener: ((hour: number) => void) | undefined;
    const unsubSpy = vi.fn();
    subscribeToDayStartHourMock.mockImplementation((_uid, cb) => {
      listener = cb as (hour: number) => void;
      return unsubSpy;
    });

    const unsub = useSettingsStore.getState().subscribeToStartOfDayHour('uid-1');
    expect(subscribeToDayStartHourMock).toHaveBeenCalledWith('uid-1', expect.any(Function));

    listener?.(8);
    expect(useSettingsStore.getState().startOfDayHour).toBe(8);

    unsub();
    expect(unsubSpy).toHaveBeenCalledTimes(1);
  });

  it('round-trip: setSetting writes Firestore, load reads back the same value', async () => {
    getFirebaseUidMock.mockReturnValue('uid-rt');
    let capturedWrite: number | null = null;
    setDayStartHourMock.mockImplementation(async (_uid, v) => {
      capturedWrite = v;
    });
    getDayStartHourMock.mockImplementation(async () => capturedWrite ?? 0);

    useSettingsStore.getState().setSetting('startOfDayHour', 6);
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
    expect(capturedWrite).toBe(6);

    // Simulate fresh page load: clobber local state then pull.
    useSettingsStore.setState({ startOfDayHour: 0 });
    await useSettingsStore.getState().loadStartOfDayHourFromFirestore('uid-rt');
    expect(useSettingsStore.getState().startOfDayHour).toBe(6);
  });
});
