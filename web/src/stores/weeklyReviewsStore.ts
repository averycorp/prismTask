import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToWeeklyReviews,
  type WeeklyReview,
} from '@/api/firestore/weeklyReviews';

/**
 * Live cache of the current user's weekly_reviews collection.
 *
 * Wired by `useFirestoreSync` so cross-device writes (Android push, or
 * the backend `weekly_review_generator` cron — parity audit C.4b) land
 * in the Weekly Review screen without a manual refresh. Closes parity
 * audit § A.1b residual for `weekly_reviews` — `subscribeToWeeklyReviews`
 * already existed in `weeklyReviews.ts`; it just wasn't wired into any
 * consumer.
 *
 * The screen still computes a fresh local aggregate on mount for the
 * unsigned-in / no-cache case, so this cache is additive — it powers
 * the "previous weeks" listing and lets a cron-generated row show up
 * as soon as the listener delivers it.
 */
interface WeeklyReviewsState {
  reviews: WeeklyReview[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToWeeklyReviews: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useWeeklyReviewsStore = create<WeeklyReviewsState>((set) => ({
  reviews: [],

  subscribeToWeeklyReviews: (uid) => {
    return subscribeToWeeklyReviews(uid, (reviews) => {
      set({ reviews });
    });
  },

  reset: () => set({ reviews: [] }),
}));
