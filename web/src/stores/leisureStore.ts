import { create } from 'zustand';
import { leisureApi } from '@/api/leisure';
import { useSettingsStore } from '@/stores/settingsStore';
import { endOfLogicalDayMs, startOfLogicalDayMs } from '@/utils/dayBoundary';
import {
  CUSTOM_CATEGORY_PREFIX,
  LEISURE_CATEGORIES,
  LEISURE_CATEGORY_DISPLAY,
  type LeisureActivity,
  type LeisureCategory,
  type LeisureSession,
  type LeisureSettings,
  isCustomCategoryId,
  parseLeisureCategory,
} from '@/types/leisure';

/**
 * Mirrors `LeisurePoolViewModel.UiState` from
 * `app/.../ui/screens/leisure/LeisurePoolViewModel.kt`. Custom categories +
 * built-in display overrides live client-side only; they map onto Android's
 * `LeisureBudgetPreferences` DataStore semantics.
 */

const CUSTOM_CATEGORIES_KEY = 'prismtask_leisure_custom_categories_v1';
const CATEGORY_DISPLAY_KEY = 'prismtask_leisure_category_display_v1';
const MAX_TARGET = 480;

export interface CustomLeisureCategory {
  id: string; // `custom:<uuid8>`
  label: string;
  emoji: string;
}

export interface LeisureCategoryRef {
  id: string;
  label: string;
  emoji: string;
  kind: 'built-in' | 'custom';
}

function uuid8(): string {
  // Cheap RFC-4122-ish prefix; matches Android `UUID.randomUUID().toString().take(8)`.
  return Math.random().toString(36).slice(2, 10);
}

export function newCustomCategoryId(): string {
  return `${CUSTOM_CATEGORY_PREFIX}${uuid8()}`;
}

function loadCustomCategories(): CustomLeisureCategory[] {
  try {
    const raw = localStorage.getItem(CUSTOM_CATEGORIES_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed)
      ? parsed.filter(
          (c) => c && typeof c.id === 'string' && c.id.startsWith(CUSTOM_CATEGORY_PREFIX),
        )
      : [];
  } catch {
    return [];
  }
}

function persistCustomCategories(list: CustomLeisureCategory[]): void {
  try {
    localStorage.setItem(CUSTOM_CATEGORIES_KEY, JSON.stringify(list));
  } catch {
    /* quota etc — silently drop, in-memory state still works */
  }
}

function loadCategoryDisplay(): Record<LeisureCategory, { emoji: string; label: string }> {
  try {
    const raw = localStorage.getItem(CATEGORY_DISPLAY_KEY);
    if (!raw) return {} as Record<LeisureCategory, { emoji: string; label: string }>;
    return JSON.parse(raw) as Record<LeisureCategory, { emoji: string; label: string }>;
  } catch {
    return {} as Record<LeisureCategory, { emoji: string; label: string }>;
  }
}

function persistCategoryDisplay(
  map: Record<LeisureCategory, { emoji: string; label: string }>,
): void {
  try {
    localStorage.setItem(CATEGORY_DISPLAY_KEY, JSON.stringify(map));
  } catch {
    /* swallow */
  }
}

/**
 * "Today" minutes start-of-day window — honours the user-configurable
 * Start-of-Day hour (cross-device synced via
 * `users/{uid}/prefs/task_behavior_prefs.day_start_hour`). A user with
 * SoD = 6 who logs a leisure session at 5:30 am should see that session
 * count toward *yesterday's* total, and one logged at 6:30 am should
 * count toward today — same shape Android's `DayBoundary` enforces in
 * `LeisureBudgetTracker`.
 *
 * Reads `startOfDayHour` from the settings store at call time so a
 * cross-device update (e.g. user changes SoD on Android) is picked up
 * without re-mounting the leisure screen.
 */
function startOfTodayMillis(): number {
  const hour = useSettingsStore.getState().startOfDayHour;
  return startOfLogicalDayMs(Date.now(), hour);
}

function endOfTodayMillis(): number {
  const hour = useSettingsStore.getState().startOfDayHour;
  return endOfLogicalDayMs(Date.now(), hour);
}

export interface LeisureState {
  activities: LeisureActivity[];
  sessions: LeisureSession[];
  settings: LeisureSettings | null;
  customCategories: CustomLeisureCategory[];
  categoryDisplay: Record<LeisureCategory, { emoji: string; label: string }>;
  isLoading: boolean;
  error: string | null;

