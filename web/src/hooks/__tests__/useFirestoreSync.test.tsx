import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook } from '@testing-library/react';

// ── Hoisted Firestore subscriber mocks ───────────────────────────
//
// Each `subscribeTo*` function returns an unsubscribe spy. We assert
// (a) that App.tsx's hook calls every subscribe with the current uid
// and (b) that flipping uid → null invokes every returned unsub.

const {
  subscribeToTasksMock,
  subscribeToProjectsMock,
  subscribeToTagsMock,
  subscribeToHabitsMock,
  subscribeToCompletionsMock,
  subscribeToSlotDefsMock,
  subscribeToReminderModePreferencesMock,
  subscribeToDayStartHourMock,
  subscribeToDependenciesMock,
  subscribeToAllPhasesMock,
  subscribeToAllRisksMock,
  subscribeToAllAnchorsMock,
  subscribeToRulesMock,
  subscribeToTaskTemplatesMock,
  subscribeToMoodLogsMock,
  subscribeToCheckInsMock,
  subscribeToFocusLogsMock,
  subscribeToMedicationsMock,
  subscribeToWeeklyReviewsMock,
  unsubTasks,
  unsubProjects,
  unsubTags,
  unsubHabits,
  unsubCompletions,
  unsubSlotDefs,
  unsubPrefs,
  unsubStartOfDay,
  unsubDependencies,
  unsubPhases,
  unsubRisks,
  unsubAnchors,
  unsubRules,
  unsubTaskTemplates,
  unsubMoodLogs,
  unsubCheckIns,
  unsubFocusLogs,
  unsubMedications,
  unsubWeeklyReviews,
} = vi.hoisted(() => {
  const unsubTasks = vi.fn();
  const unsubProjects = vi.fn();
  const unsubTags = vi.fn();
  const unsubHabits = vi.fn();
  const unsubCompletions = vi.fn();
  const unsubSlotDefs = vi.fn();
  const unsubPrefs = vi.fn();
  const unsubStartOfDay = vi.fn();
  const unsubDependencies = vi.fn();
  const unsubPhases = vi.fn();
  const unsubRisks = vi.fn();
  const unsubAnchors = vi.fn();
  const unsubRules = vi.fn();
  const unsubTaskTemplates = vi.fn();
  const unsubMoodLogs = vi.fn();
  const unsubCheckIns = vi.fn();
  const unsubFocusLogs = vi.fn();
  const unsubMedications = vi.fn();
  const unsubWeeklyReviews = vi.fn();
  return {
    subscribeToTasksMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubTasks),
    subscribeToProjectsMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubProjects),
    subscribeToTagsMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubTags),
    subscribeToHabitsMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubHabits),
    subscribeToCompletionsMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubCompletions),
    subscribeToSlotDefsMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubSlotDefs),
    subscribeToReminderModePreferencesMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubPrefs),
    subscribeToDayStartHourMock: vi.fn<
      (uid: string, cb: (hour: number) => void) => () => void
    >(() => unsubStartOfDay),
    subscribeToDependenciesMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubDependencies),
    subscribeToAllPhasesMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubPhases),
    subscribeToAllRisksMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubRisks),
    subscribeToAllAnchorsMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubAnchors),
    subscribeToRulesMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubRules),
    subscribeToTaskTemplatesMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubTaskTemplates),
    subscribeToMoodLogsMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubMoodLogs),
    subscribeToCheckInsMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubCheckIns),
    subscribeToFocusLogsMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubFocusLogs),
    subscribeToMedicationsMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubMedications),
    subscribeToWeeklyReviewsMock: vi.fn<
      (uid: string, cb: (data: unknown) => void) => () => void
    >(() => unsubWeeklyReviews),
    unsubTasks,
    unsubProjects,
    unsubTags,
    unsubHabits,
    unsubCompletions,
    unsubSlotDefs,
    unsubPrefs,
    unsubStartOfDay,
    unsubDependencies,
    unsubPhases,
    unsubRisks,
    unsubAnchors,
    unsubRules,
    unsubTaskTemplates,
    unsubMoodLogs,
    unsubCheckIns,
    unsubFocusLogs,
    unsubMedications,
    unsubWeeklyReviews,
  };
});


