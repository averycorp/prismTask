import {
  doc,
  updateDoc,
  getDoc,
  setDoc,
  deleteDoc,
  collection,
  query,
  where,
  getDocs,
  type DocumentData,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { dateStrToTimestamp } from '@/api/firestore/converters';
import { setTagsForTask } from '@/api/firestore/tasks';
import * as firestoreTags from '@/api/firestore/tags';
import * as medicationSlots from '@/api/firestore/medicationSlots';
import type { MedicationTier } from '@/api/firestore/medicationSlots';
import type {
  BatchMutationType,
  BatchUndoLogEntry,
  ProposedMutation,
} from '@/types/batch';

/**
 * Applies and undoes a single batch mutation against Firestore.
 *
 * Mirrors the Android `BatchOperationsRepository` shape but operates
 * directly on Firestore docs instead of going through Room. Each apply
 * captures a `pre_state` snapshot of exactly the fields it overwrites,
 * which is what the undo path reads back.
 *
 * Scope:
 *   - TASK: RESCHEDULE, DELETE, COMPLETE, PRIORITY_CHANGE, PROJECT_MOVE,
 *     TAG_CHANGE (slice 15 wired task tag persistence + resolver).
 *   - HABIT: COMPLETE, SKIP, ARCHIVE, DELETE
 *   - PROJECT: ARCHIVE, DELETE
 *   - MEDICATION: COMPLETE, SKIP, STATE_CHANGE (writes the slot's
 *     tier-state at `users/{uid}/medication_tier_states/{date}__{slot}`).
 *     Web has no per-medication dose collection, so multiple medication
 *     mutations targeting the same (date, slot) collapse onto the same
 *     tier-state row idempotently — matches the slot-level UX. DELETE
 *     on MEDICATION is not supported on web because there is no per-
 *     medication dose to remove (Android dose-tracking has no web peer).
 */

export interface ApplyOutcome {
  applied: boolean;
  entry?: BatchUndoLogEntry;
  reason?: string;
}

function taskDoc(uid: string, id: string) {
  return doc(firestore, 'users', uid, 'tasks', id);
}

function habitDoc(uid: string, id: string) {
  return doc(firestore, 'users', uid, 'habits', id);
}

function projectDoc(uid: string, id: string) {
  return doc(firestore, 'users', uid, 'projects', id);
}

function habitCompletionsCol(uid: string) {
  return collection(firestore, 'users', uid, 'habit_completions');
}

/**
 * Mirrors `web/src/api/firestore/habits.ts habitCompletionId`. Doc id =
 * `${habitCloudId}__${completedDateLocal}` so two devices completing
 * the same habit on the same logical day collapse into one Firestore
 * doc rather than producing siblings.
 */
function habitCompletionId(habitCloudId: string, completedDateLocal: string): string {
  return `${habitCloudId}__${completedDateLocal}`;
}

function habitCompletionDoc(uid: string, completionId: string) {
  return doc(firestore, 'users', uid, 'habit_completions', completionId);
}

function parseIsoDateToMillis(iso: string): number | null {
  const ms = dateStrToTimestamp(iso);
  return ms == null || Number.isNaN(ms) ? null : ms;
}

/** Apply one proposed mutation. Returns the undo-log entry on success.
 *  On failure, returns `{ applied: false, reason }` — the caller records
 *  this as a skipped entry so the undo log still reflects the batch. */
export async function applyMutation(
  uid: string,
  mutation: ProposedMutation,
): Promise<ApplyOutcome> {
  const { entity_type, mutation_type, entity_id, proposed_new_values } = mutation;
  try {
    switch (entity_type) {
      case 'TASK':
        return await applyTaskMutation(
          uid,
          entity_id,
          mutation_type,
          proposed_new_values,
        );
      case 'HABIT':
        return await applyHabitMutation(
          uid,
          entity_id,
          mutation_type,
          proposed_new_values,
        );
      case 'PROJECT':
        return await applyProjectMutation(uid, entity_id, mutation_type);
      case 'MEDICATION':
        return await applyMedicationMutation(
          uid,
          entity_id,
          mutation_type,
          proposed_new_values,
        );
    }
  } catch (e) {
    return { applied: false, reason: (e as Error).message || 'apply failed' };
  }
}

async function applyTaskMutation(
  uid: string,
  id: string,
  mutationType: BatchMutationType,
  values: Record<string, unknown>,
): Promise<ApplyOutcome> {
  const snap = await getDoc(taskDoc(uid, id));
  if (!snap.exists()) return { applied: false, reason: 'task not found' };
  const data = snap.data() as DocumentData;
  const now = Date.now();

  switch (mutationType) {
    case 'RESCHEDULE': {
      const newDue = typeof values.due_date === 'string'
        ? parseIsoDateToMillis(values.due_date)
        : null;
      await updateDoc(taskDoc(uid, id), {
        dueDate: newDue,
        updatedAt: now,
      });
      return {
        applied: true,
        entry: {
          entity_type: 'TASK',
          entity_id: id,
          mutation_type: mutationType,
          pre_state: {
            dueDate: data.dueDate ?? null,
            scheduledStartTime: data.scheduledStartTime ?? null,
          },
          applied: true,
        },
      };
    }
    case 'DELETE': {
      await updateDoc(taskDoc(uid, id), { archivedAt: now, updatedAt: now });
      return {
        applied: true,
        entry: {
          entity_type: 'TASK',
          entity_id: id,
          mutation_type: mutationType,
          pre_state: { archivedAt: data.archivedAt ?? null },
          applied: true,
        },
      };
    }
    case 'COMPLETE': {
      await updateDoc(taskDoc(uid, id), {
        isCompleted: true,
        completedAt: now,
        webStatus: 'done',
        updatedAt: now,
      });
      return {
        applied: true,
        entry: {
          entity_type: 'TASK',
          entity_id: id,
          mutation_type: mutationType,
          pre_state: {
            isCompleted: data.isCompleted ?? false,
            completedAt: data.completedAt ?? null,
            webStatus: data.webStatus ?? (data.isCompleted ? 'done' : 'todo'),
          },
          applied: true,
        },
      };
    }
    case 'PRIORITY_CHANGE': {
      const newPriority = typeof values.priority === 'number' ? values.priority : null;
      if (newPriority == null) {
        return { applied: false, reason: 'missing priority value' };
      }
      await updateDoc(taskDoc(uid, id), {
        priority: newPriority,
        updatedAt: now,
      });
      return {
        applied: true,
        entry: {
          entity_type: 'TASK',
          entity_id: id,
          mutation_type: mutationType,
          pre_state: { priority: data.priority ?? 0 },
          applied: true,
        },
      };
    }
    case 'PROJECT_MOVE': {
      const newProjectId = typeof values.project_id === 'string'
        ? values.project_id
        : null;
      await updateDoc(taskDoc(uid, id), {
        projectId: newProjectId,
        updatedAt: now,
      });
      return {
        applied: true,
        entry: {
          entity_type: 'TASK',
          entity_id: id,
          mutation_type: mutationType,
          pre_state: { projectId: data.projectId ?? null },
          applied: true,
        },
      };
    }
    case 'TAG_CHANGE': {
      const addNames = Array.isArray(values.tags_added)
        ? (values.tags_added as unknown[]).filter(
            (x): x is string => typeof x === 'string',
          )
        : [];
      const removeNames = Array.isArray(values.tags_removed)
        ? (values.tags_removed as unknown[]).filter(
            (x): x is string => typeof x === 'string',
          )
        : [];
      // Resolve names -> tag IDs. Unknown names are auto-created, matching
      // Android's `applyTagDelta` behavior in BatchOperationsRepository.kt.
      const allTags = await firestoreTags.getTags(uid);
      const lowerToTag = new Map(
        allTags.map((t) => [t.name.toLowerCase(), t]),
      );
      const addIds: string[] = [];
      for (const name of addNames) {
        const existing = lowerToTag.get(name.toLowerCase());
        if (existing) {
          addIds.push(existing.id);
          continue;
        }
        const created = await firestoreTags.createTag(uid, { name });
        lowerToTag.set(name.toLowerCase(), created);
        addIds.push(created.id);
      }
      const removeIds = new Set(
        removeNames
          .map((n) => lowerToTag.get(n.toLowerCase())?.id)
          .filter((id): id is string => typeof id === 'string'),
      );
      const priorIds: string[] = Array.isArray(data.tagIds)
        ? (data.tagIds as unknown[]).filter(
            (x): x is string => typeof x === 'string',
          )
        : [];
      const nextIds = Array.from(
        new Set([...priorIds.filter((id) => !removeIds.has(id)), ...addIds]),
      );
      await setTagsForTask(uid, id, nextIds);
      await updateDoc(taskDoc(uid, id), { updatedAt: now });
      return {
        applied: true,
        entry: {
          entity_type: 'TASK',
          entity_id: id,
          mutation_type: mutationType,
          pre_state: { tagIds: priorIds },
          applied: true,
        },
      };
    }
    default:
      return { applied: false, reason: `unsupported task mutation: ${mutationType}` };
  }
}

async function applyHabitMutation(
  uid: string,
  id: string,
  mutationType: BatchMutationType,
  values: Record<string, unknown>,
): Promise<ApplyOutcome> {
  const snap = await getDoc(habitDoc(uid, id));
  if (!snap.exists()) return { applied: false, reason: 'habit not found' };
  const data = snap.data() as DocumentData;
  const now = Date.now();

  switch (mutationType) {
    case 'COMPLETE': {
      const dateIso = typeof values.date === 'string'
        ? values.date
        : new Date().toISOString().slice(0, 10);
      const dateMs = parseIsoDateToMillis(dateIso) ?? now;
      const completionId = habitCompletionId(id, dateIso);
      await setDoc(
        habitCompletionDoc(uid, completionId),
        {
          habitCloudId: id,
          completedDate: dateMs,
          completedDateLocal: dateIso,
          completedAt: now,
          notes: null,
        },
        { merge: true },
      );
      return {
        applied: true,
        entry: {
          entity_type: 'HABIT',
          entity_id: id,
          mutation_type: mutationType,
          pre_state: {
            date_iso: dateIso,
            completion_doc_id: completionId,
          },
          applied: true,
        },
      };
    }
    case 'SKIP': {
      // SKIP = delete any completion row for this (habit, date). Snapshot
      // the deleted docs so undo can re-create them.
      const dateIso = typeof values.date === 'string'
        ? values.date
        : new Date().toISOString().slice(0, 10);
      const dateMs = parseIsoDateToMillis(dateIso);
      if (dateMs == null) {
        return { applied: false, reason: 'invalid date for SKIP' };
      }
      const q = query(
        habitCompletionsCol(uid),
        where('habitCloudId', '==', id),
        where('completedDate', '==', dateMs),
      );
      const qs = await getDocs(q);
      const deleted: Array<{ id: string; data: DocumentData }> = [];
      for (const d of qs.docs) {
        deleted.push({ id: d.id, data: d.data() });
        await deleteDoc(d.ref);
      }
      return {
        applied: true,
        entry: {
          entity_type: 'HABIT',
          entity_id: id,
          mutation_type: mutationType,
          pre_state: {
            date_iso: dateIso,
            deleted_completions: deleted,
          },
          applied: true,
        },
      };
    }
    case 'ARCHIVE':
    case 'DELETE': {
      await updateDoc(habitDoc(uid, id), { isArchived: true, updatedAt: now });
      return {
        applied: true,
        entry: {
          entity_type: 'HABIT',
          entity_id: id,
          mutation_type: mutationType,
          pre_state: { isArchived: data.isArchived ?? false },
          applied: true,
        },
      };
    }
    default:
      return { applied: false, reason: `unsupported habit mutation: ${mutationType}` };
  }
}

async function applyMedicationMutation(
  uid: string,
  entityId: string,
  mutationType: BatchMutationType,
  values: Record<string, unknown>,
): Promise<ApplyOutcome> {
  const slotKey = typeof values.slot_key === 'string' ? values.slot_key : null;
  if (slotKey == null) {
    return { applied: false, reason: 'missing slot_key' };
  }
  const dateIso =
    typeof values.date === 'string'
      ? values.date
      : new Date().toISOString().slice(0, 10);

  // Resolve the desired tier per mutation. Web has no per-medication dose
  // collection — every medication mutation collapses onto the slot's
  // tier-state row. Multiple mutations on the same (date, slot) are
  // therefore idempotent at write time.
  let nextTier: MedicationTier | null;
  switch (mutationType) {
    case 'COMPLETE':
      nextTier = 'complete';
      break;
    case 'SKIP':
      nextTier = 'skipped';
      break;
    case 'STATE_CHANGE': {
      const raw = typeof values.tier === 'string' ? values.tier.toLowerCase() : '';
      if (
        raw === 'skipped' ||
        raw === 'essential' ||
        raw === 'prescription' ||
        raw === 'complete'
      ) {
        nextTier = raw;
      } else {
        return { applied: false, reason: `invalid tier: ${values.tier}` };
      }
      break;
    }
    case 'DELETE':
      // Web has no per-medication dose to remove; the matching Android
      // path deletes a `MedicationDoseEntity` row. Skip with a reason
      // so the preview screen can surface "not supported on web."
      return {
        applied: false,
        reason: 'DELETE on MEDICATION not supported on web (no per-dose tracking)',
      };
    default:
      return {
        applied: false,
        reason: `unsupported medication mutation: ${mutationType}`,
      };
  }

  const prior = await medicationSlots.getTierState(uid, dateIso, slotKey);
  await medicationSlots.setTierState(uid, dateIso, slotKey, nextTier, 'user_set');
  return {
    applied: true,
    entry: {
      entity_type: 'MEDICATION',
      entity_id: entityId,
      mutation_type: mutationType,
      pre_state: {
        date_iso: dateIso,
        slot_key: slotKey,
        prior_existed: prior != null,
        prior_tier: prior?.tier ?? null,
        prior_source: prior?.source ?? null,
      },
      applied: true,
    },
  };
}

async function applyProjectMutation(
  uid: string,
  id: string,
  mutationType: BatchMutationType,
): Promise<ApplyOutcome> {
  const snap = await getDoc(projectDoc(uid, id));
  if (!snap.exists()) return { applied: false, reason: 'project not found' };
  const data = snap.data() as DocumentData;
  const now = Date.now();

  if (mutationType !== 'ARCHIVE' && mutationType !== 'DELETE') {
    return { applied: false, reason: `unsupported project mutation: ${mutationType}` };
  }
  await updateDoc(projectDoc(uid, id), {
    status: 'archived',
    archivedAt: now,
    updatedAt: now,
  });
  return {
    applied: true,
    entry: {
      entity_type: 'PROJECT',
      entity_id: id,
      mutation_type: mutationType,
      pre_state: {
        status: data.status ?? 'active',
        archivedAt: data.archivedAt ?? null,
      },
      applied: true,
    },
  };
}

// ── Undo ────────────────────────────────────────────────────────

export async function undoEntry(
  uid: string,
  entry: BatchUndoLogEntry,
): Promise<boolean> {
  if (!entry.applied) return false;
  try {
    switch (entry.entity_type) {
      case 'TASK':
        return await undoTaskEntry(uid, entry);
      case 'HABIT':
        return await undoHabitEntry(uid, entry);
      case 'PROJECT':
        return await undoProjectEntry(uid, entry);
      case 'MEDICATION':
        return await undoMedicationEntry(uid, entry);
    }
  } catch {
    return false;
  }
}

async function undoTaskEntry(
  uid: string,
  entry: BatchUndoLogEntry,
): Promise<boolean> {
  const now = Date.now();
  const pre = entry.pre_state;
  const ref = taskDoc(uid, entry.entity_id);
  switch (entry.mutation_type) {
    case 'RESCHEDULE':
      await updateDoc(ref, {
        dueDate: pre.dueDate ?? null,
        scheduledStartTime: pre.scheduledStartTime ?? null,
        updatedAt: now,
      });
      return true;
    case 'DELETE':
      await updateDoc(ref, { archivedAt: pre.archivedAt ?? null, updatedAt: now });
      return true;
    case 'COMPLETE':
      await updateDoc(ref, {
        isCompleted: pre.isCompleted ?? false,
        completedAt: pre.completedAt ?? null,
        webStatus: pre.webStatus ?? 'todo',
        updatedAt: now,
      });
      return true;
    case 'PRIORITY_CHANGE':
      await updateDoc(ref, { priority: pre.priority ?? 0, updatedAt: now });
      return true;
    case 'PROJECT_MOVE':
      await updateDoc(ref, { projectId: pre.projectId ?? null, updatedAt: now });
      return true;
    case 'TAG_CHANGE': {
      const priorIds = Array.isArray(pre.tagIds)
        ? (pre.tagIds as unknown[]).filter(
            (x): x is string => typeof x === 'string',
          )
        : [];
      await setTagsForTask(uid, entry.entity_id, priorIds);
      return true;
    }
    default:
      return false;
  }
}

async function undoHabitEntry(
  uid: string,
  entry: BatchUndoLogEntry,
): Promise<boolean> {
  const now = Date.now();
  const pre = entry.pre_state;
  switch (entry.mutation_type) {
    case 'COMPLETE': {
      const completionId = pre.completion_doc_id;
      if (typeof completionId !== 'string') return false;
      await deleteDoc(
        doc(firestore, 'users', uid, 'habit_completions', completionId),
      );
      return true;
    }
    case 'SKIP': {
      const deleted = (pre.deleted_completions as Array<{
        id: string;
        data: DocumentData;
      }>) ?? [];
      for (const d of deleted) {
        // Re-create with the original doc id via `setDoc(merge=true)`
        // so the round-trip is idempotent (a second SKIP→undo doesn't
        // produce a fresh sibling) and so undo respects the canonical-
        // row dedup contract.
        await setDoc(
          doc(firestore, 'users', uid, 'habit_completions', d.id),
          d.data,
          { merge: true },
        );
      }
      return true;
    }
    case 'ARCHIVE':
    case 'DELETE': {
      await updateDoc(habitDoc(uid, entry.entity_id), {
        isArchived: pre.isArchived ?? false,
        updatedAt: now,
      });
      return true;
    }
    default:
      return false;
  }
}

async function undoProjectEntry(
  uid: string,
  entry: BatchUndoLogEntry,
): Promise<boolean> {
  const now = Date.now();
  const pre = entry.pre_state;
  await updateDoc(projectDoc(uid, entry.entity_id), {
    status: pre.status ?? 'active',
    archivedAt: pre.archivedAt ?? null,
    updatedAt: now,
  });
  return true;
}

async function undoMedicationEntry(
  uid: string,
  entry: BatchUndoLogEntry,
): Promise<boolean> {
  const pre = entry.pre_state;
  const dateIso = typeof pre.date_iso === 'string' ? pre.date_iso : null;
  const slotKey = typeof pre.slot_key === 'string' ? pre.slot_key : null;
  if (dateIso == null || slotKey == null) return false;
  const priorExisted = pre.prior_existed === true;
  if (priorExisted) {
    const tier = pre.prior_tier as MedicationTier | null;
    const source = pre.prior_source as 'auto' | 'user_set' | null;
    if (tier == null) return false;
    await medicationSlots.setTierState(uid, dateIso, slotKey, tier, source ?? 'auto');
  } else {
    await medicationSlots.clearTierState(uid, dateIso, slotKey);
  }
  return true;
}
