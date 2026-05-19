import { describe, it, expect } from 'vitest';
import {
  buildRoutineCard,
  schoolworkHasContent,
} from '@/features/today/DailyEssentialsCards';
import type { SelfCareLog, SelfCareStep } from '@/api/firestore/selfCare';
import type { SelfCareTierDefaults } from '@/api/firestore/advancedTuningPreferences';
import type { Assignment, Course } from '@/types/schoolwork';

/**
 * Pure-function tests for the Daily Essentials section card builders.
 * Mirrors Android's `DailyEssentialsUseCase.observeRoutineCard` shape
 * (parity unit 6 of 23). No DOM, no Firestore.
 */

const TIER_DEFAULTS: SelfCareTierDefaults = {
  morning: 'solid',
  bedtime: 'solid',
  medication: 'prescription',
  housework: 'regular',
};

function step(over: Partial<SelfCareStep> & { step_id: string; routine_type: string }): SelfCareStep {
  return {
    id: over.step_id,
    step_id: over.step_id,
    routine_type: over.routine_type,
    label: over.label ?? over.step_id,
    duration: over.duration ?? '',
    tier: over.tier ?? 'solid',
    note: over.note ?? '',
    phase: over.phase ?? '',
    sort_order: over.sort_order ?? 0,
    reminder_delay_millis: over.reminder_delay_millis ?? null,
    time_of_day: over.time_of_day ?? 'morning',
    medication_name: over.medication_name ?? null,
    source_version: over.source_version ?? 0,
    updated_at: over.updated_at ?? 0,
  };
}

function log(over: Partial<SelfCareLog> & { routine_type: string; date: number }): SelfCareLog {
  return {
    id: `${over.routine_type}__${over.date}`,
    routine_type: over.routine_type,
    date: over.date,
    selected_tier: over.selected_tier ?? 'solid',
    completed_steps: over.completed_steps ?? '[]',
    tiers_by_time: over.tiers_by_time ?? '{}',
    is_complete: over.is_complete ?? false,
    started_at: over.started_at ?? null,
    created_at: over.created_at ?? 0,
    updated_at: over.updated_at ?? 0,
  };
}

const TODAY_MS = 1_700_000_000_000;

describe('buildRoutineCard (parity unit 6)', () => {
  it('returns null when no steps exist for the routine', () => {
    const card = buildRoutineCard('morning', [], [], TODAY_MS, TIER_DEFAULTS);
    expect(card).toBeNull();
  });

  it('lists steps sorted by sort_order with completion flags', () => {
    const steps = [
      step({ step_id: 's2', routine_type: 'morning', sort_order: 2, label: 'Brush' }),
      step({ step_id: 's1', routine_type: 'morning', sort_order: 1, label: 'Wake' }),
      step({ step_id: 'sBed', routine_type: 'bedtime', sort_order: 1, label: 'Read' }),
    ];
    const todayLog = log({
      routine_type: 'morning',
      date: TODAY_MS,
      completed_steps: '["s1"]',
    });
    const card = buildRoutineCard(
      'morning',
      steps,
      [todayLog],
      TODAY_MS,
      TIER_DEFAULTS,
    );
    expect(card).not.toBeNull();
    expect(card!.routineType).toBe('morning');
    expect(card!.displayName).toBe('Morning Routine');
    expect(card!.steps.map((s) => s.stepId)).toEqual(['s1', 's2']);
    expect(card!.steps[0].completed).toBe(true);
    expect(card!.steps[1].completed).toBe(false);
  });

  it('ignores logs for other dates', () => {
    const steps = [step({ step_id: 's1', routine_type: 'morning' })];
    const yesterdayLog = log({
      routine_type: 'morning',
      date: TODAY_MS - 24 * 60 * 60 * 1000,
      completed_steps: '["s1"]',
    });
    const card = buildRoutineCard(
      'morning',
      steps,
      [yesterdayLog],
      TODAY_MS,
      TIER_DEFAULTS,
    );
    expect(card!.steps[0].completed).toBe(false);
  });

  it('maps the bedtime + housework display names', () => {
    const morningSteps = [step({ step_id: 's1', routine_type: 'morning' })];
    const bedtimeSteps = [step({ step_id: 's1', routine_type: 'bedtime' })];
    const houseworkSteps = [
      step({ step_id: 's1', routine_type: 'housework', tier: 'quick' }),
    ];
    expect(
      buildRoutineCard('bedtime', bedtimeSteps, [], TODAY_MS, TIER_DEFAULTS)!
        .displayName,
    ).toBe('Bedtime Routine');
    expect(
      buildRoutineCard('housework', houseworkSteps, [], TODAY_MS, TIER_DEFAULTS)!
        .displayName,
    ).toBe('Housework');
    expect(
      buildRoutineCard('morning', morningSteps, [], TODAY_MS, TIER_DEFAULTS)!
        .displayName,
    ).toBe('Morning Routine');
  });

  it('hides steps above the configured tier default (regression for #1659)', () => {
    // Housework tier order: quick < regular < deep. A "regular" default
    // must show quick + regular steps but hide the deep one.
    const steps = [
      step({ step_id: 'hQuick', routine_type: 'housework', tier: 'quick', sort_order: 1 }),
      step({ step_id: 'hReg', routine_type: 'housework', tier: 'regular', sort_order: 2 }),
      step({ step_id: 'hDeep', routine_type: 'housework', tier: 'deep', sort_order: 3 }),
    ];
    const card = buildRoutineCard(
      'housework',
      steps,
      [],
      TODAY_MS,
      { ...TIER_DEFAULTS, housework: 'regular' },
    );
    expect(card!.steps.map((s) => s.stepId)).toEqual(['hQuick', 'hReg']);
  });

  it("today's log selectedTier overrides the prefs default", () => {
    const steps = [
      step({ step_id: 'hQuick', routine_type: 'housework', tier: 'quick', sort_order: 1 }),
      step({ step_id: 'hReg', routine_type: 'housework', tier: 'regular', sort_order: 2 }),
      step({ step_id: 'hDeep', routine_type: 'housework', tier: 'deep', sort_order: 3 }),
    ];
    const todayLog = log({
      routine_type: 'housework',
      date: TODAY_MS,
      selected_tier: 'deep',
    });
    const card = buildRoutineCard(
      'housework',
      steps,
      [todayLog],
      TODAY_MS,
      { ...TIER_DEFAULTS, housework: 'quick' },
    );
    expect(card!.steps.map((s) => s.stepId)).toEqual(['hQuick', 'hReg', 'hDeep']);
  });
});

