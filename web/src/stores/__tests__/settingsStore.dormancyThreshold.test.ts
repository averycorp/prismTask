import { describe, it, expect, vi, beforeEach } from 'vitest';

// In-memory localStorage shim (see settingsStore.startOfDayHour.test.ts for
// the rationale — vitest's stub lacks setItem).
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
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: shim });
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'localStorage', { configurable: true, value: shim });
  }
});

const {
  setDormancyMock,
  getDormancyMock,
  subscribeDormancyMock,
  getFirebaseUidMock,
} = vi.hoisted(() => ({
  setDormancyMock: vi.fn(),
  getDormancyMock: vi.fn(),
  subscribeDormancyMock: vi.fn(),
  getFirebaseUidMock: vi.fn(),
}));

vi.mock('@/api/firestore/dormancyPreferences', () => ({
  DEFAULT_DORMANCY_THRESHOLD_DAYS: 7,
  getDormancyThresholdDays: getDormancyMock,
  setDormancyThresholdDays: setDormancyMock,
  subscribeToDormancyThresholdDays: subscribeDormancyMock,
}));
vi.mock('@/api/firestore/taskBehaviorPreferences', () => ({
  DEFAULT_DAY_START_HOUR: 0,
  getDayStartHour: vi.fn(),
  setDayStartHour: vi.fn(),
  subscribeToDayStartHour: vi.fn(),
}));
vi.mock('@/api/firestore/aiPreferences', () => ({
  DEFAULT_AI_FEATURES_ENABLED: true,
  getAiFeaturesEnabled: vi.fn(),
  setAiFeaturesEnabled: vi.fn(),
}));
vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: getFirebaseUidMock,
  setFirebaseUid: vi.fn(),
}));
vi.mock('@/api/client', () => ({
  default: {},
  setAiFeaturesEnabledProvider: vi.fn(),
}));

import { useSettingsStore } from '@/stores/settingsStore';

beforeEach(() => {
  setDormancyMock.mockReset();
  getDormancyMock.mockReset();
  subscribeDormancyMock.mockReset();
  getFirebaseUidMock.mockReset();
  useSettingsStore.setState({ dormancyThresholdDays: 7 });
});

describe('settingsStore — dormancyThresholdDays', () => {
  it('defaults to 7', () => {
    expect(useSettingsStore.getState().dormancyThresholdDays).toBe(7);
  });

  it('setSetting updates local state and pushes to Firestore when signed in', async () => {
    getFirebaseUidMock.mockReturnValue('uid-1');
    setDormancyMock.mockResolvedValueOnce(undefined);

    useSettingsStore.getState().setSetting('dormancyThresholdDays', 14);
    expect(useSettingsStore.getState().dormancyThresholdDays).toBe(14);

    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));

    expect(setDormancyMock).toHaveBeenCalledWith('uid-1', 14);
  });

  it('subscribe applies remote updates within one tick', () => {
    let listener: ((days: number) => void) | undefined;
    const unsub = vi.fn();
    subscribeDormancyMock.mockImplementation((_uid, cb) => {
      listener = cb as (days: number) => void;
      return unsub;
    });

    useSettingsStore.getState().subscribeToDormancyThreshold('uid-1');
    listener?.(21);
    expect(useSettingsStore.getState().dormancyThresholdDays).toBe(21);
  });

  it('round-trips: setSetting writes, load reads back the same value', async () => {
    getFirebaseUidMock.mockReturnValue('uid-rt');
    let captured: number | null = null;
    setDormancyMock.mockImplementation(async (_uid, v) => {
      captured = v;
    });
    getDormancyMock.mockImplementation(async () => captured ?? 7);

    useSettingsStore.getState().setSetting('dormancyThresholdDays', 30);
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
    expect(captured).toBe(30);

    useSettingsStore.setState({ dormancyThresholdDays: 7 });
    await useSettingsStore.getState().loadDormancyThresholdFromFirestore('uid-rt');
    expect(useSettingsStore.getState().dormancyThresholdDays).toBe(30);
  });
});