vi.mock('@/api/firestore/tasks', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/firestore/tasks')>(
      '@/api/firestore/tasks',
    );
  return { ...actual, subscribeToTasks: subscribeToTasksMock };
});
vi.mock('@/api/firestore/projects', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/firestore/projects')>(
      '@/api/firestore/projects',
    );
  return { ...actual, subscribeToProjects: subscribeToProjectsMock };
});
vi.mock('@/api/firestore/tags', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/firestore/tags')>(
      '@/api/firestore/tags',
    );
  return { ...actual, subscribeToTags: subscribeToTagsMock };
});
vi.mock('@/api/firestore/habits', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/firestore/habits')>(
      '@/api/firestore/habits',
    );
  return {
    ...actual,
    subscribeToHabits: subscribeToHabitsMock,
    subscribeToCompletions: subscribeToCompletionsMock,
  };
});
vi.mock('@/api/firestore/medicationSlots', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/medicationSlots')
  >('@/api/firestore/medicationSlots');
  return { ...actual, subscribeToSlotDefs: subscribeToSlotDefsMock };
});
vi.mock('@/api/firestore/medicationPreferences', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/medicationPreferences')
  >('@/api/firestore/medicationPreferences');
  return {
    ...actual,
    subscribeToReminderModePreferences:
      subscribeToReminderModePreferencesMock,
  };
});
vi.mock('@/api/firestore/taskBehaviorPreferences', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/taskBehaviorPreferences')
  >('@/api/firestore/taskBehaviorPreferences');
  return { ...actual, subscribeToDayStartHour: subscribeToDayStartHourMock };
});
vi.mock('@/api/firestore/taskDependencies', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/taskDependencies')
  >('@/api/firestore/taskDependencies');
  return { ...actual, subscribeToDependencies: subscribeToDependenciesMock };
});
vi.mock('@/api/firestore/projectPhases', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/projectPhases')
  >('@/api/firestore/projectPhases');
  return { ...actual, subscribeToAllPhases: subscribeToAllPhasesMock };
});
vi.mock('@/api/firestore/projectRisks', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/projectRisks')
  >('@/api/firestore/projectRisks');
  return { ...actual, subscribeToAllRisks: subscribeToAllRisksMock };
});
vi.mock('@/api/firestore/externalAnchors', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/externalAnchors')
  >('@/api/firestore/externalAnchors');
  return { ...actual, subscribeToAllAnchors: subscribeToAllAnchorsMock };
});
vi.mock('@/api/firestore/boundaryRules', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/boundaryRules')
  >('@/api/firestore/boundaryRules');
  return { ...actual, subscribeToRules: subscribeToRulesMock };
});
vi.mock('@/api/firestore/taskTemplates', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/taskTemplates')
  >('@/api/firestore/taskTemplates');
  return {
    ...actual,
    subscribeToTaskTemplates: subscribeToTaskTemplatesMock,
  };
});
vi.mock('@/api/firestore/moodEnergyLogs', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/moodEnergyLogs')
  >('@/api/firestore/moodEnergyLogs');
  return { ...actual, subscribeToMoodLogs: subscribeToMoodLogsMock };
});
vi.mock('@/api/firestore/checkInLogs', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/checkInLogs')
  >('@/api/firestore/checkInLogs');
  return { ...actual, subscribeToCheckIns: subscribeToCheckInsMock };
});
vi.mock('@/api/firestore/focusReleaseLogs', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/focusReleaseLogs')
  >('@/api/firestore/focusReleaseLogs');
  return { ...actual, subscribeToFocusLogs: subscribeToFocusLogsMock };
});
vi.mock('@/api/firestore/medications', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/medications')
  >('@/api/firestore/medications');
  return { ...actual, subscribeToMedications: subscribeToMedicationsMock };
});
vi.mock('@/api/firestore/weeklyReviews', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/weeklyReviews')
  >('@/api/firestore/weeklyReviews');
  return {
    ...actual,
    subscribeToWeeklyReviews: subscribeToWeeklyReviewsMock,
  };
});
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useFirestoreSync } from '@/hooks/useFirestoreSync';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import { useTagStore } from '@/stores/tagStore';
import { useHabitStore } from '@/stores/habitStore';
import {
  useMedicationSlotsStore,
} from '@/stores/medicationSlotsStore';
import {
  useMedicationPreferencesStore,
} from '@/stores/medicationPreferencesStore';
import { useTaskDependencyStore } from '@/stores/taskDependencyStore';
import { useProjectPhaseStore } from '@/stores/projectPhaseStore';
import { useProjectRiskStore } from '@/stores/projectRiskStore';
import { useExternalAnchorStore } from '@/stores/externalAnchorStore';
import { useBoundaryRulesStore } from '@/stores/boundaryRulesStore';
import { useTemplateStore } from '@/stores/templateStore';
import { useMoodEnergyLogsStore } from '@/stores/moodEnergyLogsStore';
import { useCheckInLogsStore } from '@/stores/checkInLogsStore';
import { useFocusReleaseLogsStore } from '@/stores/focusReleaseLogsStore';
import { useMedicationsStore } from '@/stores/medicationsStore';
import { useWeeklyReviewsStore } from '@/stores/weeklyReviewsStore';
import {
  DEFAULT_REMINDER_MODE_PREFERENCES,
  type MedicationReminderModePreferences,
} from '@/api/firestore/medicationPreferences';
import type { MedicationSlotDef } from '@/api/firestore/medicationSlots';
import type { BoundaryRule } from '@/api/firestore/boundaryRules';
import type { MoodEnergyLog } from '@/api/firestore/moodEnergyLogs';
import type { CheckInLog } from '@/api/firestore/checkInLogs';
import type { FocusReleaseLog } from '@/api/firestore/focusReleaseLogs';
import type { MedicationDoc } from '@/api/firestore/medications';
import type { WeeklyReview } from '@/api/firestore/weeklyReviews';
import type { Task } from '@/types/task';
import type { TaskDependency } from '@/types/taskDependency';
import type { ProjectPhase } from '@/types/projectPhase';
import type { ProjectRisk } from '@/types/projectRisk';
import type { ExternalAnchorRecord } from '@/types/externalAnchor';
import type { TaskTemplate } from '@/types/template';

