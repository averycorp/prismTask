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
import {
  decodeExternalAnchor,
  encodeExternalAnchor,
  type ExternalAnchor,
  type ExternalAnchorCreate,
  type ExternalAnchorRecord,
  type ExternalAnchorUpdate,
} from '@/types/externalAnchor';
import { safeMergeDoc } from '@/lib/firestore/safeMergeDoc';

// ── Collection reference ──────────────────────────────────────

function anchorsCol(uid: string) {
  return collection(firestore, 'users', uid, 'external_anchors');
}

function anchorDoc(uid: string, anchorId: string) {
  return doc(firestore, 'users', uid, 'external_anchors', anchorId);
}

// ── Firestore doc → Web ExternalAnchorRecord ──────────────────

/**
 * Returns `null` when the JSON payload fails to decode — mirrors the
 * Android `ExternalAnchorJsonAdapter` malformed-row-drops behavior so a
 * corrupted snapshot doesn't break the entire roadmap render. Callers
 * should filter `null` before display.
 */
function docToAnchor(
  docId: string,
  data: DocumentData,
  projectId: string,
): ExternalAnchorRecord | null {
  const anchorJson = typeof data.anchorJson === 'string' ? data.anchorJson : null;
  const anchor = decodeExternalAnchor(anchorJson);
  if (!anchor) return null;
  return {
    id: docId,
    project_id: projectId,
    phase_id: typeof data.phaseCloudId === 'string' ? data.phaseCloudId : null,
    label: typeof data.label === 'string' ? data.label : '',
    anchor,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : Date.now(),
  };
}

// ── Web ExternalAnchorRecord → Firestore doc ──────────────────

function anchorCreateToDoc(
  projectId: string,
  data: ExternalAnchorCreate,
): Record<string, unknown> {
  const now = Date.now();
  return {
    projectCloudId: projectId,
    phaseCloudId: data.phase_id ?? null,
    label: data.label,
    anchorJson: encodeExternalAnchor(data.anchor),
    createdAt: now,
    updatedAt: now,
  };
}

function anchorUpdateToDoc(data: ExternalAnchorUpdate): Record<string, unknown> {
  // `updatedAt` is stamped by `safeMergeCurrentDoc` via
  // `serverTimestamp()` — don't pre-populate it here, otherwise the
  // helper's stamp clobbers a literal `Date.now()` and the timestamps
  // diverge under clock skew.
  const result: Record<string, unknown> = {};
  if (data.label !== undefined) result.label = data.label;
  if (data.anchor !== undefined) result.anchorJson = encodeExternalAnchor(data.anchor);
  if (data.phase_id !== undefined) result.phaseCloudId = data.phase_id;
  return result;
}

// ── CRUD operations ──────────────────────────────────────────

export async function getAnchorsByProject(
  uid: string,
  projectId: string,
): Promise<ExternalAnchorRecord[]> {
  const q = query(
    anchorsCol(uid),
    where('projectCloudId', '==', projectId),
    orderBy('createdAt', 'asc'),
  );
  const snap = await getDocs(q);
  return snap.docs
    .map((d) => docToAnchor(d.id, d.data(), projectId))
    .filter((a): a is ExternalAnchorRecord => a !== null);
}

export async function getAnchor(
  uid: string,
  anchorId: string,
): Promise<ExternalAnchorRecord | null> {
  const snap = await getDoc(anchorDoc(uid, anchorId));
  if (!snap.exists()) return null;
  const data = snap.data();
  const projectId =
    typeof data.projectCloudId === 'string' ? data.projectCloudId : '';
  return docToAnchor(snap.id, data, projectId);
}

export async function createAnchor(
  uid: string,
  projectId: string,
  data: ExternalAnchorCreate,
): Promise<ExternalAnchorRecord> {
  const payload = anchorCreateToDoc(projectId, data);
  const ref = await addDoc(anchorsCol(uid), payload);
  const decoded = docToAnchor(ref.id, payload, projectId);
  if (!decoded) {
    // Should never happen — we just encoded an in-memory anchor. Defensive
    // throw rather than returning a half-built record.
    throw new Error('Failed to round-trip newly-created external anchor');
  }
  return decoded;
}

export async function updateAnchor(
  uid: string,
  anchorId: string,
  data: ExternalAnchorUpdate,
): Promise<ExternalAnchorRecord | null> {
  const payload = anchorUpdateToDoc(data);
  // Server-stamped merge via `safeMergeDoc`'s first-create path
  // (`expectedUpdatedAt = null`). The caller doesn't keep an
  // `expectedUpdatedAt` for anchors (no detail-view that snapshots
  // it), so the strict precondition path doesn't apply — but routing
  // through the helper keeps the `updatedAt` stamp consistent
  // (`serverTimestamp()` rather than a clock-skewed local `Date.now()`)
  // so cross-device LWW on Android's side stays honest. Sync sweep C.
  await safeMergeDoc(anchorDoc(uid, anchorId), payload, null);
  const snap = await getDoc(anchorDoc(uid, anchorId));
  const docData = snap.data() ?? {};
  const projectId =
    typeof docData.projectCloudId === 'string' ? docData.projectCloudId : '';
  return docToAnchor(snap.id, docData, projectId);
}

export async function deleteAnchor(uid: string, anchorId: string): Promise<void> {
  await deleteDoc(anchorDoc(uid, anchorId));
}

// ── Real-time listener ───────────────────────────────────────

export function subscribeToAnchors(
  uid: string,
  projectId: string,
  callback: (anchors: ExternalAnchorRecord[]) => void,
): Unsubscribe {
  const q = query(
    anchorsCol(uid),
    where('projectCloudId', '==', projectId),
    orderBy('createdAt', 'asc'),
  );
  return onSnapshot(q, (snap) => {
    callback(
      snap.docs
        .map((d) => docToAnchor(d.id, d.data(), projectId))
        .filter((a): a is ExternalAnchorRecord => a !== null),
    );
  });
}

/**
 * User-wide listener over every anchor in `users/{uid}/external_anchors`.
 *
 * Mirrors the at-sign-in sync shape used by `subscribeToTasks` /
 * `subscribeToProjects` so `useFirestoreSync` can mount it without a
 * `projectId`. Each emitted anchor carries its own `project_id` decoded
 * from the `projectCloudId` field on the Firestore doc, and `docToAnchor`
 * already drops malformed JSON payloads — same hardening as
 * `subscribeToAnchors`. Skips docs missing `projectCloudId`.
 */
export function subscribeToAllAnchors(
  uid: string,
  callback: (anchors: ExternalAnchorRecord[]) => void,
): Unsubscribe {
  const q = query(anchorsCol(uid), orderBy('createdAt', 'asc'));
  return onSnapshot(q, (snap) => {
    callback(
      snap.docs
        .map((d) => {
          const data = d.data();
          const projectId =
            typeof data.projectCloudId === 'string' ? data.projectCloudId : '';
          if (!projectId) return null;
          return docToAnchor(d.id, data, projectId);
        })
        .filter((a): a is ExternalAnchorRecord => a !== null),
    );
  });
}

// Re-export for convenience
export type { ExternalAnchor };
