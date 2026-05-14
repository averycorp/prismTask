import { useEffect } from 'react';
import type { Unsubscribe } from 'firebase/firestore';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import { useTagStore } from '@/stores/tagStore';
import { useHabitStore } from '@/stores/habitStore';
import { useMedicationSlotsStore } from '@/stores/medicationSlotsStore';
import { useMedicationPreferencesStore } from '@/stores/medicationPreferencesStore';
import { useSettingsStore } from '@/stores/settingsStore';

/**
 * Wires all defined-but-previously-unused `subscribeTo*` Firestore
 * real-time listeners while a user is signed in. On sign-out (uid →
 * null) every listener is cleanly unsubscribed and the medication
 * caches reset to defaults so the next user sees a clean slate.
 *
 * Audit context: prior to this hook web's `App.tsx` only ran
 * `initFirebaseAuthListener`. The seven `subscribeTo*` functions in
 * `web/src/api/firestore/*.ts` were defined and exposed via store
 * methods (e.g. `taskStore.subscribeToTasks`) but were never invoked
 * from any component, so cross-device changes only landed after a
 * manual page refresh.
 *
 * Conflict resolution at apply time is intentionally last-write-wins:
 * Firestore is the source of truth on web, optimistic local state is
 * overwritten by the remote snapshot. LWW timestamp guards and
 * cloud_id dedup are tracked separately as G.0 follow-ups.
 */
export function useFirestoreSync(uid: string | null | undefined): void {
  const subscribeToTasks = useTaskStore((s) => s.subscribeToTasks);
  const subscribeToProjects = useProjectStore((s) => s.subscribeToProjects);
  const subscribeToTags = useTagStore((s) => s.subscribeToTags);
  const subscribeToHabits = useHabitStore((s) => s.subscribeToHabits);
  const subscribeToCompletions = useHabitStore((s) => s.subscribeToCompletions);
  const subscribeToSlotDefs = useMedicationSlotsStore(
    (s) => s.subscribeToSlotDefs,
  );
  const subscribeToPreferences = useMedicationPreferencesStore(
    (s) => s.subscribeToPreferences,
  );
  const subscribeToStartOfDayHour = useSettingsStore(
    (s) => s.subscribeToStartOfDayHour,
  );
  const resetSlots = useMedicationSlotsStore((s) => s.reset);
  const resetPrefs = useMedicationPreferencesStore((s) => s.reset);

  useEffect(() => {
    if (!uid) {
      // Signed out — make sure local caches reset so the next user
      // doesn't see stale data from the previous session before their
      // first snapshot lands.
      resetSlots();
      resetPrefs();
      return;
    }

    const unsubscribers: Unsubscribe[] = [];
    const safeSubscribe = (fn: (uid: string) => Unsubscribe, label: string) => {
      try {
        unsubscribers.push(fn(uid));
      } catch (err) {
        // A failed subscription must not take the rest down — Firestore
        // permission errors on one collection (e.g. medication_slot_defs
        // before the user has opened the medication screen) shouldn't
        // block tasks/habits/projects from going live.
        console.warn(`[useFirestoreSync] Failed to subscribe to ${label}`, err);
      }
    };

    safeSubscribe(subscribeToTasks, 'tasks');
    safeSubscribe(subscribeToProjects, 'projects');
    safeSubscribe(subscribeToTags, 'tags');
    safeSubscribe(subscribeToHabits, 'habits');
    safeSubscribe(subscribeToCompletions, 'habit-completions');
    safeSubscribe(subscribeToSlotDefs, 'medication-slot-defs');
    safeSubscribe(subscribeToPreferences, 'medication-preferences');
    safeSubscribe(subscribeToStartOfDayHour, 'start-of-day-hour');

    return () => {
      for (const unsub of unsubscribers) {
        try {
          unsub();
        } catch {
          // Defensive — onSnapshot unsubscribers are sync and shouldn't
          // throw, but wrap to guarantee we run every cleanup even if
          // one mocked impl misbehaves in tests.
        }
      }
    };
  }, [
    uid,
    subscribeToTasks,
    subscribeToProjects,
    subscribeToTags,
    subscribeToHabits,
    subscribeToCompletions,
    subscribeToSlotDefs,
    subscribeToPreferences,
    subscribeToStartOfDayHour,
    resetSlots,
    resetPrefs,
  ]);
}
