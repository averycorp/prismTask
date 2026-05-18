import {
  collection,
  doc,
  getDoc,
  getDocs,
  addDoc,
  deleteDoc,
  query,
  orderBy,
  onSnapshot,
  type Unsubscribe,
  type DocumentData,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { lwwUpdate } from './lww';
import type { Project, ProjectDetail, ProjectStatus } from '@/types/project';
import { timestampToIso } from './converters';

// ── Collection reference ──────────────────────────────────────

function projectsCol(uid: string) {
  return collection(firestore, 'users', uid, 'projects');
}

function projectDoc(uid: string, projectId: string) {
  return doc(firestore, 'users', uid, 'projects', projectId);
}

// Android writes `status` using the Kotlin enum name (`ACTIVE`, `ARCHIVED`,
// `COMPLETED`, `ON_HOLD`). The web type is lowercased, so normalize at the
// read boundary so every web filter / picker can compare against the web
// `ProjectStatus` union without re-checking casing.
const WEB_STATUSES: ReadonlySet<ProjectStatus> = new Set([
  'active',
  'completed',
  'on_hold',
  'archived',
]);

function normalizeStatus(raw: unknown): ProjectStatus {
  if (typeof raw !== 'string') return 'active';
  const lower = raw.toLowerCase();
  return (WEB_STATUSES.has(lower as ProjectStatus) ? lower : 'active') as ProjectStatus;
}

// ── Firestore doc → Web Project ──────────────────────────────

function docToProject(docId: string, data: DocumentData, uid: string): Project {
  return {
    id: docId,
    goal_id: '',
    user_id: uid,
    title: data.name ?? '',
    description: data.description ?? null,
    status: normalizeStatus(data.status),
    due_date: null,
    color: data.color ?? '#4A90D9',
    icon: data.icon ?? '📁',
    sort_order: data.sortOrder ?? 0,
    created_at: timestampToIso(data.createdAt) ?? new Date().toISOString(),
    updated_at: timestampToIso(data.updatedAt) ?? new Date().toISOString(),
  };
}

// ── Web Project → Firestore doc ──────────────────────────────

function projectCreateToDoc(data: { title: string; description?: string; color?: string; icon?: string; sort_order?: number }): Record<string, unknown> {
  const now = Date.now();
  return {
    name: data.title,
    description: data.description ?? null,
    color: data.color ?? '#4A90D9',
    icon: data.icon ?? '📁',
    status: 'active',
    sortOrder: data.sort_order ?? 0,
    createdAt: now,
    updatedAt: now,
  };
}

function projectUpdateToDoc(
  data: Record<string, unknown>,
  now: number = Date.now(),
): Record<string, unknown> {
  // `now` is threaded through so the LWW guard's comparison and the
  // doc's `updatedAt` use the same wall-clock millis. See `lww.ts`.
  const result: Record<string, unknown> = { updatedAt: now };
  if (data.title !== undefined) result.name = data.title;
  if (data.description !== undefined) result.description = data.description;
  if (data.color !== undefined) result.color = data.color;
  if (data.icon !== undefined) result.icon = data.icon;
  if (data.status !== undefined) result.status = data.status;
  if (data.sort_order !== undefined) result.sortOrder = data.sort_order;
  return result;
}

// ── CRUD operations ──────────────────────────────────────────

export async function getProjects(uid: string): Promise<Project[]> {
  const q = query(projectsCol(uid), orderBy('createdAt', 'desc'));
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToProject(d.id, d.data(), uid));
}

export async function getProject(uid: string, projectId: string): Promise<ProjectDetail | null> {
  const snap = await getDoc(projectDoc(uid, projectId));
  if (!snap.exists()) return null;
  return { ...docToProject(snap.id, snap.data()!, uid), tasks: [] };
}

export async function createProject(
  uid: string,
  data: { title: string; description?: string; color?: string; icon?: string; sort_order?: number },
): Promise<Project> {
  const firestoreData = projectCreateToDoc(data);
  const ref = await addDoc(projectsCol(uid), firestoreData);
  return docToProject(ref.id, firestoreData, uid);
}

export async function updateProject(
  uid: string,
  projectId: string,
  data: Record<string, unknown>,
): Promise<Project> {
  // LWW guard — Android-side project lifecycle edits (status flip,
  // end_date / theme_color_key writes the web doesn't own) shouldn't
  // be clobbered by a web rename or sort-order edit. Parity audit A.2.
  const now = Date.now();
  const firestoreData = projectUpdateToDoc(data, now);
  await lwwUpdate(projectDoc(uid, projectId), firestoreData as Parameters<typeof lwwUpdate>[1]);
  const snap = await getDoc(projectDoc(uid, projectId));
  return docToProject(snap.id, snap.data()!, uid);
}

export async function deleteProject(uid: string, projectId: string): Promise<void> {
  await deleteDoc(projectDoc(uid, projectId));
}

// ── Real-time listener ───────────────────────────────────────

export function subscribeToProjects(
  uid: string,
  callback: (projects: Project[]) => void,
): Unsubscribe {
  const q = query(projectsCol(uid), orderBy('createdAt', 'desc'));
  return onSnapshot(q, (snap) => {
    const projects = snap.docs.map((d) => docToProject(d.id, d.data(), uid));
    callback(projects);
  });
}
