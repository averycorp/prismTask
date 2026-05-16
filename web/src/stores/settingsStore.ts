import type { Unsubscribe } from 'firebase/firestore';
import { create } from 'zustand';
import { setAiFeaturesEnabledProvider } from '@/api/client';
import {
  DEFAULT_AI_FEATURES_ENABLED,
  getAiFeaturesEnabled,
  setAiFeaturesEnabled,
} from '@/api/firestore/aiPreferences';
import {
  DEFAULT_DAY_START_HOUR,
  getDayStartHour,
  setDayStartHour,
  subscribeToDayStartHour,
} from '@/api/firestore/taskBehaviorPreferences';

interface SettingsState {
  // Task Defaults
  defaultPriority: number;
  showCompletedTasks: boolean;
  confirmBeforeDelete: boolean;

  // Today View
  showOverdueSection: boolean;
  showUpcomingSection: boolean;
  showHabitChips: boolean;
  showBriefingCard: boolean;
  showMorningCheckIn: boolean;
  upcomingDays: number;
  /** Hour (0–23) at which the "logical day" rolls over. Matches
   *  Android's `DayBoundary` / `startOfDay` preference. 0 = midnight.
   *  Synced cross-device via Firestore at
   *  `users/{uid}/prefs/task_behavior_prefs.day_start_hour`. */
  startOfDayHour: number;

  // Calendar
  weekStartsOn: 'sunday' | 'monday';
  timeFormat: '12h' | '24h';
  showWeekends: boolean;

  // Compact
  compactMode: boolean;

  /**
   * When true (default), Quick Add surfaces a TaskConfirmModal pre-filled
   * from the NLP parse result so the user can review/edit before the task
   * is saved. Mirrors Android's `KEY_QUICK_ADD_CONFIRM`. Stored locally —
   * per-device pref, not synced cross-platform yet.
   */
  confirmTaskBeforeSave: boolean;

  /**
   * Master AI-features opt-out. Default `true` (opt-out, not opt-in) to match
   * Android's `KEY_AI_FEATURES_ENABLED`. When false, the request-side gate in
   * `api/client.ts` short-circuits all Anthropic-touching paths with a
   * synthetic 451. Synced cross-device via Firestore at
   * `users/{uid}/prefs/user_prefs.ai_features_enabled` so toggling this on
   * Android propagates to web (and vice versa).
   */
  aiFeaturesEnabled: boolean;

  // Actions
  setSetting: <K extends keyof SettingsState>(key: K, value: SettingsState[K]) => void;
  loadFromStorage: () => void;
  /**
   * Pull the AI-features flag from Firestore on auth-load. Called by the
   * auth bootstrap once `firebaseUid` is known. Idempotent. Falls back to
   * the local (localStorage) value on read failure so offline users don't
   * lose the flag.
   */
  loadAiFeaturesFromFirestore: (uid: string) => Promise<void>;
  /**
   * Pull the Start-of-Day hour from Firestore on auth-load. Called by the
   * auth bootstrap once `firebaseUid` is known. Idempotent. Falls back to
   * the local (localStorage) value on read failure so offline users don't
   * lose the value.
   */
  loadStartOfDayHourFromFirestore: (uid: string) => Promise<void>;
  /**
   * Subscribe to remote Firestore updates to the Start-of-Day hour so a
   * change made on another device (e.g. Android) propagates here without
   * a page refresh. Returns the unsubscribe function. Wired by
   * `useFirestoreSync`.
   */
  subscribeToStartOfDayHour: (uid: string) => Unsubscribe;
}

const STORAGE_KEY = 'prismtask_settings';

function loadSettings(): Partial<SettingsState> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

function saveSettings(state: Partial<SettingsState>) {
  const {
    defaultPriority,
    showCompletedTasks,
    confirmBeforeDelete,
    showOverdueSection,
    showUpcomingSection,
    showHabitChips,
    showBriefingCard,
    showMorningCheckIn,
    upcomingDays,
    startOfDayHour,
    weekStartsOn,
    timeFormat,
    showWeekends,
    compactMode,
    aiFeaturesEnabled,
    confirmTaskBeforeSave,
  } = state as SettingsState;

  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      defaultPriority,
      showCompletedTasks,
      confirmBeforeDelete,
      showOverdueSection,
      showUpcomingSection,
      showHabitChips,
      showBriefingCard,
      showMorningCheckIn,
      upcomingDays,
      startOfDayHour,
      weekStartsOn,
      timeFormat,
      showWeekends,
      compactMode,
      aiFeaturesEnabled,
      confirmTaskBeforeSave,
    }),
  );
}

