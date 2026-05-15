import {
  collection,
  doc,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  setDoc,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';

/**
 * Web mirror of Android's `SelfCareLogEntity` / `SelfCareStepEntity`.
 *
 * Android already pushes both collections to Firestore from
 * `SyncService.runSelfCareLogsBackfillIfNeeded` /
 * `runSelfCareStepsBackfillIfNeeded` and on every `trackUpdate`. Doc
 * IDs there are auto-generated (`userCollection().document()`) and the
 * server stores the natural-key tuple `(routine_type, date)` in
 * payload fields. This module is read-first so the web surface stays
 * consistent with what the phone wrote without forcing Android to
 * adopt deterministic IDs.
 *
 * Per-step toggles from web write through the LWW guard against the
 * existing Android-authored doc when one exists for today's
 * `(routine_type, date)` natural key. A web-first toggle (no Android
 * doc yet) creates a deterministic doc id `${routineType}__${dateMs}`
 * so the next pull on Android merges into the same row instead of
 * producing a duplicate that would later collide on the unique index.
 */

export type RoutineType = 'morning' | 'bedtime' | 'medication' | 'housework';

export interface SelfCareLog {
  id: string;
  routine_type: string;
  /** Logical-day epoch-ms boundary that Android computed via
   *  `DayBoundary.startOfCurrentDay`. Use as the natural-key date. */
  date: number;
  selected_tier: string;
  /** Raw JSON string from Android — for non-medication routines this
   *  is `["stepId1", "stepId2"]`; for medication it's a list of
   *  `MedStepLog` objects. */
  completed_steps: string;
  tiers_by_time: string;
  is_complete: boolean;
  started_at: number | null;
  created_at: number;
  updated_at: number;
}

export interface SelfCareStep {
  id: string;
  step_id: string;
  routine_type: string;
  label: string;
  duration: string;
  tier: string;
  note: string;
  phase: string;
  sort_order: number;
  reminder_delay_millis: number | null;
  time_of_day: string;
  medication_name: string | null;
  source_version: number;
  updated_at: number;
}

function logsCol(uid: string) {
  return collection(firestore, 'users', uid, 'self_care_logs');
}

function stepsCol(uid: string) {
  return collection(firestore, 'users', uid, 'self_care_steps');
}

function logDoc(uid: string, id: string) {
  return doc(firestore, 'users', uid, 'self_care_logs', id);
}

function deterministicLogId(routineType: string, dateMs: number): string {
  return `${routineType}__${dateMs}`;
}

function docToLog(id: string, data: DocumentData): SelfCareLog {
  return {
    id,
    routine_type: typeof data.routineType === 'string' ? data.routineType : '',
    date: typeof data.date === 'number' ? data.date : 0,
    selected_tier:
      typeof data.selectedTier === 'string' ? data.selectedTier : 'solid',
    completed_steps:
      typeof data.completedSteps === 'string' ? data.completedSteps : '[]',
    tiers_by_time:
      typeof data.tiersByTime === 'string' ? data.tiersByTime : '{}',
    is_complete: !!data.isComplete,
    started_at: typeof data.startedAt === 'number' ? data.startedAt : null,
    created_at:
      typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at:
      typeof data.updatedAt === 'number' ? data.updatedAt : Date.now(),
  };
}

function docToStep(id: string, data: DocumentData): SelfCareStep {
  return {
    id,
    step_id: typeof data.stepId === 'string' ? data.stepId : '',
    routine_type: typeof data.routineType === 'string' ? data.routineType : '',
    label: typeof data.label === 'string' ? data.label : '',
    duration: typeof data.duration === 'string' ? data.duration : '',
    tier: typeof data.tier === 'string' ? data.tier : '',
    note: typeof data.note === 'string' ? data.note : '',
    phase: typeof data.phase === 'string' ? data.phase : '',
    sort_order: typeof data.sortOrder === 'number' ? data.sortOrder : 0,
    reminder_delay_millis:
      typeof data.reminderDelayMillis === 'number'
        ? data.reminderDelayMillis
        : null,
    time_of_day:
      typeof data.timeOfDay === 'string' ? data.timeOfDay : 'morning',
    medication_name:
      typeof data.medicationName === 'string' ? data.medicationName : null,
    source_version:
      typeof data.sourceVersion === 'number' ? data.sourceVersion : 0,
    updated_at:
      typeof data.updatedAt === 'number' ? data.updatedAt : Date.now(),
  };
}

export async function getAllLogs(uid: string): Promise<SelfCareLog[]> {
  const snap = await getDocs(query(logsCol(uid), orderBy('date', 'desc')));
  return snap.docs.map((d) => docToLog(d.id, d.data()));
}

export async function getAllSteps(uid: string): Promise<SelfCareStep[]> {
  const snap = await getDocs(query(stepsCol(uid), orderBy('sortOrder', 'asc')));
  return snap.docs.map((d) => docToStep(d.id, d.data()));
}

export function subscribeToSelfCareLogs(
  uid: string,
  callback: (logs: SelfCareLog[]) => void,
): Unsubscribe {
  return onSnapshot(query(logsCol(uid), orderBy('date', 'desc')), (snap) => {
    callback(snap.docs.map((d) => docToLog(d.id, d.data())));
  });
}

export function subscribeToSelfCareSteps(
  uid: string,
  callback: (steps: SelfCareStep[]) => void,
): Unsubscribe {
  return onSnapshot(
    query(stepsCol(uid), orderBy('sortOrder', 'asc')),
    (snap) => {
      callback(snap.docs.map((d) => docToStep(d.id, d.data())));
    },
  );
}

/**
 * Toggle a single step in a non-medication routine. Reads the current
 * `completed_steps` JSON list, flips membership of `stepId`, and
 * writes back through LWW. Returns the new completed-step set so the
 * caller can update local state optimistically.
 *
 * If no log row exists yet for today, creates one with deterministic
 * id so a later Android pull merges instead of creating a duplicate
 * for the same `(routine_type, date)` natural key.
 */
export async function toggleStep(
  uid: string,
  routineType: string,
  dateMs: number,
  stepId: string,
  existing: SelfCareLog | null,
): Promise<string[]> {
  const now = Date.now();
  const current = existing
    ? parseStepList(existing.completed_steps)
    : new Set<string>();
  if (current.has(stepId)) {
    current.delete(stepId);
  } else {
    current.add(stepId);
  }
  const completedJson = JSON.stringify(Array.from(current));
  const id = existing?.id ?? deterministicLogId(routineType, dateMs);
  const ref = logDoc(uid, id);
  if (!existing) {
    await setDoc(
      ref,
      {
        routineType,
        date: dateMs,
        selectedTier: 'solid',
        completedSteps: completedJson,
        tiersByTime: '{}',
        isComplete: false,
        startedAt: now,
        createdAt: now,
        updatedAt: now,
      },
      { merge: true },
    );
  } else {
    await lwwUpdate(ref, {
      completedSteps: completedJson,
      updatedAt: now,
    });
  }
  return Array.from(current);
}

function parseStepList(json: string): Set<string> {
  if (!json || json === '[]') return new Set();
  try {
    const parsed = JSON.parse(json);
    if (Array.isArray(parsed)) {
      return new Set(parsed.filter((v): v is string => typeof v === 'string'));
    }
  } catch {
    // Fall through to empty set — malformed JSON should not crash UI.
  }
  return new Set();
}

export function parseCompletedStepsForDisplay(json: string): string[] {
  if (!json || json === '[]') return [];
  try {
    const parsed = JSON.parse(json);
    if (Array.isArray(parsed)) {
      return parsed
        .map((v): string | null => {
          if (typeof v === 'string') return v;
          if (v && typeof v === 'object' && typeof v.id === 'string') {
            return v.id;
          }
          return null;
        })
        .filter((v): v is string => v !== null);
    }
  } catch {
    // Same fall-through as parseStepList.
  }
  return [];
}
