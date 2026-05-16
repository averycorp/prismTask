import {
  collection,
  doc,
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
import type { Tag } from '@/types/tag';
import { timestampToIso } from './converters';
import { safeMergeDoc } from '@/lib/firestore/safeMergeDoc';

// ── Collection reference ──────────────────────────────────────

function tagsCol(uid: string) {
  return collection(firestore, 'users', uid, 'tags');
}

function tagDoc(uid: string, tagId: string) {
  return doc(firestore, 'users', uid, 'tags', tagId);
}

// ── Firestore doc → Web Tag ──────────────────────────────────

function docToTag(docId: string, data: DocumentData, uid: string): Tag {
  return {
    id: docId,
    user_id: uid,
    name: data.name ?? '',
    color: data.color ?? '#6B7280',
    created_at: timestampToIso(data.createdAt) ?? new Date().toISOString(),
  };
}

// ── CRUD operations ──────────────────────────────────────────

export async function getTags(uid: string): Promise<Tag[]> {
  const q = query(tagsCol(uid), orderBy('createdAt', 'desc'));
  const snap = await getDocs(q);
  return snap.docs.map((d) => docToTag(d.id, d.data(), uid));
}

export async function createTag(
  uid: string,
  data: { name: string; color?: string },
): Promise<Tag> {
  const now = Date.now();
  const firestoreData = {
    name: data.name,
    color: data.color ?? '#6B7280',
    createdAt: now,
  };
  const ref = await addDoc(tagsCol(uid), firestoreData);
  return docToTag(ref.id, firestoreData, uid);
}

export async function updateTag(
  uid: string,
  tagId: string,
  data: { name?: string; color?: string },
): Promise<Tag> {
  const updates: Record<string, unknown> = {};
  if (data.name !== undefined) updates.name = data.name;
  if (data.color !== undefined) updates.color = data.color;
  // Tags don't carry an `updatedAt` field on Android — the schema is
  // `name`, `color`, `createdAt` only. We route through `safeMergeDoc`
  // with `expectedUpdatedAt = null` (first-create-wins shape) so the
  // helper stamps a server-side `updatedAt` for future cross-device
  // LWW visibility without breaking Android's additive pull (unknown
  // keys are ignored).
  await safeMergeDoc(tagDoc(uid, tagId), updates, null);
  // Return the updated tag by re-reading (tags are small, this is fine)
  const snap = await getDocs(query(tagsCol(uid)));
  const tagSnap = snap.docs.find((d) => d.id === tagId);
  if (!tagSnap) throw new Error('Tag not found after update');
  return docToTag(tagSnap.id, tagSnap.data(), uid);
}

export async function deleteTag(uid: string, tagId: string): Promise<void> {
  await deleteDoc(tagDoc(uid, tagId));
}

// ── Real-time listener ───────────────────────────────────────

export function subscribeToTags(
  uid: string,
  callback: (tags: Tag[]) => void,
): Unsubscribe {
  const q = query(tagsCol(uid), orderBy('createdAt', 'desc'));
  return onSnapshot(q, (snap) => {
    const tags = snap.docs.map((d) => docToTag(d.id, d.data(), uid));
    callback(tags);
  });
}
