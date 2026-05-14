import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDocs,
  orderBy,
  query,
  type DocumentData,
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

export type BoundaryRuleType =
  | 'daily_task_cap'
  | 'work_hours_window'
  | 'weekly_hour_budget';

export interface BoundaryRule {
  id: string;
  type: BoundaryRuleType;
  /** Human-readable summary. For `daily_task_cap` this could be
   *  "Max 12 tasks/day". The editor fills it in; the enforcer uses
   *  it verbatim in breach messages. */
  label: string;
  /** Numeric value the rule compares against — task count for caps,
   *  start hour for window (0–23), budgeted hours/week. */
  value: number;
  /** Optional second value. For `work_hours_window` this is the end
   *  hour (0–23). Unused for other types. */
  secondary_value: number | null;
  enabled: boolean;
  created_at: number;
  updated_at: number;
}

function rulesCol(uid: string) {
  return collection(firestore, 'users', uid, 'boundary_rules');
}

function ruleDoc(uid: string, id: string) {
  return doc(firestore, 'users', uid, 'boundary_rules', id);
}

function docToRule(id: string, data: DocumentData): BoundaryRule {
  const type: BoundaryRuleType =
    data.type === 'work_hours_window' || data.type === 'weekly_hour_budget'
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
  };
}

export interface BoundaryRuleInput {
  type: BoundaryRuleType;
  label: string;
  value: number;
  secondary_value?: number | null;
  enabled?: boolean;
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
  await lwwUpdate(ruleDoc(uid, id), payload as Parameters<typeof lwwUpdate>[1]);
}

export async function deleteRule(uid: string, id: string): Promise<void> {
  await deleteDoc(ruleDoc(uid, id));
}