const ALL_SUBSCRIBES = [
  subscribeToTasksMock,
  subscribeToProjectsMock,
  subscribeToTagsMock,
  subscribeToHabitsMock,
  subscribeToCompletionsMock,
  subscribeToSlotDefsMock,
  subscribeToReminderModePreferencesMock,
  subscribeToDayStartHourMock,
  subscribeToDependenciesMock,
  subscribeToAllPhasesMock,
  subscribeToAllRisksMock,
  subscribeToAllAnchorsMock,
  subscribeToRulesMock,
  subscribeToTaskTemplatesMock,
  subscribeToMoodLogsMock,
  subscribeToCheckInsMock,
  subscribeToFocusLogsMock,
  subscribeToMedicationsMock,
  subscribeToWeeklyReviewsMock,
] as const;

const ALL_UNSUBS = [
  unsubTasks,
  unsubProjects,
  unsubTags,
  unsubHabits,
  unsubCompletions,
  unsubSlotDefs,
  unsubPrefs,
  unsubStartOfDay,
  unsubDependencies,
  unsubPhases,
  unsubRisks,
  unsubAnchors,
  unsubRules,
  unsubTaskTemplates,
  unsubMoodLogs,
  unsubCheckIns,
  unsubFocusLogs,
  unsubMedications,
  unsubWeeklyReviews,
] as const;

