import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Vitest 4 + jsdom 29 ship a non-functional `localStorage` stub. The
// settingsStore writes to localStorage on every `setSetting` call, so we
// install a minimal in-memory shim before any store imports run. Mirrors
// the shim used in `habitStore.startOfDayHour.test.ts` and
// `leisureStore.startOfDayHour.test.ts`.
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

// Stub the Firestore-backed rest-day API + ancillary modules pulled in
// transitively. The store reaches `markRestDay` / `unmarkRestDay` for
// write paths; we capture the call args via vi.fn() so the tests can
// assert what was sent to Firestore without standing up an emulator.
// Use vi.hoisted so the spies are initialized before vi.mock's factory
// runs (vi.mock is hoisted to the top of the file).
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

import { useRestDayStore } from '@/stores/restDayStore';
import { useSettingsStore } from '@/stores/settingsStore';

describe('restDayStore — SoD-aware date resolution', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    useSettingsStore.setState({ startOfDayHour: 0 });
    useRestDayStore.setState({ restDates: new Set<string>() });
    markSpy.mockClear();
    unmarkSpy.mockClear();
    subscribeSpy.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /**
   * Parity with Android's `RestDayRepository.markTodayAsRestDay` —
   * the row key comes from `DayBoundary.currentLocalDateString` using
   * the user's SoD, not from system midnight. A user with SoD = 4
   * tapping the toggle at 02:30 local on 2026-05-14 marks
   * *2026-05-13's* logical day, because 02:30 is before today's SoD
   * (04:00) so the logical day still belongs to yesterday.
   */
  it('todayIso honours SoD: SoD=4 at 02:30 local resolves to yesterday', () => {
    vi.setSystemTime(new Date(2026, 4, 14, 2, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 4 });

    expect(useRestDayStore.getState().todayIso()).toBe('2026-05-13');
  });

  it('todayIso honours SoD: SoD=4 at 05:30 local resolves to today', () => {
    vi.setSystemTime(new Date(2026, 4, 14, 5, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 4 });

    expect(useRestDayStore.getState().todayIso()).toBe('2026-05-14');
  });

  it('todayIso with SoD=0 (default) is the calendar date', () => {
    vi.setSystemTime(new Date(2026, 4, 14, 2, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 0 });

    expect(useRestDayStore.getState().todayIso()).toBe('2026-05-14');
  });

  it('isRestDayToday reflects the live set keyed by SoD-aware date', () => {
    vi.setSystemTime(new Date(2026, 4, 14, 2, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 4 });
    // Logical today = 2026-05-13.
    useRestDayStore.setState({ restDates: new Set(['2026-05-13']) });
    expect(useRestDayStore.getState().isRestDayToday()).toBe(true);

    // A row keyed by the *calendar* date (today's 14th) must NOT count
    // as "today" when the logical day is the 13th — this is the bug a
    // naive `new Date().toISOString()` lookup would have.
    useRestDayStore.setState({ restDates: new Set(['2026-05-14']) });
    expect(useRestDayStore.getState().isRestDayToday()).toBe(false);
  });

  it('markToday writes the SoD-aware ISO to Firestore', async () => {
    vi.setSystemTime(new Date(2026, 4, 14, 2, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 4 });

    await useRestDayStore.getState().markToday();

    expect(markSpy).toHaveBeenCalledTimes(1);
    expect(markSpy).toHaveBeenCalledWith('test-uid', '2026-05-13');
    // Optimistic update lands locally too.
    expect(useRestDayStore.getState().restDates.has('2026-05-13')).toBe(true);
  });

  it('unmarkToday writes the SoD-aware ISO to Firestore', async () => {
    vi.setSystemTime(new Date(2026, 4, 14, 5, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 4 });
    useRestDayStore.setState({ restDates: new Set(['2026-05-14']) });

    await useRestDayStore.getState().unmarkToday();

    expect(unmarkSpy).toHaveBeenCalledTimes(1);
    expect(unmarkSpy).toHaveBeenCalledWith('test-uid', '2026-05-14');
    expect(useRestDayStore.getState().restDates.has('2026-05-14')).toBe(false);
  });

  it('markToday is idempotent: a second call against the same logical day no-ops the local set', async () => {
    vi.setSystemTime(new Date(2026, 4, 14, 12, 0, 0));
    useSettingsStore.setState({ startOfDayHour: 0 });

    await useRestDayStore.getState().markToday();
    await useRestDayStore.getState().markToday();

    // Firestore-side `setDoc` with merge handles dedupe; the local set
    // is a Set, so re-adding the same key is a no-op. The point of the
    // test is that double-tapping doesn't surface a duplicate / no
    // change in state.
    expect(useRestDayStore.getState().restDates.size).toBe(1);
    expect(useRestDayStore.getState().restDates.has('2026-05-14')).toBe(true);
  });

  it('unmarkToday is idempotent: unmarking a non-rest day is a no-op locally', async () => {
    vi.setSystemTime(new Date(2026, 4, 14, 12, 0, 0));
    useSettingsStore.setState({ startOfDayHour: 0 });

    // Pre-condition: today is NOT a rest day.
    expect(useRestDayStore.getState().isRestDayToday()).toBe(false);
    await useRestDayStore.getState().unmarkToday();

    // The Firestore write still fires (deleteDoc on a missing row is
    // itself a no-op Firestore-side, so we don't gate it client-side),
    // but the local set stays empty.
    expect(useRestDayStore.getState().restDates.size).toBe(0);
  });

  it('reset clears the rest-day set (sign-out path)', () => {
    useRestDayStore.setState({
      restDates: new Set(['2026-05-13', '2026-05-14']),
    });
    useRestDayStore.getState().reset();
    expect(useRestDayStore.getState().restDates.size).toBe(0);
  });

  it('isRestDay does a direct lookup against the cached set', () => {
    useRestDayStore.setState({
      restDates: new Set(['2026-05-13', '2026-05-14']),
    });
    expect(useRestDayStore.getState().isRestDay('2026-05-13')).toBe(true);
    expect(useRestDayStore.getState().isRestDay('2026-05-14')).toBe(true);
    expect(useRestDayStore.getState().isRestDay('2026-05-15')).toBe(false);
  });
});
