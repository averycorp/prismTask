import {
  collection,
  onSnapshot,
  orderBy,
  query,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Real-time mirror of `users/{uid}/milestones` — project milestones that
 * Android writes via `SyncMapper.milestoneToMap`. CASCADE-FK to projects
 * on the Room side; on Firestore the linkage is via the `projectCloudId`
 * field on each doc.
 *
 * Web's project surfaces today don't render milestones, so this listener
 * exists primarily to keep the local cache fresh for upcoming UI work
 * (project detail screen milestone list) and so cross-device milestone
 * edits propagate without a manual refresh.
 *
 * Milestones with a missing or empty `projectCloudId` are emitted with
 * an empty `project_id` — they're orphaned rows that the UI can either
 * surface as un-parented or filter out at consumer time.
 */

export interface Milestone {
  /** Firestore doc id (== Android `cloud_id`). */
  id: string;
  /** Cloud id of the parent project. Empty when missing on the doc. */
  project_id: string;
  title: string;
  is_completed: boolean;
  completed_at: number | null;
  order_index: number;
  created_at: number;
  updated_at: number;
}

function milestonesCol(uid: string) {
  return collection(firestore, 'users', uid, 'milestones');
}

function docToMilestone(docId: string, data: DocumentData): Milestone {
  return {
    id: docId,
    project_id:
      typeof data.projectCloudId === 'string' ? data.projectCloudId : '',
    title: typeof data.title === 'string' ? data.title : '',
    is_completed: data.isCompleted === true,
    completed_at: typeof data.completedAt === 'number' ? data.completedAt : null,
    order_index: typeof data.orderIndex === 'number' ? data.orderIndex : 0,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : 0,
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

export function subscribeToMilestones(
  uid: string,
  callback: (milestones: Milestone[]) => void,
): Unsubscribe {
  const q = query(milestonesCol(uid), orderBy('orderIndex', 'asc'));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToMilestone(d.id, d.data())));
  });
}
