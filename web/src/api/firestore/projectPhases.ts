import {
  collection,
  doc,
  getDoc,
  getDocs,
  addDoc,
  deleteDoc,
  query,
  where,
  orderBy,
  onSnapshot,
  type Unsubscribe,
  type DocumentData,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import type {
  ProjectPhase,
  ProjectPhaseCreate,
  ProjectPhaseUpdate,
} from '@/types/projectPhase';
import { safeMergeDoc } from '@/lib/firestore/safeMergeDoc';

// ── Collection reference ──────────────────────────────────────

function phasesCol(uid: string) {
  return collection(firestore, 'users', uid, 'project_phases');
}

function phaseDoc(uid: string, phaseId: string) {
  return doc(firestore, 'users', uid, 'project_phases', phaseId);
}

// ── Firestore doc → Web ProjectPhase ──────────────────────────

function docToPhase(docId: string, data: DocumentData, projectId: string): ProjectPhase {
  return {
    id: docId,
    project_id: projectId,
    title: typeof data.title === 'string' ? data.title : '',
    description: typeof data.description === 'string' ? data.description : null,
    color_key: typeof data.colorKey === 'string' ? data.colorKey : null,
    start_date: typeof data.startDate === 'number' ? data.startDate : null,
    end_date: typeof data.endDate === 'number' ? data.endDate : null,
    version_anchor: typeof data.versionAnchor === 'string' ? data.versionAnchor : null,
    version_note: typeof data.versionNote === 'string' ? data.versionNote : null,
    order_index: typeof data.orderIndex === 'number' ? data.orderIndex : 0,
    completed_at: typeof data.completedAt === 'number' ? data.completedAt : null,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : Date.now(),
  };
}

// ── Web ProjectPhase → Firestore doc ──────────────────────────

function phaseCreateToDoc(
  projectId: string,
  data: ProjectPhaseCreate,
): Record<string, unknown> {
  const now = Date.now();
  // `projectCloudId` is the discriminator Android pulls on (see
  // SyncService.kt:2133). Web Project.id IS the Firestore doc id, so
  // they're the same value.
  return {
    projectCloudId: projectId,
    title: data.title,
    description: data.description ?? null,
    colorKey: data.color_key ?? null,
    startDate: data.start_date ?? null,
    endDate: data.end_date ?? null,
    versionAnchor: data.version_anchor ?? null,
    versionNote: data.version_note ?? null,
    orderIndex: data.order_index ?? 0,
    completedAt: null,
    createdAt: now,
    updatedAt: now,
  };
}

function phaseUpdateToDoc(data: ProjectPhaseUpdate): Record<string, unknown> {
  // Merge-mode write: include only fields the caller passed. Anything
  // not present here Firestore leaves untouched, protecting Android-only
  // state (e.g. cloudId metadata) from being clobbered.
  // `updatedAt` is stamped by safeMergeCurrentDoc via serverTimestamp().
  const result: Record<string, unknown> = {};
  if (data.title !== undefined) result.title = data.title;
  if (data.description !== undefined) result.description = data.description;
  if (data.color_key !== undefined) result.colorKey = data.color_key;
  if (data.start_date !== undefined) result.startDate = data.start_date;
  if (data.end_date !== undefined) result.endDate = data.end_date;
  if (data.version_anchor !== undefined) result.versionAnchor = data.version_anchor;
  if (data.version_note !== undefined) result.versionNote = data.version_note;
  if (data.order_index !== undefined) result.orderIndex = data.order_index;
  if (data.completed_at !== undefined) result.completedAt = data.completed_at;
  return result;
}

// ── CRUD operations ──────────────────────────────────────────

export async function getPhasesByProject(
  uid: string,
  projectId: string,
): Promise<ProjectPhase[]> {
  const q = query(
    phasesCol(uid),
    where('projectCloudId', '==', projectId),
    orderBy('orderIndex', 'asc'),
  );
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToPhase(d.id, d.data(), projectId));
}

export async function getPhase(
  uid: string,
  phaseId: string,
): Promise<ProjectPhase | null> {
  const snap = await getDoc(phaseDoc(uid, phaseId));
  if (!snap.exists()) return null;
  const data = snap.data();
  const projectId =
    typeof data.projectCloudId === 'string' ? data.projectCloudId : '';
  return docToPhase(snap.id, data, projectId);
}

export async function createPhase(
  uid: string,
  projectId: string,
  data: ProjectPhaseCreate,
): Promise<ProjectPhase> {
  const payload = phaseCreateToDoc(projectId, data);
  const ref = await addDoc(phasesCol(uid), payload);
  return docToPhase(ref.id, payload, projectId);
}

export async function updatePhase(
  uid: string,
  phaseId: string,
  data: ProjectPhaseUpdate,
): Promise<ProjectPhase> {
  const payload = phaseUpdateToDoc(data);
  // Server-stamped merge via `safeMergeDoc`'s first-create path
  // (`expectedUpdatedAt = null`). Phase updates here are fire-and-
  // forget — no caller cached the `expectedUpdatedAt` to feed a
  // strict precondition. `serverTimestamp()` keeps Android's LWW
  // comparison honest under clock skew. Sync sweep C.
  await safeMergeDoc(phaseDoc(uid, phaseId), payload, null);
  const snap = await getDoc(phaseDoc(uid, phaseId));
  const docData = snap.data() ?? {};
  const projectId =
    typeof docData.projectCloudId === 'string' ? docData.projectCloudId : '';
  return docToPhase(snap.id, docData, projectId);
}

export async function deletePhase(uid: string, phaseId: string): Promise<void> {
  await deleteDoc(phaseDoc(uid, phaseId));
}

// ── Real-time listener ───────────────────────────────────────

export function subscribeToPhases(
  uid: string,
  projectId: string,
  callback: (phases: ProjectPhase[]) => void,
): Unsubscribe {
  const q = query(
    phasesCol(uid),
    where('projectCloudId', '==', projectId),
    orderBy('orderIndex', 'asc'),
  );
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToPhase(d.id, d.data(), projectId)));
  });
}

/**
 * User-wide listener over every phase in `users/{uid}/project_phases`.
 *
 * Mirrors the at-sign-in sync shape used by `subscribeToTasks` /
 * `subscribeToProjects` so `useFirestoreSync` can mount it without a
 * `projectId`. Each emitted phase carries its own `project_id` decoded
 * from the `projectCloudId` field on the Firestore doc, so consumers
 * downstream can re-group by project. Skips docs missing
 * `projectCloudId` rather than emitting blank-id rows.
 */
export function subscribeToAllPhases(
  uid: string,
  callback: (phases: ProjectPhase[]) => void,
): Unsubscribe {
  const q = query(phasesCol(uid), orderBy('orderIndex', 'asc'));
  return onSnapshot(q, (snap) => {
    callback(
      snap.docs
        .map((d) => {
          const data = d.data();
          const projectId =
            typeof data.projectCloudId === 'string' ? data.projectCloudId : '';
          if (!projectId) return null;
          return docToPhase(d.id, data, projectId);
        })
        .filter((p): p is ProjectPhase => p !== null),
    );
  });
}
