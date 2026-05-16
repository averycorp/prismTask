import { describe, it, expect } from 'vitest';
import {
  buildRoutineCard,
  pickHouseworkHabit,
} from '@/features/today/DailyEssentialsCards';
import type { SelfCareLog, SelfCareStep } from '@/api/firestore/selfCare';
import type { Habit } from '@/types/habit';

/**
 * Pure-function tests for the Daily Essentials section card builders.
 * Mirrors Android's `DailyEssentialsUseCase.observeRoutineCard` shape
 * (parity unit 6 of 23). No DOM, no Firestore.
 */

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

function habit(over: Partial<Habit> & { id: string; name: string }): Habit {
  return {
    id: over.id,
    user_id: 'u',
    name: over.name,
    description: over.description ?? null,
    icon: over.icon ?? '🏠',
    color: over.color ?? '#000',
    category: over.category ?? null,
    frequency: over.frequency ?? 'daily',
    target_count: over.target_count ?? 1,
    active_days_json: over.active_days_json ?? null,
    is_active: over.is_active ?? true,
    created_at: over.created_at ?? '2026-01-01T00:00:00Z',
    updated_at: over.updated_at ?? '2026-01-01T00:00:00Z',
  };
}

const TODAY_MS = 1_700_000_000_000;

describe('buildRoutineCard (parity unit 6)', () => {
  it('returns null when no steps exist for the routine', () => {
    const card = buildRoutineCard('morning', [], [], TODAY_MS);
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
    const card = buildRoutineCard('morning', steps, [todayLog], TODAY_MS);
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
    const card = buildRoutineCard('morning', steps, [yesterdayLog], TODAY_MS);
    expect(card!.steps[0].completed).toBe(false);
  });

  it('maps the bedtime + housework display names', () => {
    const morningSteps = [step({ step_id: 's1', routine_type: 'morning' })];
    const bedtimeSteps = [step({ step_id: 's1', routine_type: 'bedtime' })];
    const houseworkSteps = [step({ step_id: 's1', routine_type: 'housework' })];
    expect(buildRoutineCard('bedtime', bedtimeSteps, [], TODAY_MS)!.displayName).toBe(
      'Bedtime Routine',
    );
    expect(
      buildRoutineCard('housework', houseworkSteps, [], TODAY_MS)!.displayName,
    ).toBe('Housework');
    expect(buildRoutineCard('morning', morningSteps, [], TODAY_MS)!.displayName).toBe(
      'Morning Routine',
    );
  });
});

describe('pickHouseworkHabit (parity unit 6)', () => {
  it('returns null when no habits exist', () => {
    expect(pickHouseworkHabit([], null)).toBeNull();
  });

  it('returns the pinned habit when active', () => {
    const a = habit({ id: 'a', name: 'Stretch' });
    const b = habit({ id: 'b', name: 'House Stuff' });
    expect(pickHouseworkHabit([a, b], 'b')).toBe(b);
  });

  it('falls back to a name-matched habit when pin is missing', () => {
    const a = habit({ id: 'a', name: 'Stretch' });
    const b = habit({ id: 'b', name: 'Clean Kitchen' });
    expect(pickHouseworkHabit([a, b], null)).toBe(b);
  });

  it('skips inactive pinned habits and falls back', () => {
    const a = habit({ id: 'a', name: 'Stretch' });
    const b = habit({ id: 'b', name: 'House Stuff', is_active: false });
    const c = habit({ id: 'c', name: 'Cleanup Routine' });
    expect(pickHouseworkHabit([a, b, c], 'b')).toBe(c);
  });

  it('returns null when no candidate matches', () => {
    const a = habit({ id: 'a', name: 'Stretch' });
    const b = habit({ id: 'b', name: 'Walk' });
    expect(pickHouseworkHabit([a, b], null)).toBeNull();
  });
});
