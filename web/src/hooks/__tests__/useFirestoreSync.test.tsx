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
import {
  DEFAULT_REMINDER_MODE_PREFERENCES,
  type MedicationReminderModePreferences,
} from '@/api/firestore/medicationPreferences';
import type { MedicationSlotDef } from '@/api/firestore/medicationSlots';
import type { Task } from '@/types/task';
import type { TaskDependency } from '@/types/taskDependency';
import type { ProjectPhase } from '@/types/projectPhase';
import type { ProjectRisk } from '@/types/projectRisk';
import type { ExternalAnchorRecord } from '@/types/externalAnchor';

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
}

describe('useFirestoreSync', () => {
  beforeEach(() => {
    resetAllMocks();
    resetStores();
  });

  it('subscribes to all 11 entity types when uid is set', () => {
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

  it('a failed subscription does not block the remaining listeners', () => {
    subscribeToTasksMock.mockImplementationOnce(() => {
      throw new Error('permission-denied');
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    renderHook(() => useFirestoreSync('uid-A'));

    // Tasks throw; the other seven still subscribe
    // Tasks throw; the other ten still subscribe
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
    expect(warnSpy).toHaveBeenCalled();

    warnSpy.mockRestore();
  });
});
