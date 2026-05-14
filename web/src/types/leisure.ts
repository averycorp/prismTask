/**
 * Leisure Budget v2.0 types — mirrors the FastAPI Pydantic schemas at
 * `backend/app/schemas/leisure.py` and the Android Room entities
 * `LeisureActivityEntity` / `LeisureSessionEntity`.
 *
 * Categories are spec-locked server-side to the four built-in buckets via a
 * CHECK constraint; user-defined custom categories stay client-side only (the
 * Android counterpart lives in `LeisureBudgetPreferences` DataStore under the
 * `custom:<uuid8>` id namespace). Custom-tagged activities/sessions do NOT
 * round-trip through the REST API and are filtered out at the network
 * boundary by `leisureApi.createActivity` / `createSession`.
 */

export type LeisureCategory = 'PHYSICAL' | 'SOCIAL' | 'CREATIVE' | 'PASSIVE';
export type LeisureEnforcementMode = 'SOFT' | 'MEDIUM' | 'HARD';
export type LeisureSessionSource = 'TIMER' | 'MANUAL';

export const LEISURE_CATEGORIES: readonly LeisureCategory[] = [
  'PHYSICAL',
  'SOCIAL',
  'CREATIVE',
  'PASSIVE',
] as const;

/**
 * Display metadata for the four built-in categories. Custom-category labels
 * + emojis are stored alongside the id in `leisureStore.customCategories`
 * (later PR). Mirrors `LeisureCategory.kt`.
 */
export const LEISURE_CATEGORY_DISPLAY: Record<
  LeisureCategory,
  { emoji: string; label: string }
> = {
  PHYSICAL: { emoji: '🏃', label: 'Physical' },
  SOCIAL: { emoji: '👥', label: 'Social' },
  CREATIVE: { emoji: '🎨', label: 'Creative' },
  PASSIVE: { emoji: '🛋️', label: 'Passive' },
};

export interface LeisureActivity {
  id: string;
  name: string;
  category: LeisureCategory;
  default_duration_minutes: number | null;
  enabled: boolean;
  created_at: string;
  updated_at: string;
  last_completed_at: string | null;
}

export interface LeisureActivityCreate {
  id: string;
  name: string;
  category: LeisureCategory;
  default_duration_minutes?: number | null;
  enabled?: boolean;
}

export interface LeisureActivityUpdate {
  name?: string;
  category?: LeisureCategory;
  default_duration_minutes?: number | null;
  enabled?: boolean;
}

export interface LeisureSession {
  id: string;
  activity_id: string | null;
  category: LeisureCategory;
  duration_minutes: number;
  logged_at: string;
  source: LeisureSessionSource;
  created_at: string;
}

export interface LeisureSessionCreate {
  id: string;
  activity_id?: string | null;
  category: LeisureCategory;
  duration_minutes: number;
  logged_at: string;
  source: LeisureSessionSource;
}

export interface LeisureSettings {
  daily_target_minutes: number;
  weekend_target_minutes: number | null;
  enforcement_mode: LeisureEnforcementMode;
  refresh_limit: number;
  enabled_categories: LeisureCategory[];
  pending_enforcement_mode: LeisureEnforcementMode | null;
  pending_enforcement_effective_date: string | null;
  updated_at: string;
}

export interface LeisureSettingsUpdate {
  daily_target_minutes?: number;
  weekend_target_minutes?: number | null;
  enforcement_mode?: LeisureEnforcementMode;
  refresh_limit?: number;
  enabled_categories?: LeisureCategory[];
  promote_pending_enforcement?: boolean;
}

/**
 * Parse a stored category string back into a known LeisureCategory, or null
 * if the value is a custom-namespace id or otherwise unrecognized.
 */
export function parseLeisureCategory(
  value: string | null | undefined,
): LeisureCategory | null {
  if (!value) return null;
  return (LEISURE_CATEGORIES as readonly string[]).includes(value)
    ? (value as LeisureCategory)
    : null;
}

export const CUSTOM_CATEGORY_PREFIX = 'custom:';

export function isCustomCategoryId(value: string | null | undefined): boolean {
  return typeof value === 'string' && value.startsWith(CUSTOM_CATEGORY_PREFIX);
}
