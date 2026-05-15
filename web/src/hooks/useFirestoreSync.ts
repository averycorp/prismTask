import { useEffect } from 'react';
import type { Unsubscribe } from 'firebase/firestore';
import { backfillLegacySlotDefs } from '@/api/firestore/medicationSlots';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import { useTagStore } from '@/stores/tagStore';
import { useHabitStore } from '@/stores/habitStore';
import { useHabitLogStore } from '@/stores/habitLogStore';
import { useMedicationSlotsStore } from '@/stores/medicationSlotsStore';
import { useMedicationPreferencesStore } from '@/stores/medicationPreferencesStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { useTaskDependencyStore } from '@/stores/taskDependencyStore';
import { useProjectPhaseStore } from '@/stores/projectPhaseStore';
import { useProjectRiskStore } from '@/stores/projectRiskStore';
import { useExternalAnchorStore } from '@/stores/externalAnchorStore';
import { useCourseStore } from '@/stores/courseStore';
import { useThemeStore } from '@/stores/themeStore';
import { useNdPreferencesStore } from '@/stores/ndPreferencesStore';
import { useA11yStore } from '@/stores/a11yStore';
import { useSelfCareStore } from '@/stores/selfCareStore';
import { useBoundaryRulesStore } from '@/stores/boundaryRulesStore';
import { useTemplateStore } from '@/stores/templateStore';
import { useDashboardStore } from '@/stores/dashboardStore';
import { useMoodEnergyLogsStore } from '@/stores/moodEnergyLogsStore';
import { useCheckInLogsStore } from '@/stores/checkInLogsStore';
import { useFocusReleaseLogsStore } from '@/stores/focusReleaseLogsStore';
import { useMedicationsStore } from '@/stores/medicationsStore';
import { useWeeklyReviewsStore } from '@/stores/weeklyReviewsStore';
import { useAdvancedTuningStore } from '@/stores/advancedTuningStore';

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
 * Parity audit A.1a (2026-05-13) extends this to 11 listeners — adds
 * task_dependencies, project_phases, project_risks, external_anchors.
 * Parity audit A.1b residual (Unit 16) adds the remaining cross-device
 * collections — `mood_energy_logs`, `check_in_logs`,
 * `focus_release_logs`, `medications`, `weekly_reviews` — so Android
 * writes (and the backend `weekly_review_generator` cron for the last)
 * surface on web without a manual refresh on the mood / check-in /
 * focus-release / medication / weekly-review screens.
 * Pillars audit Phase 2 #4 adds `advanced_tuning_prefs` so a slider
 * tweak on phone (forgiveness grace window, allowed misses, classifier
 * custom keywords) propagates live to web and vice-versa.
 * `subscribeToAiFeaturesEnabled` is intentionally NOT wired here: the
 * AI-features flag is already pulled imperatively via
 * `settingsStore.loadAiFeaturesFromFirestore` on auth bootstrap, and
 * adding an `onSnapshot` here would double-read the same doc plus race
 * with the localStorage-backed `setSetting` write-back inside the
 * store. Real-time AI-prefs sync is tracked separately so it can be
 * addressed alongside the broader settings persistence work (A.5b).
 *
 * Conflict resolution at apply time is intentionally last-write-wins:
 * Firestore is the source of truth on web, optimistic local state is
 * overwritten by the remote snapshot. LWW timestamp guards and
 * cloud_id dedup are tracked separately as G.0 follow-ups.
 */
