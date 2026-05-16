import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToHabitTemplates,
  type HabitTemplate,
} from '@/api/firestore/habitTemplates';

/**
 * Live cache of the current user's habit templates.
 *
 * Read-only on web in this unit (Sync Sweep B, 2 of 23). Android writes
 * via `SyncService.uploadRoomConfigFamily('habit_templates')`. The
 * apply path (spawn a Habit from the blueprint + bump `usage_count`)
 * lands in the habit-templates UI unit.
 *
 * Selector usage note: read individual fields off the store
 * (`s => s.templates`) — fresh-object selectors trigger React #185.
 */
interface HabitTemplatesState {
  templates: HabitTemplate[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToTemplates: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useHabitTemplatesStore = create<HabitTemplatesState>((set) => ({
  templates: [],

  subscribeToTemplates: (uid) => {
    return subscribeToHabitTemplates(uid, (templates) => {
      set({ templates });
    });
  },

  reset: () => set({ templates: [] }),
}));