function resetAllMocks() {
  for (const m of ALL_SUBSCRIBES) m.mockClear();
  for (const u of ALL_UNSUBS) u.mockClear();
  subscribeToTasksMock.mockReturnValue(unsubTasks);
  subscribeToProjectsMock.mockReturnValue(unsubProjects);
  subscribeToTagsMock.mockReturnValue(unsubTags);
  subscribeToHabitsMock.mockReturnValue(unsubHabits);
  subscribeToCompletionsMock.mockReturnValue(unsubCompletions);
  subscribeToSlotDefsMock.mockReturnValue(unsubSlotDefs);
  subscribeToReminderModePreferencesMock.mockReturnValue(unsubPrefs);
  subscribeToDayStartHourMock.mockReturnValue(unsubStartOfDay);
  subscribeToDependenciesMock.mockReturnValue(unsubDependencies);
  subscribeToAllPhasesMock.mockReturnValue(unsubPhases);
  subscribeToAllRisksMock.mockReturnValue(unsubRisks);
  subscribeToAllAnchorsMock.mockReturnValue(unsubAnchors);
  subscribeToRulesMock.mockReturnValue(unsubRules);
  subscribeToTaskTemplatesMock.mockReturnValue(unsubTaskTemplates);
  subscribeToMoodLogsMock.mockReturnValue(unsubMoodLogs);
  subscribeToCheckInsMock.mockReturnValue(unsubCheckIns);
  subscribeToFocusLogsMock.mockReturnValue(unsubFocusLogs);
  subscribeToMedicationsMock.mockReturnValue(unsubMedications);
  subscribeToWeeklyReviewsMock.mockReturnValue(unsubWeeklyReviews);
}

function resetStores() {
  useTaskStore.setState({
    tasks: [],
    todayTasks: [],
    overdueTasks: [],
    upcomingTasks: [],
  });
  useProjectStore.setState({ projects: [] });
  useTagStore.setState({ tags: [] });
  useHabitStore.setState({ habits: [], completions: {} });
  useMedicationSlotsStore.setState({ slotDefs: [] });
  useMedicationPreferencesStore.setState({
    prefs: DEFAULT_REMINDER_MODE_PREFERENCES,
  });
  useTaskDependencyStore.setState({ dependencies: [] });
  useProjectPhaseStore.setState({ phases: [] });
  useProjectRiskStore.setState({ risks: [] });
  useExternalAnchorStore.setState({ anchors: [] });
  useBoundaryRulesStore.setState({ rules: [] });
  useTemplateStore.setState({
    templates: [],
    isLoading: false,
    error: null,
  });
  useMoodEnergyLogsStore.setState({ logs: [] });
  useCheckInLogsStore.setState({ logs: [] });
  useFocusReleaseLogsStore.setState({ logs: [] });
  useMedicationsStore.setState({ medications: [] });
  useWeeklyReviewsStore.setState({ reviews: [] });
}

