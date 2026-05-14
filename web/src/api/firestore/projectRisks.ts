import {
  collection,
  doc,
  getDoc,
  getDocs,
  addDoc,
  updateDoc,
  deleteDoc,
  query,
  where,
  orderBy,
  onSnapshot,
  type Unsubscribe,
  type DocumentData,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import {
  parseRiskLevel,
  type ProjectRisk,
  type ProjectRiskCreate,
  type ProjectRiskUpdate,
} from '@/types/projectRisk';

// ── Collection reference ──────────────────────────────────────

function risksCol(uid: string) {
  return collection(firestore, 'users', uid, 'project_risks');
}

function riskDoc(uid: string, riskId: string) {
  return doc(firestore, 'users', uid, 'project_risks', riskId);
}

// ── Firestore doc → Web ProjectRisk ───────────────────────────

function docToRisk(docId: string, data: DocumentData, projectId: string): ProjectRisk {
  return {
    id: docId,
    project_id: projectId,
    title: typeof data.title === 'string' ? data.title : '',
    level: parseRiskLevel(data.level),
    mitigation: typeof data.mitigation === 'string' ? data.mitigation : null,
    resolved_at: typeof data.resolvedAt === 'number' ? data.resolvedAt : null,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : Date.now(),
  };
}

// ── Web ProjectRisk → Firestore doc ───────────────────────────

function riskCreateToDoc(
  projectId: string,
  data: ProjectRiskCreate,
): Record<string, unknown> {
  const now = Date.now();
  return {
    projectCloudId: projectId,
    title: data.title,
    level: data.level ?? 'MEDIUM',
    mitigation: data.mitigation ?? null,
    resolvedAt: null,
    createdAt: now,
    updatedAt: now,
  };
}

function riskUpdateToDoc(data: ProjectRiskUpdate): Record<string, unknown> {
  const result: Record<string, unknown> = { updatedAt: Date.now() };
  if (data.title !== undefined) result.title = data.title;
  if (data.level !== undefined) result.level = data.level;
  if (data.mitigation !== undefined) result.mitigation = data.mitigation;
  if (data.resolved_at !== undefined) result.resolvedAt = data.resolved_at;
  return result;
}

// ── CRUD operations ──────────────────────────────────────────

export async function getRisksByProject(
  uid: string,
  projectId: string,
): Promise<ProjectRisk[]> {
  const q = query(
    risksCol(uid),
    where('projectCloudId', '==', projectId),
    orderBy('createdAt', 'asc'),
  );
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToRisk(d.id, d.data(), projectId));
}

export async function getRisk(uid: string, riskId: string): Promise<ProjectRisk | null> {
  const snap = await getDoc(riskDoc(uid, riskId));
  if (!snap.exists()) return null;
  const data = snap.data();
  const projectId =
    typeof data.projectCloudId === 'string' ? data.projectCloudId : '';
  return docToRisk(snap.id, data, projectId);
}

export async function createRisk(
  uid: string,
  projectId: string,
  data: ProjectRiskCreate,
): Promise<ProjectRisk> {
  const payload = riskCreateToDoc(projectId, data);
  const ref = await addDoc(risksCol(uid), payload);
  return docToRisk(ref.id, payload, projectId);
}

export async function updateRisk(
  uid: string,
  riskId: string,
  data: ProjectRiskUpdate,
): Promise<ProjectRisk> {
  const payload = riskUpdateToDoc(data);
  await updateDoc(riskDoc(uid, riskId), payload);
  const snap = await getDoc(riskDoc(uid, riskId));
  const docData = snap.data() ?? {};
  const projectId =
    typeof docData.projectCloudId === 'string' ? docData.projectCloudId : '';
  return docToRisk(snap.id, docData, projectId);
}

export async function deleteRisk(uid: string, riskId: string): Promise<void> {
  await deleteDoc(riskDoc(uid, riskId));
}

// ── Real-time listener ───────────────────────────────────────

export function subscribeToRisks(
  uid: string,
  projectId: string,
  callback: (risks: ProjectRisk[]) => void,
): Unsubscribe {
  const q = query(
    risksCol(uid),
    where('projectCloudId', '==', projectId),
    orderBy('createdAt', 'asc'),
  );
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToRisk(d.id, d.data(), projectId)));
  });
}

/**
 * User-wide listener over every risk in `users/{uid}/project_risks`.
 *
 * Mirrors the at-sign-in sync shape used by `subscribeToTasks` /
 * `subscribeToProjects` so `useFirestoreSync` can mount it without a
 * `projectId`. Each emitted risk carries its own `project_id` decoded
 * from the `projectCloudId` field on the Firestore doc. Skips docs
 * missing `projectCloudId` rather than emitting blank-id rows.
 */
export function subscribeToAllRisks(
  uid: string,
  callback: (risks: ProjectRisk[]) => void,
): Unsubscribe {
  const q = query(risksCol(uid), orderBy('createdAt', 'asc'));
  return onSnapshot(q, (snap) => {
    callback(
      snap.docs
        .map((d) => {
          const data = d.data();
          const projectId =
            typeof data.projectCloudId === 'string' ? data.projectCloudId : '';
          if (!projectId) return null;
          return docToRisk(d.id, data, projectId);
        })
        .filter((r): r is ProjectRisk => r !== null),
    );
  });
}