const defaults = {
  defaultPriority: 3,
  showCompletedTasks: true,
  confirmBeforeDelete: true,
  showOverdueSection: true,
  showUpcomingSection: true,
  showHabitChips: true,
  showBriefingCard: true,
  showMorningCheckIn: true,
  upcomingDays: 7,
  startOfDayHour: DEFAULT_DAY_START_HOUR,
  weekStartsOn: 'sunday' as const,
  timeFormat: '12h' as const,
  showWeekends: true,
  compactMode: false,
  aiFeaturesEnabled: DEFAULT_AI_FEATURES_ENABLED,
  confirmTaskBeforeSave: true,
};

const stored = loadSettings();

/**
 * Push the AI-features flag to Firestore so Android picks it up on its
 * next pull. Best-effort: a transient Firestore failure should never block
 * the local UI toggle. We surface the error to the console rather than
 * unwinding the optimistic local update because the Firestore pull side
 * is last-write-wins and the user can re-toggle.
 */
async function pushAiFeaturesEnabledToFirestore(enabled: boolean): Promise<void> {
  // Lazy-import to avoid initializing Firebase modules on test paths that
  // don't need them.
  const [{ getFirebaseUid }] = await Promise.all([
    import('@/stores/firebaseUid'),
  ]);
  let uid: string;
  try {
    uid = getFirebaseUid();
  } catch {
    // Not signed in — local-only toggle is fine.
    return;
  }
  try {
    await setAiFeaturesEnabled(uid, enabled);
  } catch (e) {
    console.warn('Failed to sync AI features flag to Firestore', e);
  }
}

/**
 * Push the Start-of-Day hour to Firestore so Android picks it up on its
 * next pull. Best-effort: a transient Firestore failure should never
 * block the local UI update. We surface the error to the console rather
 * than unwinding the optimistic local update because the Firestore pull
 * side is last-write-wins and the user can re-pick.
 */
async function pushStartOfDayHourToFirestore(hour: number): Promise<void> {
  const [{ getFirebaseUid }] = await Promise.all([
    import('@/stores/firebaseUid'),
  ]);
  let uid: string;
  try {
    uid = getFirebaseUid();
  } catch {
    // Not signed in — local-only update is fine.
    return;
  }
  try {
    await setDayStartHour(uid, hour);
  } catch (e) {
    console.warn('Failed to sync Start-of-Day hour to Firestore', e);
  }
}

export const useSettingsStore = create<SettingsState>((set, get) => ({
  ...defaults,
  ...stored,

  setSetting: (key, value) => {
    set({ [key]: value } as Partial<SettingsState>);
    saveSettings({ ...get(), [key]: value });

    // Apply compact mode immediately
    if (key === 'compactMode') {
      document.documentElement.classList.toggle('compact', value as boolean);
    }

    // Sync the AI-features flag to Firestore so Android receives the
    // toggle on its next pull. Fire-and-forget — local UI is optimistic.
    if (key === 'aiFeaturesEnabled') {
      void pushAiFeaturesEnabledToFirestore(value as boolean);
    }

    // Sync the Start-of-Day hour to Firestore so Android receives the
    // new value on its next pull. Fire-and-forget — local UI is
    // optimistic. Parity audit item A.5a.
    if (key === 'startOfDayHour') {
      void pushStartOfDayHourToFirestore(value as number);
    }
  },

  loadFromStorage: () => {
    const stored = loadSettings();
    set(stored);
    if (stored.compactMode) {
      document.documentElement.classList.add('compact');
    }
  },

  loadAiFeaturesFromFirestore: async (uid) => {
    try {
      const remote = await getAiFeaturesEnabled(uid);
      set({ aiFeaturesEnabled: remote });
      saveSettings({ ...get(), aiFeaturesEnabled: remote });
    } catch (e) {
      console.warn('Failed to load AI features flag from Firestore', e);
    }
  },

  loadStartOfDayHourFromFirestore: async (uid) => {
    try {
      const remote = await getDayStartHour(uid);
      set({ startOfDayHour: remote });
      saveSettings({ ...get(), startOfDayHour: remote });
    } catch (e) {
      console.warn('Failed to load Start-of-Day hour from Firestore', e);
    }
  },

  subscribeToStartOfDayHour: (uid) =>
    subscribeToDayStartHour(uid, (remote) => {
      // Apply remote update verbatim. We intentionally don't re-push
      // the same value to Firestore — `subscribeToDayStartHour` fires
      // on local writes too (Firestore echoes our own writes), and
      // pushing again would create an infinite loop.
      set({ startOfDayHour: remote });
      saveSettings({ ...get(), startOfDayHour: remote });
    }),
}));

// Register the gate provider so the axios request interceptor can read the
// current AI-features flag without importing the store directly (which
// would create a cycle: client → store → client).
setAiFeaturesEnabledProvider(() => useSettingsStore.getState().aiFeaturesEnabled);
