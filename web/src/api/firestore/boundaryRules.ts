import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';

/**
 * Firestore-native user-declared boundary rules. Stored at
 * `users/{uid}/boundary_rules`. Mirrors Android's
 * `BoundaryRuleEntity` at the capability level, trimmed to the three
 * rule types the web actively enforces:
 *
 *   - `daily_task_cap`: warn when active tasks for the logical day
 *     exceeds `value`.
 *   - `work_hours_window`: declares a start/end hour window; a
 *     breach flips when the current local hour falls outside it.
 *   - `weekly_hour_budget`: informational cap used by the burnout
 *     scorer; not actively checked minute-by-minute.
 */

/**
 * `daily_task_cap` / `work_hours_window` / `weekly_hour_budget` are the
 * original web-only rule types — the live enforcer + Today banner
 * already understand them.
 *
 * `category_limit` and `escalation` are the parity-19 additions that
 * mirror Android's richer `BoundaryRuleType` enum (BLOCK_CATEGORY /
 * SUGGEST_CATEGORY / REMIND): category-limit rules cap hours/day on a
 * `LifeCategory`, escalation rules describe a notification-profile
 * chain. The enforcer keeps a no-op fallthrough for the new types so
 * existing breach logic doesn't regress — they're surfaced only by the
 * dedicated boundary-rule editor for now.
 */
export type BoundaryRuleType =
  | 'daily_task_cap'
  | 'work_hours_window'
  | 'weekly_hour_budget'
  | 'category_limit'
  | 'escalation';

/** Mirrors Android `LifeCategory` (`domain/model/LifeCategory.kt`). */
export type BoundaryLifeCategory =
  | 'WORK'
  | 'PERSONAL'
  | 'SELF_CARE'
  | 'HEALTH';

export interface BoundaryRule {
  id: string;
  type: BoundaryRuleType;
  /** Human-readable summary. For `daily_task_cap` this could be
   *  "Max 12 tasks/day". The editor fills it in; the enforcer uses
   *  it verbatim in breach messages. */
  label: string;
  /** Numeric value the rule compares against — task count for caps,
   *  start hour for window (0–23), budgeted hours/week,
   *  hours/day cap for `category_limit`, stage delay (minutes) for
   *  `escalation`. */
  value: number;
  /** Optional second value. For `work_hours_window` this is the end
   *  hour (0–23). Unused for other types — minute components and
   *  escalation chains have their own dedicated fields below. */
  secondary_value: number | null;
  enabled: boolean;
  created_at: number;
  updated_at: number;
  /** Optional `LifeCategory` for `category_limit` rules. Stored on
   *  the doc so cross-device readers don't have to re-parse `label`. */
  category?: BoundaryLifeCategory | null;
  /** For `category_limit`: 'max' means at-most, 'min' means at-least. */
  bound?: 'max' | 'min' | null;
  /** Active days as ISO-1 (Mon=1 … Sun=7) ordinals. Empty/missing =
   *  all days. */
  active_days?: number[] | null;
  /** For `work_hours_window`: minute-component of `value` (the start
   *  hour). Lets the editor round-trip 09:30 cleanly. */
  start_minute?: number | null;
  /** For `work_hours_window`: minute-component of `secondary_value`
   *  (the end hour). */
  end_minute?: number | null;
  /** For `escalation`: ordered notification-profile names. */
  escalation_chain?: string[] | null;
  /** For `escalation`: free-form name of the rule that triggers this
   *  escalation. Stored as text so we don't need a foreign-key
   *  lifecycle for now. */
  escalation_trigger?: string | null;
}

function rulesCol(uid: string) {
  return collection(firestore, 'users', uid, 'boundary_rules');
}

function ruleDoc(uid: string, id: string) {
  return doc(firestore, 'users', uid, 'boundary_rules', id);
}

const SUPPORTED_TYPES: ReadonlyArray<BoundaryRuleType> = [
  'daily_task_cap',
  'work_hours_window',
  'weekly_hour_budget',
  'category_limit',
  'escalation',
];

function isSupportedType(value: unknown): value is BoundaryRuleType {
  return (
    typeof value === 'string' &&
    (SUPPORTED_TYPES as ReadonlyArray<string>).includes(value)
  );
}

const SUPPORTED_CATEGORIES: ReadonlyArray<BoundaryLifeCategory> = [
  'WORK',
  'PERSONAL',
  'SELF_CARE',
  'HEALTH',
];

function coerceCategory(value: unknown): BoundaryLifeCategory | null {
  return typeof value === 'string' &&
    (SUPPORTED_CATEGORIES as ReadonlyArray<string>).includes(value)
    ? (value as BoundaryLifeCategory)
    : null;
}

function coerceStringArray(value: unknown): string[] | null {
  if (!Array.isArray(value)) return null;
  const out = value.filter((s): s is string => typeof s === 'string');
  return out.length > 0 ? out : null;
}

