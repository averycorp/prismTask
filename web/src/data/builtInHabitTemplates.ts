/**
 * Web mirror of Android's `BuiltInHabitVersionRegistry`
 * (`app/.../data/seed/BuiltInHabitVersionRegistry.kt`). Each definition
 * carries the current `version` for its `templateKey`; the reconciler
 * compares this to each habit's persisted `source_version` to surface
 * "template update available" prompts.
 *
 * KEEP IN SYNC: when Android bumps a `BuiltInHabitDefinition.version`,
 * mirror that bump here so web users see the same update prompt. The
 * shipped Android baseline is v1 for every key (parity audit B.4,
 * 2026-05-13). Steps + self-care payloads are intentionally NOT
 * carried on web — web has no `self_care_steps` table, so the diff
 * applies only at the habit-row level (name / description / frequency /
 * target / active days). Step-level diffs live behind the Android
 * `BuiltInUpdateDetector` and are not surfaced on web.
 */
export interface BuiltInHabitDefinition {
  templateKey: string;
  version: number;
  name: string;
  description: string | null;
  frequency: 'daily' | 'weekly';
  targetCount: number;
  /** ISO-day CSV (`"1,2,3"`) or empty string for "every active day". */
  activeDaysCsv: string;
}

export const BUILT_IN_TEMPLATE_KEYS = {
  SCHOOL: 'builtin_school',
  LEISURE: 'builtin_leisure',
  MORNING_SELFCARE: 'builtin_morning_selfcare',
  BEDTIME_SELFCARE: 'builtin_bedtime_selfcare',
  MEDICATION: 'builtin_medication',
  HOUSEWORK: 'builtin_housework',
} as const;

const DEFINITIONS: BuiltInHabitDefinition[] = [
  {
    templateKey: BUILT_IN_TEMPLATE_KEYS.SCHOOL,
    version: 1,
    name: 'School',
    description: null,
    frequency: 'daily',
    targetCount: 1,
    activeDaysCsv: '',
  },
  {
    templateKey: BUILT_IN_TEMPLATE_KEYS.LEISURE,
    version: 1,
    name: 'Leisure',
    description: null,
    frequency: 'daily',
    targetCount: 1,
    activeDaysCsv: '',
  },
  {
    templateKey: BUILT_IN_TEMPLATE_KEYS.MORNING_SELFCARE,
    version: 1,
    name: 'Morning Self-Care',
    description: null,
    frequency: 'daily',
    targetCount: 1,
    activeDaysCsv: '',
  },
  {
    templateKey: BUILT_IN_TEMPLATE_KEYS.BEDTIME_SELFCARE,
    version: 1,
    name: 'Bedtime Self-Care',
    description: null,
    frequency: 'daily',
    targetCount: 1,
    activeDaysCsv: '',
  },
  {
    templateKey: BUILT_IN_TEMPLATE_KEYS.MEDICATION,
    version: 1,
    name: 'Medication',
    description: null,
    frequency: 'daily',
    targetCount: 1,
    activeDaysCsv: '',
  },
  {
    templateKey: BUILT_IN_TEMPLATE_KEYS.HOUSEWORK,
    version: 1,
    name: 'Housework',
    description: null,
    frequency: 'daily',
    targetCount: 1,
    activeDaysCsv: '',
  },
];

const BY_KEY = new Map<string, BuiltInHabitDefinition>(
  DEFINITIONS.map((def) => [def.templateKey, def]),
);

export function builtInDefinition(
  templateKey: string,
): BuiltInHabitDefinition | null {
  return BY_KEY.get(templateKey) ?? null;
}

export function builtInVersionFor(templateKey: string): number {
  return BY_KEY.get(templateKey)?.version ?? 0;
}

export function allBuiltInDefinitions(): BuiltInHabitDefinition[] {
  return [...DEFINITIONS];
}

export function isKnownBuiltInTemplate(templateKey: string): boolean {
  return BY_KEY.has(templateKey);
}
