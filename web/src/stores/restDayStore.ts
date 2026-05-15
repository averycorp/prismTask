import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  markRestDay,
  subscribeToRestDays,
  unmarkRestDay,
} from '@/api/firestore/restDays';
import { useSettingsStore } from '@/stores/settingsStore';
import { logicalToday } from '@/utils/dayBoundary';
import { getFirebaseUid } from '@/stores/firebaseUid';

/**
 * Rest-Day primitive store. Mirrors Android's `RestDayRepository`:
 *
 *  - Holds the live set of ISO date strings the user has marked as
 *    rest days, hydrated by the Firestore real-time listener wired
 *    from `useFirestoreSync`.
 *  - Resolves "today" via the user's Start-of-Day hour (from
 *    `settingsStore.startOfDayHour`) so a user with SoD = 4 tapping
 *    the toggle at 02:30 marks *yesterday's* calendar date — which is
 *    correct, because that's the user's logical day. Mirrors
 *    `DayBoundary.currentLocalDateString` semantics line-for-line via
 *    `logicalToday(now, sodHour)`.
 *  - Mark/unmark are idempotent (Firestore-side `setDoc` merge /
 *    `deleteDoc` makes them no-ops on already-set / already-clear
 *    rows). UI can safely re-tap without guarding.
 *
 * The streak walk (`web/src/utils/streaks.ts`) consumes
 * `restDates` directly — it's a `Set<string>` of ISO dates so
 * `forgivenessDailyWalk` can treat each date as kept-by-definition
 * (does NOT consume the grace window, does NOT count as a miss).
 *
 * Web doesn't fire the non-medication notifications Android suppresses
 * via `RestDayGate` (habit reminders, daily briefing, etc. are all
 * Android-side), so the gate seam is N/A here. The streak fold + soft
 * Today-screen banner are the load-bearing surfaces.
 *
 * See `docs/REST_DAY.md` for the philosophy.
 */
interface RestDayState {
  /** ISO `yyyy-MM-dd` dates the user has marked as rest days. */
  restDates: Set<string>;

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToRestDays: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;

  /** ISO date of "today" (logical, SoD-aware). */
  todayIso: () => string;

  /** Is today (logical, SoD-aware) marked as a rest day? */
  isRestDayToday: () => boolean;

  /** Look up an arbitrary ISO date. */
  isRestDay: (isoDate: string) => boolean;

  /**
   * Mark today (logical, SoD-aware) as a rest day. Optimistically
   * updates local state, then writes to Firestore. The snapshot
   * listener reconciles back; on Firestore failure the optimistic
   * update is left in place because the listener will overwrite it
   * with the authoritative set momentarily.
   */
  markToday: () => Promise<void>;

  /**
   * Unmark today (logical, SoD-aware). Same optimistic shape as
   * `markToday`.
   */
  unmarkToday: () => Promise<void>;
}

export const useRestDayStore = create<RestDayState>((set, get) => ({
  restDates: new Set<string>(),

  subscribeToRestDays: (uid) =>
    subscribeToRestDays(uid, (dates) => {
      set({ restDates: dates });
    }),

  reset: () => set({ restDates: new Set<string>() }),

  todayIso: () => {
    const hour = useSettingsStore.getState().startOfDayHour;
    return logicalToday(Date.now(), hour);
  },

  isRestDayToday: () => get().restDates.has(get().todayIso()),

  isRestDay: (isoDate) => get().restDates.has(isoDate),

  markToday: async () => {
    const iso = get().todayIso();
    // Optimistic update — the snapshot listener will reconcile.
    if (!get().restDates.has(iso)) {
      const next = new Set(get().restDates);
      next.add(iso);
      set({ restDates: next });
    }
    let uid: string;
    try {
      uid = getFirebaseUid();
    } catch {
      // Not signed in — local-only mode. The optimistic update stays.
      return;
    }
    try {
      await markRestDay(uid, iso);
    } catch (err) {
      console.warn('[restDayStore] markRestDay failed', err);
      // Leave optimistic update in place; the listener (or a retry on
      // next sign-in) is the canonical source of truth. The Android
      // path uses the same shape — local change first, sync after.
    }
  },

  unmarkToday: async () => {
    const iso = get().todayIso();
    if (get().restDates.has(iso)) {
      const next = new Set(get().restDates);
      next.delete(iso);
      set({ restDates: next });
    }
    let uid: string;
    try {
      uid = getFirebaseUid();
    } catch {
      return;
    }
    try {
      await unmarkRestDay(uid, iso);
    } catch (err) {
      console.warn('[restDayStore] unmarkRestDay failed', err);
    }
  },
}));
