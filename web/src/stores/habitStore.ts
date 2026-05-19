import { create } from 'zustand';
import { format } from 'date-fns';
import type {
  Habit,
  HabitCreate,
  HabitUpdate,
  HabitCompletion,
  HabitStats,
} from '@/types/habit';
import * as firestoreHabits from '@/api/firestore/habits';
import { calculateStreaks, type StreakData } from '@/utils/streaks';
import { isMedicationBuiltInHabit } from '@/utils/medicationBuiltInHabit';
import { logicalToday } from '@/utils/dayBoundary';
import { getPeriodBounds } from '@/utils/habitPeriod';
import { useSettingsStore } from '@/stores/settingsStore';
import {
  selectForgivenessConfig,
  useAdvancedTuningStore,
} from '@/stores/advancedTuningStore';
import { useRestDayStore } from '@/stores/restDayStore';
import type { Unsubscribe } from 'firebase/firestore';

interface HabitState {
  habits: Habit[];
  /** habitId → completions list */
  completions: Record<string, HabitCompletion[]>;
  /** habitId → stats from backend */
  stats: Record<string, HabitStats>;
  selectedHabit: Habit | null;
  isLoading: boolean;
  error: string | null;

  fetchHabits: () => Promise<void>;
  fetchCompletionsForHabit: (habitId: string) => Promise<void>;
  fetchAllCompletions: () => Promise<void>;
  fetchHabitStats: (habitId: string) => Promise<HabitStats>;
  createHabit: (data: HabitCreate) => Promise<Habit>;
  updateHabit: (habitId: string, data: HabitUpdate) => Promise<Habit>;
  deleteHabit: (habitId: string) => Promise<void>;
  toggleCompletion: (habitId: string, date: string) => Promise<void>;
  setSelectedHabit: (habit: Habit | null) => void;
  clearError: () => void;

  // Real-time
  subscribeToHabits: (uid: string) => Unsubscribe;
  subscribeToCompletions: (uid: string) => Unsubscribe;

  // Computed helpers
  getStreakData: (habitId: string) => StreakData | null;
  isTodayCompleted: (habitId: string) => boolean;
  getTodayCount: (habitId: string) => number;
  /**
   * Completion count inside the habit's current period. For `daily`
   * habits this is today's count (period = day); for non-daily habits
   * it is the sum of completions inside the matching weekly /
   * fortnightly / monthly / bimonthly / quarterly window — the same
   * `periodCompletions` Android surfaces on the habit card.
   */
  getPeriodCompletions: (habitId: string) => number;
  getTodayProgress: () => { completed: number; total: number };
  getWeekCompletions: (habitId: string) => boolean[];
}

import { getFirebaseUid } from '@/stores/firebaseUid';

function getUid(): string {
  return getFirebaseUid();
}