export function useFirestoreSync(uid: string | null | undefined): void {
  const subscribeToTasks = useTaskStore((s) => s.subscribeToTasks);
  const subscribeToTaskCompletions = useTaskStore(
    (s) => s.subscribeToTaskCompletions,
  );
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
  const subscribeToDependencies = useTaskDependencyStore(
    (s) => s.subscribeToDependencies,
  );
  const subscribeToPhases = useProjectPhaseStore((s) => s.subscribeToPhases);
  const subscribeToRisks = useProjectRiskStore((s) => s.subscribeToRisks);
  const subscribeToAnchors = useExternalAnchorStore(
    (s) => s.subscribeToAnchors,
  );
  const subscribeToCourses = useCourseStore((s) => s.subscribe);
  const subscribeToTheme = useThemeStore((s) => s.subscribeToFirestore);
  const subscribeToNdPrefs = useNdPreferencesStore((s) => s.subscribeToPrefs);
  const subscribeToA11y = useA11yStore((s) => s.subscribeToFirestore);
  const subscribeToSelfCareLogs = useSelfCareStore((s) => s.subscribeToLogs);
  const subscribeToSelfCareSteps = useSelfCareStore((s) => s.subscribeToSteps);
  const subscribeToBoundaryRules = useBoundaryRulesStore(
    (s) => s.subscribeToRules,
  );
  const subscribeToTaskTemplates = useTemplateStore(
    (s) => s.subscribeToTaskTemplates,
  );
  const subscribeToDashboardPrefs = useDashboardStore(
    (s) => s.subscribeToPrefs,
  );
  const subscribeToMoodLogs = useMoodEnergyLogsStore(
    (s) => s.subscribeToLogs,
  );
  const subscribeToCheckIns = useCheckInLogsStore(
    (s) => s.subscribeToCheckIns,
  );
  const subscribeToFocusLogs = useFocusReleaseLogsStore(
    (s) => s.subscribeToFocusLogs,
  );
  const subscribeToMedications = useMedicationsStore(
    (s) => s.subscribeToMedications,
  );
  const subscribeToWeeklyReviews = useWeeklyReviewsStore(
    (s) => s.subscribeToWeeklyReviews,
  );
  const subscribeToHabitLogs = useHabitLogStore((s) => s.subscribeToLogs);
  const subscribeToAdvancedTuning = useAdvancedTuningStore(
    (s) => s.subscribeToPrefs,
  );
  const resetHabitLogs = useHabitLogStore((s) => s.reset);
  const resetSelfCare = useSelfCareStore((s) => s.reset);
  const resetNdPrefs = useNdPreferencesStore((s) => s.reset);
  const resetBoundaryRules = useBoundaryRulesStore((s) => s.reset);
  const resetTaskTemplates = useTemplateStore((s) => s.reset);
  const resetDashboard = useDashboardStore((s) => s.reset);
  const resetSlots = useMedicationSlotsStore((s) => s.reset);
  const resetPrefs = useMedicationPreferencesStore((s) => s.reset);
  const resetDependencies = useTaskDependencyStore((s) => s.reset);
  const resetPhases = useProjectPhaseStore((s) => s.reset);
  const resetRisks = useProjectRiskStore((s) => s.reset);
  const resetAnchors = useExternalAnchorStore((s) => s.reset);
  const resetMoodLogs = useMoodEnergyLogsStore((s) => s.reset);
  const resetCheckIns = useCheckInLogsStore((s) => s.reset);
  const resetFocusLogs = useFocusReleaseLogsStore((s) => s.reset);
  const resetMedications = useMedicationsStore((s) => s.reset);
  const resetWeeklyReviews = useWeeklyReviewsStore((s) => s.reset);
  const resetAdvancedTuning = useAdvancedTuningStore((s) => s.reset);

  useEffect(() => {
    if (!uid) {
      // Signed out — make sure local caches reset so the next user
      // doesn't see stale data from the previous session before their
      // first snapshot lands.
      resetSlots();
      resetPrefs();
      resetDependencies();
      resetPhases();
      resetRisks();
      resetAnchors();
      resetNdPrefs();
      resetSelfCare();
      resetBoundaryRules();
      resetTaskTemplates();
      resetDashboard();
      resetMoodLogs();
      resetCheckIns();
      resetFocusLogs();
      resetMedications();
      resetWeeklyReviews();
      resetHabitLogs();
      resetAdvancedTuning();
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

    // One-time legacy → canonical slot-defs backfill (parity Batch 5 PR-7,
    // decision D-E2). Guarded by a localStorage flag inside the helper
    // so re-mounts and re-signs no-op after the first success. Fires
    // in the background — failure doesn't gate subscriptions.
    void backfillLegacySlotDefs(uid).catch(() => undefined);

    safeSubscribe(subscribeToTasks, 'tasks');
    safeSubscribe(subscribeToTaskCompletions, 'task-completions');
    safeSubscribe(subscribeToProjects, 'projects');
    safeSubscribe(subscribeToTags, 'tags');
    safeSubscribe(subscribeToHabits, 'habits');
    safeSubscribe(subscribeToCompletions, 'habit-completions');
    safeSubscribe(subscribeToSlotDefs, 'medication-slot-defs');
    safeSubscribe(subscribeToPreferences, 'medication-preferences');
    safeSubscribe(subscribeToStartOfDayHour, 'start-of-day-hour');
    safeSubscribe(subscribeToDependencies, 'task-dependencies');
    safeSubscribe(subscribeToPhases, 'project-phases');
    safeSubscribe(subscribeToRisks, 'project-risks');
    safeSubscribe(subscribeToAnchors, 'external-anchors');
    safeSubscribe(subscribeToCourses, 'courses');
    safeSubscribe(subscribeToTheme, 'theme-preferences');
    safeSubscribe(subscribeToNdPrefs, 'nd-preferences');
    safeSubscribe(subscribeToA11y, 'a11y-preferences');
    safeSubscribe(subscribeToSelfCareLogs, 'self-care-logs');
    safeSubscribe(subscribeToSelfCareSteps, 'self-care-steps');
    safeSubscribe(subscribeToBoundaryRules, 'boundary-rules');
    safeSubscribe(subscribeToTaskTemplates, 'task-templates');
    safeSubscribe(subscribeToDashboardPrefs, 'dashboard-preferences');
    safeSubscribe(subscribeToMoodLogs, 'mood-energy-logs');
    safeSubscribe(subscribeToCheckIns, 'check-in-logs');
    safeSubscribe(subscribeToFocusLogs, 'focus-release-logs');
    safeSubscribe(subscribeToMedications, 'medications');
    safeSubscribe(subscribeToWeeklyReviews, 'weekly-reviews');
    safeSubscribe(subscribeToHabitLogs, 'habit-logs');
    safeSubscribe(subscribeToAdvancedTuning, 'advanced-tuning');

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
    subscribeToTaskCompletions,
    subscribeToProjects,
    subscribeToTags,
    subscribeToHabits,
    subscribeToCompletions,
    subscribeToSlotDefs,
    subscribeToPreferences,
    subscribeToStartOfDayHour,
    subscribeToDependencies,
    subscribeToPhases,
    subscribeToRisks,
    subscribeToAnchors,
    subscribeToCourses,
    subscribeToTheme,
    subscribeToNdPrefs,
    subscribeToA11y,
    subscribeToSelfCareLogs,
    subscribeToSelfCareSteps,
    subscribeToBoundaryRules,
    subscribeToTaskTemplates,
    subscribeToDashboardPrefs,
    subscribeToMoodLogs,
    subscribeToCheckIns,
    subscribeToFocusLogs,
    subscribeToMedications,
    subscribeToWeeklyReviews,
    subscribeToHabitLogs,
    subscribeToAdvancedTuning,
    resetHabitLogs,
    resetSelfCare,
    resetSlots,
    resetPrefs,
    resetDependencies,
    resetPhases,
    resetRisks,
    resetAnchors,
    resetNdPrefs,
    resetBoundaryRules,
    resetTaskTemplates,
    resetDashboard,
    resetMoodLogs,
    resetCheckIns,
    resetFocusLogs,
    resetMedications,
    resetWeeklyReviews,
    resetAdvancedTuning,
  ]);
}
