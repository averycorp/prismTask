import { describe, expect, it } from 'vitest';
import { isMedicationBuiltInHabit } from '@/utils/medicationBuiltInHabit';
import type { Habit } from '@/types/habit';

function habit(partial: Partial<Habit>): Habit {
  return {
    id: 'h1',
    user_id: 'u1',
    name: 'Habit',
    description: null,
    icon: null,
    color: null,
    category: null,
    frequency: 'daily',
    target_count: 1,
    active_days_json: null,
    is_active: true,
    created_at: '2026-05-18T00:00:00.000Z',
    updated_at: '2026-05-18T00:00:00.000Z',
    ...partial,
  };
}

describe('isMedicationBuiltInHabit', () => {
  it('matches by builtin_medication template key', () => {
    expect(
      isMedicationBuiltInHabit(
        habit({ name: 'My Pills', template_key: 'builtin_medication' }),
      ),
    ).toBe(true);
  });

  it('matches a renamed built-in by template key alone', () => {
    expect(
      isMedicationBuiltInHabit(
        habit({
          name: 'Whatever The User Renamed It To',
          template_key: 'builtin_medication',
          is_built_in: true,
        }),
      ),
    ).toBe(true);
  });

  it('matches legacy built-in rows by name when template_key is missing', () => {
    expect(
      isMedicationBuiltInHabit(
        habit({ name: 'Medication', is_built_in: true, template_key: null }),
      ),
    ).toBe(true);
  });

  it('does not match user-created habits literally named "Medication"', () => {
    expect(
      isMedicationBuiltInHabit(
        habit({ name: 'Medication', is_built_in: false }),
      ),
    ).toBe(false);
    expect(
      isMedicationBuiltInHabit(
        habit({ name: 'Medication', is_built_in: undefined }),
      ),
    ).toBe(false);
  });

  it('does not match other built-in template keys', () => {
    expect(
      isMedicationBuiltInHabit(
        habit({
          name: 'Morning Self-Care',
          template_key: 'builtin_morning_selfcare',
          is_built_in: true,
        }),
      ),
    ).toBe(false);
    expect(
      isMedicationBuiltInHabit(
        habit({
          name: 'Housework',
          template_key: 'builtin_housework',
          is_built_in: true,
        }),
      ),
    ).toBe(false);
  });

  it('returns false for ordinary user habits', () => {
    expect(
      isMedicationBuiltInHabit(
        habit({ name: 'Drink Water', template_key: null }),
      ),
    ).toBe(false);
  });
});