  // Fetchers
  fetchAll: () => Promise<void>;
  refreshActivities: () => Promise<void>;
  refreshSessions: () => Promise<void>;
  refreshSettings: () => Promise<void>;

  // Activity mutations
  addActivity: (input: {
    name: string;
    categoryId: string;
    defaultDurationMinutes: number | null;
  }) => Promise<void>;
  updateActivity: (
    activity: LeisureActivity,
    input: { name: string; categoryId: string; defaultDurationMinutes: number | null },
  ) => Promise<void>;
  setActivityEnabled: (activity: LeisureActivity, enabled: boolean) => Promise<void>;
  deleteActivity: (activity: LeisureActivity) => Promise<void>;

  // Session mutations
  checkOffActivity: (
    activity: LeisureActivity,
    durationMinutes?: number,
  ) => Promise<void>;
  logCategorySession: (categoryId: string, durationMinutes: number) => Promise<void>;
  logManualSession: (input: {
    activityId: string | null;
    freeTextName: string | null;
    categoryId: string;
    durationMinutes: number;
    loggedAtIso?: string;
  }) => Promise<void>;

  // Settings mutations
  setDailyTarget: (minutes: number) => Promise<void>;
  setWeekendTarget: (minutes: number | null) => Promise<void>;
  setCategoryEnabled: (category: LeisureCategory, enabled: boolean) => Promise<void>;

  // Custom-category management (local only)
  addCustomCategory: (label: string, emoji: string) => void;
  updateCustomCategory: (id: string, label: string, emoji: string) => void;
  removeCustomCategory: (id: string) => void;

  // Built-in display overrides (local only)
  setCategoryDisplay: (category: LeisureCategory, label: string, emoji: string) => void;
  resetCategoryDisplay: (category: LeisureCategory) => void;

  // Derived getters
  getVisibleCategoryRefs: () => LeisureCategoryRef[];
  refForId: (id: string | null | undefined) => LeisureCategoryRef | null;
  getMinutesLoggedToday: () => number;
  getMinutesByCategoryIdToday: () => Record<string, number>;
  getTargetMinutesToday: () => number;
}

function isWeekend(date = new Date()): boolean {
  const d = date.getDay();
  return d === 0 || d === 6;
}

function targetForToday(settings: LeisureSettings | null): number {
  if (!settings) return 60;
  if (isWeekend() && settings.weekend_target_minutes != null) {
    return settings.weekend_target_minutes;
  }
  return settings.daily_target_minutes;
}

