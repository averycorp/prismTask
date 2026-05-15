import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Vitest 4 + jsdom 29 ship a non-functional `localStorage` stub. The
// settingsStore writes to localStorage on every `setSetting` call, so we
// install a minimal in-memory shim before any store imports run.
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

// Stub the leisure API + ancillary Firestore modules pulled in
// transitively by the leisure store. The store never calls these in the
// tests below — the assertions all read computed getters from in-memory
// state — but the imports are evaluated when the module loads.
vi.mock('@/api/leisure', () => ({
  leisureApi: {
    listActivities: vi.fn(),
    listSessions: vi.fn(),
    getSettings: vi.fn(),
    createActivity: vi.fn(),
    updateActivity: vi.fn(),
    deleteActivity: vi.fn(),
    createSession: vi.fn(),
    updateSettings: vi.fn(),
  },
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

import { useLeisureStore } from '@/stores/leisureStore';
import { useSettingsStore } from '@/stores/settingsStore';
import type { LeisureSession } from '@/types/leisure';

function session(overrides: Partial<LeisureSession> = {}): LeisureSession {
  return {
    id: 'session-1',
    activity_id: null,
    category: 'PHYSICAL',
    duration_minutes: 30,
    logged_at: '2026-05-14T05:30:00.000Z',
    source: 'MANUAL',
    created_at: '2026-05-14T05:30:00.000Z',
    ...overrides,
  };
}

/**
 * Parity tests for the leisure-store Today window. Mirrors Android's
 * `DayBoundary`-driven `LeisureBudgetTracker.getMinutesLoggedToday`. A
 * user with SoD = 6 logging a leisure session at 05:30 should see that
 * session count toward *yesterday* (logical day still belongs to
 * yesterday because 5 < 6), while a session at 06:30 should count
 * toward today.
 */
describe('leisureStore — Start-of-Day awareness', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    useSettingsStore.setState({ startOfDayHour: 0 });
    useLeisureStore.setState({
      activities: [],
      sessions: [],
      settings: null,
      isLoading: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('SoD=6 at 06:30 local: 05:30 session is yesterday, not counted', () => {
    // Now: 2026-05-14 06:30 local. Logical today started at 06:00 today.
    vi.setSystemTime(new Date(2026, 4, 14, 6, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 6 });
    // Session timestamp = 2026-05-14 05:30 local (logical day = yesterday).
    const loggedAt = new Date(2026, 4, 14, 5, 30, 0).toISOString();
    useLeisureStore.setState({
      sessions: [session({ logged_at: loggedAt, duration_minutes: 30 })],
    });

    expect(useLeisureStore.getState().getMinutesLoggedToday()).toBe(0);
    expect(useLeisureStore.getState().getMinutesByCategoryIdToday()).toEqual({});
  });

  it('SoD=6 at 06:30 local: 06:30 session counts toward today', () => {
    vi.setSystemTime(new Date(2026, 4, 14, 6, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 6 });
    const loggedAt = new Date(2026, 4, 14, 6, 30, 0).toISOString();
    useLeisureStore.setState({
      sessions: [
        session({ logged_at: loggedAt, duration_minutes: 45, category: 'SOCIAL' }),
      ],
    });

    expect(useLeisureStore.getState().getMinutesLoggedToday()).toBe(45);
    expect(useLeisureStore.getState().getMinutesByCategoryIdToday()).toEqual({
      SOCIAL: 45,
    });
  });

  it('SoD=6 just before boundary (05:30): pre-boundary session within the same logical day is counted', () => {
    // Now: 2026-05-14 05:30 local. SoD = 6 → logical day started
    // 2026-05-13 06:00 and ends 2026-05-14 06:00. A session at
    // 2026-05-13 18:00 falls inside this window.
    vi.setSystemTime(new Date(2026, 4, 14, 5, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 6 });
    const loggedAt = new Date(2026, 4, 13, 18, 0, 0).toISOString();
    useLeisureStore.setState({
      sessions: [session({ logged_at: loggedAt, duration_minutes: 60 })],
    });

    expect(useLeisureStore.getState().getMinutesLoggedToday()).toBe(60);
  });

  it('SoD=0 (default) keeps calendar-midnight semantics', () => {
    // Now: 2026-05-14 06:30 local. SoD = 0 → logical day = calendar day.
    // A session at 2026-05-14 05:30 is the same calendar day → counted.
    vi.setSystemTime(new Date(2026, 4, 14, 6, 30, 0));
    useSettingsStore.setState({ startOfDayHour: 0 });
    const loggedAt = new Date(2026, 4, 14, 5, 30, 0).toISOString();
    useLeisureStore.setState({
      sessions: [session({ logged_at: loggedAt, duration_minutes: 30 })],
    });

    expect(useLeisureStore.getState().getMinutesLoggedToday()).toBe(30);
  });

  it('aggregates per-category totals only within the logical day window', () => {
    vi.setSystemTime(new Date(2026, 4, 14, 10, 0, 0));
    useSettingsStore.setState({ startOfDayHour: 4 });
    // Inside window (today after 04:00):
    const todayMorning = new Date(2026, 4, 14, 8, 0, 0).toISOString();
    const todayLate = new Date(2026, 4, 14, 9, 30, 0).toISOString();
    // Outside window (yesterday before 04:00):
    const yesterdayEarly = new Date(2026, 4, 14, 2, 0, 0).toISOString();
    useLeisureStore.setState({
      sessions: [
        session({
          id: 's1',
          logged_at: todayMorning,
          category: 'PHYSICAL',
          duration_minutes: 20,
        }),
        session({
          id: 's2',
          logged_at: todayLate,
          category: 'PHYSICAL',
          duration_minutes: 15,
        }),
        session({
          id: 's3',
          logged_at: yesterdayEarly,
          category: 'SOCIAL',
          duration_minutes: 90,
        }),
      ],
    });

    expect(useLeisureStore.getState().getMinutesLoggedToday()).toBe(35);
    expect(useLeisureStore.getState().getMinutesByCategoryIdToday()).toEqual({
      PHYSICAL: 35,
    });
  });
});