function course(over: Partial<Course> & { id: string }): Course {
  return {
    id: over.id,
    name: over.name ?? 'Course',
    code: over.code ?? '',
    color: over.color ?? 0,
    icon: over.icon ?? '',
    active: over.active ?? true,
    sortOrder: over.sortOrder ?? 0,
    createdAt: over.createdAt ?? 0,
    updatedAt: over.updatedAt ?? 0,
    createDailyTask: over.createDailyTask ?? false,
  };
}

function assignment(over: Partial<Assignment> & { id: string }): Assignment {
  return {
    id: over.id,
    courseId: over.courseId ?? 'c1',
    title: over.title ?? 'Assignment',
    dueDate: over.dueDate ?? null,
    completed: over.completed ?? false,
    completedAt: over.completedAt ?? null,
    notes: over.notes ?? null,
    createdAt: over.createdAt ?? 0,
    updatedAt: over.updatedAt ?? 0,
  };
}

describe('schoolworkHasContent', () => {
  const TODAY_MIDNIGHT = 1_700_000_000_000;
  const TOMORROW_MIDNIGHT = TODAY_MIDNIGHT + 24 * 60 * 60 * 1000;

  it('is true when an active course exists', () => {
    expect(
      schoolworkHasContent([course({ id: 'c1', active: true })], [], TODAY_MIDNIGHT),
    ).toBe(true);
  });

  it('is false when only archived courses exist and no assignments', () => {
    expect(
      schoolworkHasContent([course({ id: 'c1', active: false })], [], TODAY_MIDNIGHT),
    ).toBe(false);
  });

  it('is true when an assignment is due today and not completed', () => {
    expect(
      schoolworkHasContent(
        [course({ id: 'c1', active: false })],
        [assignment({ id: 'a1', dueDate: TODAY_MIDNIGHT + 3600_000 })],
        TODAY_MIDNIGHT,
      ),
    ).toBe(true);
  });

  it('ignores completed assignments due today', () => {
    expect(
      schoolworkHasContent(
        [],
        [
          assignment({
            id: 'a1',
            dueDate: TODAY_MIDNIGHT + 3600_000,
            completed: true,
          }),
        ],
        TODAY_MIDNIGHT,
      ),
    ).toBe(false);
  });

  it('ignores assignments due tomorrow', () => {
    expect(
      schoolworkHasContent(
        [],
        [assignment({ id: 'a1', dueDate: TOMORROW_MIDNIGHT })],
        TODAY_MIDNIGHT,
      ),
    ).toBe(false);
  });

  it('ignores assignments without a due date', () => {
    expect(
      schoolworkHasContent(
        [],
        [assignment({ id: 'a1', dueDate: null })],
        TODAY_MIDNIGHT,
      ),
    ).toBe(false);
  });

  it('returns false when nothing matches', () => {
    expect(schoolworkHasContent([], [], TODAY_MIDNIGHT)).toBe(false);
  });
});