function parseActiveDays(json: string | null): number[] | null {
  if (!json) return null;
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

/**
 * Sum a non-daily habit's completion counts inside the period containing
 * `todayIso`. Caller is responsible for branching on `habit.frequency` —
 * passing a daily habit throws (see `getPeriodBounds`).
 */
function countPeriodCompletions(
  habit: Habit,
  completions: HabitCompletion[],
  todayIso: string,
): number {
  const { startIso, endIso } = getPeriodBounds(habit.frequency, todayIso);
  let total = 0;
  for (const c of completions) {
    if (c.date >= startIso && c.date <= endIso) total += c.count;
  }
  return total;
}

/**
 * Logical "today" ISO (`YYYY-MM-DD`) honouring the user's Start-of-Day
 * hour. Reads `startOfDayHour` from the settings store at call time so
 * the value stays in sync with cross-device updates.
 *
 * The bug this fixes: previously this returned the calendar-midnight
 * date via `format(new Date(), 'yyyy-MM-dd')`. Android writes
 * `completedDateLocal` via `DayBoundary.normalizeToDayStart` (SoD-aware,
 * see `HabitRepository.completeHabit` → `epochToLocalDateString`). For a
 * user with SoD = 4 completing a habit at 02:00 local, Android stamps
 * yesterday's date (logical day started at 04:00 yesterday) but the web
 * `isTodayCompleted` / `getTodayCount` getters were looking up *today's*
 * calendar date, so the completion appeared to vanish on web.
 */
function todayStr(): string {
  const hour = useSettingsStore.getState().startOfDayHour;
  return logicalToday(Date.now(), hour);
}

export const useHabitStore = create<HabitState>((set, get) => ({
  habits: [],
  completions: {},
  stats: {},
  selectedHabit: null,
  isLoading: false,
  error: null,

  fetchHabits: async () => {
    set({ isLoading: true, error: null });
    try {
      const uid = getUid();
      const habits = await firestoreHabits.getHabits(uid);
      set({ habits, isLoading: false });

      // Fetch all completions
      await get().fetchAllCompletions();
    } catch (e) {
      set({ error: (e as Error).message, isLoading: false });
    }
  },

  fetchCompletionsForHabit: async (habitId) => {
    try {
      const uid = getUid();
      const completionsList = await firestoreHabits.getCompletions(uid, habitId);
      set((state) => ({
        completions: {
          ...state.completions,
          [habitId]: completionsList,
        },
      }));
    } catch {
      // Silently fail for individual habit fetches
    }
  },

  fetchAllCompletions: async () => {
    try {
      const uid = getUid();
      const allCompletions = await firestoreHabits.getAllCompletions(uid);
      // Group completions by habit_id
      const grouped: Record<string, HabitCompletion[]> = {};
      for (const c of allCompletions) {
        if (!grouped[c.habit_id]) grouped[c.habit_id] = [];
        grouped[c.habit_id].push(c);
      }
      set({ completions: grouped });
    } catch {
      // Silently fail
    }
  },

  fetchHabitStats: async (habitId) => {
    // Compute stats client-side from completions
    const completions = get().completions[habitId] || [];
    const streakData = get().getStreakData(habitId);
    const stats: HabitStats = {
      habit_id: habitId,
      current_streak: streakData?.currentStreak ?? 0,
      longest_streak: streakData?.longestStreak ?? 0,
      total_completions: completions.reduce((sum, c) => sum + c.count, 0),
      completion_rate: 0,
      completions_this_week: 0,
    };
    set((state) => ({
      stats: { ...state.stats, [habitId]: stats },
    }));
    return stats;
  },

  createHabit: async (data) => {
    const uid = getUid();
    const habit = await firestoreHabits.createHabit(uid, data);
    set((state) => ({
      habits: [...state.habits, habit],
      completions: { ...state.completions, [habit.id]: [] },
    }));
    return habit;
  },

  updateHabit: async (habitId, data) => {
    const uid = getUid();
    const updated = await firestoreHabits.updateHabit(uid, habitId, data as Record<string, unknown>);
    set((state) => ({
      habits: state.habits.map((h) => (h.id === habitId ? updated : h)),
    }));
    return updated;
  },

  deleteHabit: async (habitId) => {
    const uid = getUid();
    await firestoreHabits.deleteHabit(uid, habitId);
    set((state) => {
      const newCompletions = { ...state.completions };
      delete newCompletions[habitId];
      const newStats = { ...state.stats };
      delete newStats[habitId];
      return {
        habits: state.habits.filter((h) => h.id !== habitId),
        completions: newCompletions,
        stats: newStats,
      };
    });
  },

  toggleCompletion: async (habitId, date) => {
    const state = get();
    const existing = (state.completions[habitId] || []).find(
      (c) => c.date === date,
    );

    // Optimistic update
    if (existing && existing.count > 0) {
      set((s) => ({
        completions: {
          ...s.completions,
          [habitId]: (s.completions[habitId] || []).filter(
            (c) => c.date !== date,
          ),
        },
      }));
    } else {
      const optimistic: HabitCompletion = {
        id: `temp_${Date.now()}`,
        habit_id: habitId,
        date,
        count: 1,
        created_at: new Date().toISOString(),
      };
      set((s) => ({
        completions: {
          ...s.completions,
          [habitId]: [...(s.completions[habitId] || []), optimistic],
        },
      }));
    }

    try {
      const uid = getUid();
      const result = await firestoreHabits.toggleCompletion(uid, habitId, date);

      if (result.action === 'removed') {
        set((s) => ({
          completions: {
            ...s.completions,
            [habitId]: (s.completions[habitId] || []).filter(
              (c) => c.date !== date,
            ),
          },
        }));
      } else if (result.completion) {
        set((s) => ({
          completions: {
            ...s.completions,
            [habitId]: [
              ...(s.completions[habitId] || []).filter(
                (c) => c.date !== date,
              ),
              result.completion!,
            ],
          },
        }));
      }
    } catch {
      // Revert on error by re-fetching
      await get().fetchCompletionsForHabit(habitId);
    }
  },

  setSelectedHabit: (habit) => set({ selectedHabit: habit }),
  clearError: () => set({ error: null }),

  subscribeToHabits: (uid: string) => {
    return firestoreHabits.subscribeToHabits(uid, (habits) => {
      set({ habits });
    });
  },

  subscribeToCompletions: (uid: string) => {
    return firestoreHabits.subscribeToCompletions(uid, (allCompletions) => {
      const grouped: Record<string, HabitCompletion[]> = {};
      for (const c of allCompletions) {
        if (!grouped[c.habit_id]) grouped[c.habit_id] = [];
        grouped[c.habit_id].push(c);
      }
      set({ completions: grouped });
    });
  },

  getStreakData: (habitId) => {
    const state = get();
    const habit = state.habits.find((h) => h.id === habitId);
    const completions = state.completions[habitId];
    if (!habit || !completions) return null;

    // Per-user forgiveness knobs from Settings → Advanced Tuning. Reads
    // the store imperatively so the streak picks up cross-device
    // tweaks immediately without re-subscribing this component graph.
    // When Advanced Tuning hasn't loaded yet, the store's default state
    // already mirrors `DEFAULT_FORGIVENESS`, so the streak math degrades
    // gracefully to the same numbers callers used to see pre-PR.
    const forgiveness = selectForgivenessConfig(
      useAdvancedTuningStore.getState().prefs,
    );

    // Rest days fold into the daily forgiveness walk as
    // kept-by-definition (`docs/REST_DAY.md` § *The core rule*). Pulled
    // at read time from the restDay store so a fresh rest-day mark
    // surfaces in the streak immediately — the snapshot listener wired
    // in `useFirestoreSync` keeps the underlying set in sync.
    const restDays = useRestDayStore.getState().restDates;

    // Per-habit streak-forgiveness overrides (parity audit § B.6).
    // Mirrors Android's `HabitForgivenessResolver`: any of the four
    // fields set on the habit takes precedence over the global config
    // for this habit only. `undefined` fields inherit. The resolver
    // inside `calculateStreaks` does the field-by-field substitution.
    const habitOverrides = {
      streakMaxMissedDays: habit.streak_max_missed_days,
      forgivenessEnabled: habit.forgiveness_enabled,
      forgivenessAllowedMisses: habit.forgiveness_allowed_misses,
      forgivenessGracePeriodDays: habit.forgiveness_grace_period_days,
    };

    return calculateStreaks(
      completions.map((c) => ({ date: c.date, count: c.count })),
      habit.frequency,
      parseActiveDays(habit.active_days_json),
      habit.target_count,
      forgiveness,
      restDays,
      undefined, // taskMode — habits don't carry one
      habitOverrides,
    );
  },

  isTodayCompleted: (habitId) => {
    const state = get();
    const habit = state.habits.find((h) => h.id === habitId);
    if (!habit) return false;
    const completions = state.completions[habitId] || [];
    const today = todayStr();
    if (habit.frequency === 'daily') {
      const todayCompletion = completions.find((c) => c.date === today);
      const count = todayCompletion?.count || 0;
      return count >= (habit.target_count || 1);
    }
    // Non-daily: "completed today" means the period's target is met —
    // mirrors Android's `HabitRepository.getHabitsWithFullStatus` branch
    // (`periodCompletions >= habit.targetFrequency`). target_count on a
    // non-daily habit is the per-period total, not a per-day target.
    const periodCount = countPeriodCompletions(habit, completions, today);
    return periodCount >= (habit.target_count || 1);
  },

  getTodayCount: (habitId) => {
    const completions = get().completions[habitId] || [];
    const today = todayStr();
    const todayCompletion = completions.find((c) => c.date === today);
    return todayCompletion?.count || 0;
  },

  getPeriodCompletions: (habitId) => {
    const state = get();
    const habit = state.habits.find((h) => h.id === habitId);
    if (!habit) return 0;
    const completions = state.completions[habitId] || [];
    const today = todayStr();
    if (habit.frequency === 'daily') {
      const todayCompletion = completions.find((c) => c.date === today);
      return todayCompletion?.count || 0;
    }
    return countPeriodCompletions(habit, completions, today);
  },

  getTodayProgress: () => {
    const state = get();
    // Medication is its own top-level destination (parity with Android's
    // `HabitListViewModel`); exclude it so the Today progress count
    // matches the Today habit section + done-counter sheet.
    const activeHabits = state.habits.filter(
      (h) => h.is_active && !isMedicationBuiltInHabit(h),
    );
    const today = todayStr();
    // Use the logical-day Date (parsed from `today`) so the active-day
    // weekday filter aligns with Android's `DayBoundary`-driven
    // semantics. A user with SoD = 4 doing their habit at 02:00 should
    // still see Monday-only habits as "today" if the logical day is
    // Monday.
    const todayDate = new Date(today + 'T12:00:00');
    let total = 0;
    let completed = 0;

    for (const habit of activeHabits) {
      const activeDays = parseActiveDays(habit.active_days_json);
      if (activeDays && activeDays.length > 0) {
        const jsDay = todayDate.getDay();
        const isoDay = jsDay === 0 ? 7 : jsDay;
        if (!activeDays.includes(isoDay)) continue;
      }
      total++;
      if (habit.frequency !== 'daily') {
        // Period-aware (week / fortnight / month / 2 months / quarter)
        // — mirrors Android `HabitRepository.getHabitsWithFullStatus`.
        const periodCount = countPeriodCompletions(
          habit,
          state.completions[habit.id] || [],
          today,
        );
        if (periodCount >= habit.target_count) completed++;
      } else {
        const todayCompletion = (state.completions[habit.id] || []).find(
          (c) => c.date === today,
        );
        if ((todayCompletion?.count || 0) >= habit.target_count) completed++;
      }
    }

    return { completed, total };
  },

  getWeekCompletions: (habitId) => {
    const completions = get().completions[habitId] || [];
    const today = new Date();
    const dayOffset = (today.getDay() + 6) % 7;
    const result: boolean[] = [];
    for (let i = 0; i < 7; i++) {
      const d = new Date(today);
      d.setDate(today.getDate() - dayOffset + i);
      const dateStr = format(d, 'yyyy-MM-dd');
      result.push(completions.some((c) => c.date === dateStr && c.count > 0));
    }
    return result;
  },
}));