function coerceNumberArray(value: unknown): number[] | null {
  if (!Array.isArray(value)) return null;
  const out = value.filter(
    (n): n is number => typeof n === 'number' && n >= 1 && n <= 7,
  );
  return out.length > 0 ? out : null;
}

function docToRule(id: string, data: DocumentData): BoundaryRule {
  const type: BoundaryRuleType = isSupportedType(data.type)
    ? data.type
    : 'daily_task_cap';
  return {
    id,
    type,
    label: typeof data.label === 'string' ? data.label : '',
    value: typeof data.value === 'number' ? data.value : 0,
    secondary_value:
      typeof data.secondaryValue === 'number' ? data.secondaryValue : null,
    enabled: data.enabled !== false,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : Date.now(),
    category: coerceCategory(data.category),
    bound:
      data.bound === 'max' || data.bound === 'min' ? data.bound : null,
    active_days: coerceNumberArray(data.activeDays),
    start_minute:
      typeof data.startMinute === 'number' ? data.startMinute : null,
    end_minute: typeof data.endMinute === 'number' ? data.endMinute : null,
    escalation_chain: coerceStringArray(data.escalationChain),
    escalation_trigger:
      typeof data.escalationTrigger === 'string'
        ? data.escalationTrigger
        : null,
  };
}

export interface BoundaryRuleInput {
  type: BoundaryRuleType;
  label: string;
  value: number;
  secondary_value?: number | null;
  enabled?: boolean;
  category?: BoundaryLifeCategory | null;
  bound?: 'max' | 'min' | null;
  active_days?: number[] | null;
  start_minute?: number | null;
  end_minute?: number | null;
  escalation_chain?: string[] | null;
  escalation_trigger?: string | null;
}

export async function getRules(uid: string): Promise<BoundaryRule[]> {
  const snap = await getDocs(
    query(rulesCol(uid), orderBy('createdAt', 'asc')),
  );
  return snap.docs.map((d) => docToRule(d.id, d.data()));
}

export async function createRule(
  uid: string,
  input: BoundaryRuleInput,
): Promise<BoundaryRule> {
  const now = Date.now();
  const payload = {
    type: input.type,
    label: input.label,
    value: input.value,
    secondaryValue: input.secondary_value ?? null,
    enabled: input.enabled ?? true,
    createdAt: now,
    updatedAt: now,
    category: input.category ?? null,
    bound: input.bound ?? null,
    activeDays: input.active_days ?? null,
    startMinute: input.start_minute ?? null,
    endMinute: input.end_minute ?? null,
    escalationChain: input.escalation_chain ?? null,
    escalationTrigger: input.escalation_trigger ?? null,
  };
  const ref = await addDoc(rulesCol(uid), payload);
  return docToRule(ref.id, payload);
}

export async function updateRule(
  uid: string,
  id: string,
  input: Partial<BoundaryRuleInput>,
): Promise<void> {
  // LWW guard. An Android-side enable/disable toggle (e.g. burnout
  // scorer auto-suspending a rule) shouldn't be silently undone by a
  // concurrent web label-rename. Parity audit A.2.
  const now = Date.now();
  const payload: Record<string, unknown> = { updatedAt: now };
  if (input.type !== undefined) payload.type = input.type;
  if (input.label !== undefined) payload.label = input.label;
  if (input.value !== undefined) payload.value = input.value;
  if (input.secondary_value !== undefined)
    payload.secondaryValue = input.secondary_value;
  if (input.enabled !== undefined) payload.enabled = input.enabled;
  if (input.category !== undefined) payload.category = input.category;
  if (input.bound !== undefined) payload.bound = input.bound;
  if (input.active_days !== undefined) payload.activeDays = input.active_days;
  if (input.start_minute !== undefined)
    payload.startMinute = input.start_minute;
  if (input.end_minute !== undefined) payload.endMinute = input.end_minute;
  if (input.escalation_chain !== undefined)
    payload.escalationChain = input.escalation_chain;
  if (input.escalation_trigger !== undefined)
    payload.escalationTrigger = input.escalation_trigger;
  await lwwUpdate(ruleDoc(uid, id), payload as Parameters<typeof lwwUpdate>[1]);
}

export async function deleteRule(uid: string, id: string): Promise<void> {
  await deleteDoc(ruleDoc(uid, id));
}

// ── Real-time listener ───────────────────────────────────────

/**
 * Subscribe to the user's boundary-rule collection. Wired from
 * `useFirestoreSync` so cross-device edits (Android adds a work-hours
 * window, web should reflect it immediately in the Today banner +
 * Settings list without a refresh). Closes parity audit § A.1b for
 * `boundaryRules` — the firestore module already has full CRUD, only
 * the live-listener piece was missing.
 */
export function subscribeToRules(
  uid: string,
  callback: (rules: BoundaryRule[]) => void,
): Unsubscribe {
  const q = query(rulesCol(uid), orderBy('createdAt', 'asc'));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToRule(d.id, d.data())));
  });
}