export const useLeisureStore = create<LeisureState>((set, get) => ({
  activities: [],
  sessions: [],
  settings: null,
  customCategories: loadCustomCategories(),
  categoryDisplay: loadCategoryDisplay(),
  isLoading: false,
  error: null,

  fetchAll: async () => {
    set({ isLoading: true, error: null });
    try {
      const [activities, sessions, settings] = await Promise.all([
        leisureApi.listActivities(false),
        leisureApi.listSessions({ limit: 200 }),
        leisureApi.getSettings(),
      ]);
      set({ activities, sessions, settings, isLoading: false });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load leisure data';
      set({ error: message, isLoading: false });
    }
  },

  refreshActivities: async () => {
    try {
      const activities = await leisureApi.listActivities(false);
      set({ activities });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to refresh activities';
      set({ error: message });
    }
  },

  refreshSessions: async () => {
    try {
      const sessions = await leisureApi.listSessions({ limit: 200 });
      set({ sessions });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to refresh sessions';
      set({ error: message });
    }
  },

  refreshSettings: async () => {
    try {
      const settings = await leisureApi.getSettings();
      set({ settings });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to refresh settings';
      set({ error: message });
    }
  },

  addActivity: async ({ name, categoryId, defaultDurationMinutes }) => {
    const trimmed = name.trim();
    if (!trimmed || !categoryId) return;
    // Custom categories don't round-trip; bail out gracefully because the
    // backend's CHECK constraint would 422. Surfacing UI error rather than
    // silently dropping is the right move once we have a toast layer here.
    if (isCustomCategoryId(categoryId)) {
      set({
        error:
          'Custom-category activities stay on this device — open the activity ' +
          'pool on Android (or sync once back to a built-in category) to round-trip.',
      });
      return;
    }
    const category = parseLeisureCategory(categoryId);
    if (!category) return;
    try {
      const created = await leisureApi.createActivity({
        id: crypto.randomUUID(),
        name: trimmed,
        category,
        default_duration_minutes: defaultDurationMinutes ?? undefined,
        enabled: true,
      });
      set((s) => ({ activities: [...s.activities, created], error: null }));
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to add activity';
      set({ error: message });
    }
  },

  updateActivity: async (activity, { name, categoryId, defaultDurationMinutes }) => {
    const trimmed = name.trim();
    if (!trimmed || !categoryId) return;
    if (isCustomCategoryId(categoryId)) {
      set({ error: 'Cannot reassign a synced activity to a custom category.' });
      return;
    }
    const category = parseLeisureCategory(categoryId);
    if (!category) return;
    try {
      const updated = await leisureApi.updateActivity(activity.id, {
        name: trimmed,
        category,
        default_duration_minutes: defaultDurationMinutes,
      });
      set((s) => ({
        activities: s.activities.map((a) => (a.id === updated.id ? updated : a)),
        error: null,
      }));
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to update activity';
      set({ error: message });
    }
  },

  setActivityEnabled: async (activity, enabled) => {
    try {
      const updated = await leisureApi.updateActivity(activity.id, { enabled });
      set((s) => ({
        activities: s.activities.map((a) => (a.id === updated.id ? updated : a)),
      }));
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to toggle activity';
      set({ error: message });
    }
  },

  deleteActivity: async (activity) => {
    try {
      await leisureApi.deleteActivity(activity.id);
      set((s) => ({ activities: s.activities.filter((a) => a.id !== activity.id) }));
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to delete activity';
      set({ error: message });
    }
  },

  checkOffActivity: async (activity, durationMinutes) => {
    const duration = Math.max(
      1,
      durationMinutes ?? activity.default_duration_minutes ?? 30,
    );
    await get().logManualSession({
      activityId: activity.id,
      freeTextName: null,
      categoryId: activity.category,
      durationMinutes: duration,
    });
  },

  logCategorySession: async (categoryId, durationMinutes) => {
    const duration = Math.max(1, durationMinutes);
    await get().logManualSession({
      activityId: null,
      freeTextName: null,
      categoryId,
      durationMinutes: duration,
    });
  },

  logManualSession: async ({
    activityId,
    freeTextName,
    categoryId,
    durationMinutes,
    loggedAtIso,
  }) => {
    if (!categoryId) return;
    if (isCustomCategoryId(categoryId)) {
      set({
        error:
          'Custom-category sessions stay on this device — log a built-in ' +
          'category if you want it to sync across devices.',
      });
      return;
    }
    const category = parseLeisureCategory(categoryId);
    if (!category) return;

    // Auto-add-to-pool for free-text entries (Q2 lock parity with Android).
    let resolvedActivityId: string | null = activityId;
    if (!resolvedActivityId && freeTextName && freeTextName.trim()) {
      try {
        const created = await leisureApi.createActivity({
          id: crypto.randomUUID(),
          name: freeTextName.trim(),
          category,
          default_duration_minutes: durationMinutes,
          enabled: true,
        });
        resolvedActivityId = created.id;
        set((s) => ({ activities: [...s.activities, created] }));
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Failed to add activity';
        set({ error: message });
        return;
      }
    }

    try {
      const session = await leisureApi.createSession({
        id: crypto.randomUUID(),
        activity_id: resolvedActivityId,
        category,
        duration_minutes: durationMinutes,
        logged_at: loggedAtIso ?? new Date().toISOString(),
        source: 'MANUAL',
      });
      set((s) => ({ sessions: [session, ...s.sessions], error: null }));
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to log session';
      set({ error: message });
    }
  },

  setDailyTarget: async (minutes) => {
    const clamped = Math.max(0, Math.min(MAX_TARGET, Math.round(minutes)));
    try {
      const updated = await leisureApi.updateSettings({ daily_target_minutes: clamped });
      set({ settings: updated });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to update target';
      set({ error: message });
    }
  },

  setWeekendTarget: async (minutes) => {
    const clamped =
      minutes == null ? null : Math.max(0, Math.min(MAX_TARGET, Math.round(minutes)));
    try {
      const updated = await leisureApi.updateSettings({
        weekend_target_minutes: clamped,
      });
      set({ settings: updated });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to update weekend target';
      set({ error: message });
    }
  },

  setCategoryEnabled: async (category, enabled) => {
    const current = get().settings?.enabled_categories ?? LEISURE_CATEGORIES.slice();
    const next = enabled
      ? Array.from(new Set([...current, category]))
      : current.filter((c) => c !== category);
    if (next.length === 0) {
      set({ error: 'At least one category must stay enabled.' });
      return;
    }
    try {
      const updated = await leisureApi.updateSettings({
        enabled_categories: next as LeisureCategory[],
      });
      set({ settings: updated });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to update categories';
      set({ error: message });
    }
  },

  addCustomCategory: (label, emoji) => {
    const trimmedLabel = label.trim();
    const trimmedEmoji = emoji.trim();
    if (!trimmedLabel || !trimmedEmoji) return;
    const next = [
      ...get().customCategories,
      { id: newCustomCategoryId(), label: trimmedLabel, emoji: trimmedEmoji },
    ];
    persistCustomCategories(next);
    set({ customCategories: next });
  },

  updateCustomCategory: (id, label, emoji) => {
    if (!isCustomCategoryId(id)) return;
    const trimmedLabel = label.trim();
    const trimmedEmoji = emoji.trim();
    if (!trimmedLabel || !trimmedEmoji) return;
    const next = get().customCategories.map((c) =>
      c.id === id ? { id, label: trimmedLabel, emoji: trimmedEmoji } : c,
    );
    persistCustomCategories(next);
    set({ customCategories: next });
  },

  removeCustomCategory: (id) => {
    if (!isCustomCategoryId(id)) return;
    const next = get().customCategories.filter((c) => c.id !== id);
    persistCustomCategories(next);
    set({ customCategories: next });
  },

  setCategoryDisplay: (category, label, emoji) => {
    const next = {
      ...get().categoryDisplay,
      [category]: { label: label.trim(), emoji: emoji.trim() },
    };
    persistCategoryDisplay(next);
    set({ categoryDisplay: next });
  },

  resetCategoryDisplay: (category) => {
    const next = { ...get().categoryDisplay };
    delete next[category];
    persistCategoryDisplay(next);
    set({ categoryDisplay: next });
  },

  getVisibleCategoryRefs: () => {
    const settings = get().settings;
    const display = get().categoryDisplay;
    const enabled = settings?.enabled_categories ?? LEISURE_CATEGORIES.slice();
    const builtIns: LeisureCategoryRef[] = LEISURE_CATEGORIES.filter((c) =>
      enabled.includes(c),
    ).map((c) => {
      const override = display[c];
      const def = LEISURE_CATEGORY_DISPLAY[c];
      return {
        id: c,
        label: override?.label || def.label,
        emoji: override?.emoji || def.emoji,
        kind: 'built-in',
      };
    });
    const customs: LeisureCategoryRef[] = get().customCategories.map((c) => ({
      id: c.id,
      label: c.label,
      emoji: c.emoji,
      kind: 'custom',
    }));
    return [...builtIns, ...customs];
  },

  refForId: (id) => {
    if (!id) return null;
    const visible = get().getVisibleCategoryRefs();
    const found = visible.find((r) => r.id === id);
    if (found) return found;
    // Built-in not in visible (disabled but still on an activity): synthesize.
    const builtIn = parseLeisureCategory(id);
    if (builtIn) {
      const display = get().categoryDisplay[builtIn];
      const def = LEISURE_CATEGORY_DISPLAY[builtIn];
      return {
        id: builtIn,
        label: display?.label || def.label,
        emoji: display?.emoji || def.emoji,
        kind: 'built-in',
      };
    }
    return null;
  },

  getMinutesLoggedToday: () => {
    const start = startOfTodayMillis();
    const end = endOfTodayMillis();
    return get()
      .sessions.filter((s) => {
        const t = Date.parse(s.logged_at);
        return t >= start && t < end;
      })
      .reduce((sum, s) => sum + s.duration_minutes, 0);
  },

  getMinutesByCategoryIdToday: () => {
    const start = startOfTodayMillis();
    const end = endOfTodayMillis();
    const map: Record<string, number> = {};
    for (const s of get().sessions) {
      const t = Date.parse(s.logged_at);
      if (t < start || t >= end) continue;
      map[s.category] = (map[s.category] ?? 0) + s.duration_minutes;
    }
    return map;
  },

  getTargetMinutesToday: () => targetForToday(get().settings),
}));
