import { describe, expect, it } from 'vitest';
import { isMetaBuiltInHabit } from '@/utils/metaBuiltInHabit';
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

describe('isMetaBuiltInHabit', () => {
  it.each([
    ['builtin_morning_selfcare', 'Morning Self-Care'],
    ['builtin_bedtime_selfcare', 'Bedtime Self-Care'],
    ['builtin_medication', 'Medication'],
    ['builtin_housework', 'Housework'],
    ['builtin_school', 'School'],
    ['builtin_leisure', 'Leisure'],
  ])('matches by built-in template key %s', (templateKey, name) => {
    expect(
      isMetaBuiltInHabit(habit({ name, template_key: templateKey })),
    ).toBe(true);
  });

  it('matches a renamed built-in by template key alone', () => {
    expect(
      isMetaBuiltInHabit(
        habit({
          name: 'My Custom Name',
          template_key: 'builtin_school',
          is_built_in: true,
        }),
      ),
    ).toBe(true);
  });

  it.each([
    'Morning Self-Care',
    'Bedtime Self-Care',
    'Medication',
    'Housework',
    'School',
    'Leisure',
  ])('matches legacy row "%s" by name when template_key is missing', (name) => {
    expect(
      isMetaBuiltInHabit(habit({ name, is_built_in: true, template_key: null })),
    ).toBe(true);
  });

  it('does not match user-created habits literally named after a built-in', () => {
    expect(
      isMetaBuiltInHabit(habit({ name: 'Leisure', is_built_in: false })),
    ).toBe(false);
    expect(
      isMetaBuiltInHabit(habit({ name: 'School', is_built_in: undefined })),
    ).toBe(false);
  });

  it('does not match unknown template keys', () => {
    expect(
      isMetaBuiltInHabit(
        habit({
          name: 'Workday Setup',
          template_key: 'builtin_workday_setup',
          is_built_in: true,
        }),
      ),
    ).toBe(false);
  });

  it('returns false for ordinary user habits', () => {
    expect(
      isMetaBuiltInHabit(habit({ name: 'Drink Water', template_key: null })),
    ).toBe(false);
  });
});