describe('useFirestoreSync', () => {
  beforeEach(() => {
    resetAllMocks();
    resetStores();
  });

  it('subscribes to all tracked entity types when uid is set', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    for (const m of ALL_SUBSCRIBES) {
      expect(m).toHaveBeenCalledTimes(1);
      expect(m).toHaveBeenCalledWith('uid-A', expect.any(Function));
    }
  });

  it('does not subscribe when uid is null', () => {
    renderHook(() => useFirestoreSync(null));

    for (const m of ALL_SUBSCRIBES) {
      expect(m).not.toHaveBeenCalled();
    }
  });

  it('does not subscribe when uid is undefined', () => {
    renderHook(() => useFirestoreSync(undefined));

    for (const m of ALL_SUBSCRIBES) {
      expect(m).not.toHaveBeenCalled();
    }
  });

  it('unsubscribes every listener when uid flips to null', () => {
    const { rerender } = renderHook(
      ({ uid }: { uid: string | null }) => useFirestoreSync(uid),
      { initialProps: { uid: 'uid-A' as string | null } },
    );

    for (const u of ALL_UNSUBS) expect(u).not.toHaveBeenCalled();

    rerender({ uid: null });

    for (const u of ALL_UNSUBS) expect(u).toHaveBeenCalledTimes(1);
  });

  it('unsubscribes the previous uid before subscribing to the new uid', () => {
    const { rerender } = renderHook(
      ({ uid }: { uid: string | null }) => useFirestoreSync(uid),
      { initialProps: { uid: 'uid-A' as string | null } },
    );

    rerender({ uid: 'uid-B' });

    // Previous unsubs all fired once during cleanup
    for (const u of ALL_UNSUBS) expect(u).toHaveBeenCalledTimes(1);

    // Each subscriber called twice total — once per uid
    for (const m of ALL_SUBSCRIBES) {
      expect(m).toHaveBeenCalledTimes(2);
      expect(m).toHaveBeenLastCalledWith('uid-B', expect.any(Function));
    }
  });

  it('unsubscribes on unmount', () => {
    const { unmount } = renderHook(() => useFirestoreSync('uid-A'));
    unmount();
    for (const u of ALL_UNSUBS) expect(u).toHaveBeenCalledTimes(1);
  });

  it('resets per-user caches on sign-out so the next user does not see stale data', () => {
    useMedicationSlotsStore.setState({
      slotDefs: [
        {
          id: 'slot-1',
          slot_key: 'morning',
          display_name: 'Morning',
          sort_order: 0,
          reminder_mode: null,
          reminder_interval_minutes: null,
          ideal_time: '09:00',
          drift_minutes: 180,
          is_active: true,
          created_at: 0,
          updated_at: 0,
        },
      ],
    });
    useMedicationPreferencesStore.setState({
      prefs: { mode: 'INTERVAL', interval_default_minutes: 360 },
    });
    useTaskDependencyStore.setState({
      dependencies: [
        {
          id: 'd1',
          blocker_task_id: 'a',
          blocked_task_id: 'b',
          created_at: 0,
        },
      ],
    });
    useProjectPhaseStore.setState({
      phases: [{ id: 'ph1' } as unknown as ProjectPhase],
    });
    useProjectRiskStore.setState({
      risks: [{ id: 'r1' } as unknown as ProjectRisk],
    });
    useExternalAnchorStore.setState({
      anchors: [{ id: 'a1' } as unknown as ExternalAnchorRecord],
    });

    renderHook(() => useFirestoreSync(null));

    expect(useMedicationSlotsStore.getState().slotDefs).toEqual([]);
    expect(useMedicationPreferencesStore.getState().prefs).toEqual(
      DEFAULT_REMINDER_MODE_PREFERENCES,
    );
    expect(useTaskDependencyStore.getState().dependencies).toEqual([]);
    expect(useProjectPhaseStore.getState().phases).toEqual([]);
    expect(useProjectRiskStore.getState().risks).toEqual([]);
    expect(useExternalAnchorStore.getState().anchors).toEqual([]);
  });

  it('a remote tasks snapshot updates the task store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToTasksMock.mock.calls[0]?.[1] as unknown as (
      tasks: Task[],
    ) => void;
    const remoteTask: Task = {
      id: 't1',
      project_id: 'p1',
      title: 'Remote',
      description: null,
      status: 'todo',
      priority: 0,
      due_date: null,
      reminder_offset: null,
      recurrence_rule: null,
      sort_order: 0,
      parent_id: null,
      tags: [],
      subtasks: [],
      life_category: null,
      user_overrode_quadrant: false,
      created_at: '',
      updated_at: '',
      completed_at: null,
    } as unknown as Task;
    callback([remoteTask]);

    expect(useTaskStore.getState().tasks).toEqual([remoteTask]);
  });

  it('a remote slot-defs snapshot updates the medication slots store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToSlotDefsMock.mock.calls[0]?.[1] as unknown as (
      defs: MedicationSlotDef[],
    ) => void;
    const def: MedicationSlotDef = {
      id: 'slot-2',
      slot_key: 'evening',
      display_name: 'Evening',
      sort_order: 1,
      reminder_mode: null,
      reminder_interval_minutes: null,
      ideal_time: '21:00',
      drift_minutes: 180,
      is_active: true,
      created_at: 0,
      updated_at: 0,
    };
    callback([def]);

    expect(useMedicationSlotsStore.getState().slotDefs).toEqual([def]);
  });

  it('a remote preferences snapshot updates the medication preferences store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToReminderModePreferencesMock.mock
      .calls[0]?.[1] as unknown as (
      prefs: MedicationReminderModePreferences,
    ) => void;
    const next: MedicationReminderModePreferences = {
      mode: 'INTERVAL',
      interval_default_minutes: 240,
    };
    callback(next);

    expect(useMedicationPreferencesStore.getState().prefs).toEqual(next);
  });

  it('a remote dependencies snapshot updates the dependency store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToDependenciesMock.mock
      .calls[0]?.[1] as unknown as (deps: TaskDependency[]) => void;
    const dep: TaskDependency = {
      id: 'd9',
      blocker_task_id: 'tA',
      blocked_task_id: 'tB',
      created_at: 0,
    };
    callback([dep]);

    expect(useTaskDependencyStore.getState().dependencies).toEqual([dep]);
  });

  it('a remote phases snapshot updates the phase store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToAllPhasesMock.mock.calls[0]?.[1] as unknown as (
      phases: ProjectPhase[],
    ) => void;
    const phase = { id: 'ph9', project_id: 'p1' } as unknown as ProjectPhase;
    callback([phase]);

    expect(useProjectPhaseStore.getState().phases).toEqual([phase]);
  });

  it('a remote risks snapshot updates the risk store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToAllRisksMock.mock.calls[0]?.[1] as unknown as (
      risks: ProjectRisk[],
    ) => void;
    const risk = { id: 'r9', project_id: 'p1' } as unknown as ProjectRisk;
    callback([risk]);

    expect(useProjectRiskStore.getState().risks).toEqual([risk]);
  });

  it('a remote anchors snapshot updates the anchor store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToAllAnchorsMock.mock
      .calls[0]?.[1] as unknown as (anchors: ExternalAnchorRecord[]) => void;
    const anchor = {
      id: 'a9',
      project_id: 'p1',
    } as unknown as ExternalAnchorRecord;
    callback([anchor]);

    expect(useExternalAnchorStore.getState().anchors).toEqual([anchor]);
  });

  // Parity audit § A.1b — boundary rules now stream live from Firestore.
  it('a remote boundary-rules snapshot updates the boundary-rules store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToRulesMock.mock.calls[0]?.[1] as unknown as (
      rules: BoundaryRule[],
    ) => void;
    const rule: BoundaryRule = {
      id: 'br1',
      type: 'daily_task_cap',
      label: 'Max 12',
      value: 12,
      secondary_value: null,
      enabled: true,
      created_at: 0,
      updated_at: 0,
    };
    callback([rule]);

    expect(useBoundaryRulesStore.getState().rules).toEqual([rule]);
  });

  // Parity audit § B.10 — task templates stream live from Firestore.
  it('a remote task-templates snapshot updates the template store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToTaskTemplatesMock.mock
      .calls[0]?.[1] as unknown as (templates: TaskTemplate[]) => void;
    const template: TaskTemplate = {
      id: 'tt1',
      user_id: 'uid-A',
      name: 'Morning Routine',
      description: null,
      icon: '🌅',
      category: 'Productivity',
      template_title: 'Morning Routine',
      template_description: null,
      template_priority: null,
      template_project_id: null,
      template_tags_json: null,
      template_recurrence_json: null,
      template_duration: null,
      template_subtasks_json: null,
      is_built_in: false,
      usage_count: 0,
      last_used_at: null,
      created_at: '2026-05-15T00:00:00.000Z',
      updated_at: '2026-05-15T00:00:00.000Z',
    };
    callback([template]);

    expect(useTemplateStore.getState().templates).toEqual([template]);
  });

  it('resets the task-templates cache on sign-out', () => {
    useTemplateStore.setState({
      templates: [
        {
          id: 'tt-prev',
          user_id: 'uid-prev',
          name: 'Stale',
          description: null,
          icon: null,
          category: null,
          template_title: null,
          template_description: null,
          template_priority: null,
          template_project_id: null,
          template_tags_json: null,
          template_recurrence_json: null,
          template_duration: null,
          template_subtasks_json: null,
          is_built_in: false,
          usage_count: 3,
          last_used_at: null,
          created_at: '2026-05-14T00:00:00.000Z',
          updated_at: '2026-05-14T00:00:00.000Z',
        },
      ],
    });

    renderHook(() => useFirestoreSync(null));

    expect(useTemplateStore.getState().templates).toEqual([]);
  });

  it('resets the boundary-rules cache on sign-out', () => {
    useBoundaryRulesStore.setState({
      rules: [
        {
          id: 'br-prev',
          type: 'daily_task_cap',
          label: 'Stale',
          value: 5,
          secondary_value: null,
          enabled: true,
          created_at: 0,
          updated_at: 0,
        },
      ],
    });

    renderHook(() => useFirestoreSync(null));

    expect(useBoundaryRulesStore.getState().rules).toEqual([]);
  });

  // Parity audit § A.1b residual (Unit 16) — mood / check-in / focus /
  // medications / weekly-reviews now stream live from Firestore.
  it('a remote mood-energy-logs snapshot updates the mood store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToMoodLogsMock.mock.calls[0]?.[1] as unknown as (
      logs: MoodEnergyLog[],
    ) => void;
    const log: MoodEnergyLog = {
      id: '2026-05-15__morning',
      date_iso: '2026-05-15',
      mood: 4,
      energy: 3,
      notes: '',
      time_of_day: 'morning',
      created_at: 0,
      updated_at: 0,
    };
    callback([log]);

    expect(useMoodEnergyLogsStore.getState().logs).toEqual([log]);
  });

  it('a remote check-in-logs snapshot updates the check-in store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToCheckInsMock.mock.calls[0]?.[1] as unknown as (
      logs: CheckInLog[],
    ) => void;
    const log: CheckInLog = {
      id: '2026-05-15',
      date_iso: '2026-05-15',
      steps_completed_csv: 'hydrated,medicated',
      medications_confirmed: true,
      tasks_reviewed: false,
      habits_completed: false,
      created_at: 0,
      updated_at: 0,
    };
    callback([log]);

    expect(useCheckInLogsStore.getState().logs).toEqual([log]);
  });

  it('a remote focus-release-logs snapshot updates the focus-release store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToFocusLogsMock.mock.calls[0]?.[1] as unknown as (
      logs: FocusReleaseLog[],
    ) => void;
    const log: FocusReleaseLog = {
      id: 'fr1',
      task_id: null,
      task_title_snapshot: 'Refactor sync',
      planned_minutes: 25,
      actual_minutes: 22,
      release_state: 'shipped',
      note: '',
      started_at: 0,
      ended_at: 0,
    };
    callback([log]);

    expect(useFocusReleaseLogsStore.getState().logs).toEqual([log]);
  });

  it('a remote medications snapshot updates the medications store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToMedicationsMock.mock
      .calls[0]?.[1] as unknown as (meds: MedicationDoc[]) => void;
    const med = {
      id: 'm1',
      name: 'Vitamin D',
      sortOrder: 0,
    } as unknown as MedicationDoc;
    callback([med]);

    expect(useMedicationsStore.getState().medications).toEqual([med]);
  });

  it('a remote weekly-reviews snapshot updates the weekly-reviews store', () => {
    renderHook(() => useFirestoreSync('uid-A'));

    const callback = subscribeToWeeklyReviewsMock.mock
      .calls[0]?.[1] as unknown as (reviews: WeeklyReview[]) => void;
    const review = {
      id: '2026-05-11',
      weekStartDate: '2026-05-11',
    } as unknown as WeeklyReview;
    callback([review]);

    expect(useWeeklyReviewsStore.getState().reviews).toEqual([review]);
  });

  it('resets mood/check-in/focus/medications/weekly-reviews caches on sign-out', () => {
    useMoodEnergyLogsStore.setState({
      logs: [{ id: 'stale' } as unknown as MoodEnergyLog],
    });
    useCheckInLogsStore.setState({
      logs: [{ id: 'stale' } as unknown as CheckInLog],
    });
    useFocusReleaseLogsStore.setState({
      logs: [{ id: 'stale' } as unknown as FocusReleaseLog],
    });
    useMedicationsStore.setState({
      medications: [{ id: 'stale' } as unknown as MedicationDoc],
    });
    useWeeklyReviewsStore.setState({
      reviews: [{ id: 'stale' } as unknown as WeeklyReview],
    });

    renderHook(() => useFirestoreSync(null));

    expect(useMoodEnergyLogsStore.getState().logs).toEqual([]);
    expect(useCheckInLogsStore.getState().logs).toEqual([]);
    expect(useFocusReleaseLogsStore.getState().logs).toEqual([]);
    expect(useMedicationsStore.getState().medications).toEqual([]);
    expect(useWeeklyReviewsStore.getState().reviews).toEqual([]);
  });

  it('a failed subscription does not block the remaining listeners', () => {
    subscribeToTasksMock.mockImplementationOnce(() => {
      throw new Error('permission-denied');
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    renderHook(() => useFirestoreSync('uid-A'));

    // Tasks throw; every other tracked subscribe (see ALL_SUBSCRIBES)
    // still fires. Update the list below when adding a new listener
    // to `useFirestoreSync`.
    expect(subscribeToProjectsMock).toHaveBeenCalledTimes(1);
    expect(subscribeToTagsMock).toHaveBeenCalledTimes(1);
    expect(subscribeToHabitsMock).toHaveBeenCalledTimes(1);
    expect(subscribeToCompletionsMock).toHaveBeenCalledTimes(1);
    expect(subscribeToSlotDefsMock).toHaveBeenCalledTimes(1);
    expect(subscribeToReminderModePreferencesMock).toHaveBeenCalledTimes(1);
    expect(subscribeToDayStartHourMock).toHaveBeenCalledTimes(1);
    expect(subscribeToDependenciesMock).toHaveBeenCalledTimes(1);
    expect(subscribeToAllPhasesMock).toHaveBeenCalledTimes(1);
    expect(subscribeToAllRisksMock).toHaveBeenCalledTimes(1);
    expect(subscribeToAllAnchorsMock).toHaveBeenCalledTimes(1);
    expect(subscribeToRulesMock).toHaveBeenCalledTimes(1);
    expect(subscribeToTaskTemplatesMock).toHaveBeenCalledTimes(1);
    // Tasks throw; every other tracked subscribe in ALL_SUBSCRIBES still
    // fires. Iterating keeps this assertion in lockstep with the array
    // — no manual count to update when a new listener is added.
    for (const m of ALL_SUBSCRIBES) {
      if (m === subscribeToTasksMock) continue;
      expect(m).toHaveBeenCalledTimes(1);
    }
    expect(warnSpy).toHaveBeenCalled();

    warnSpy.mockRestore();
  });
});
