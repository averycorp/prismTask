import {
  collection,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';

/**
 * Read-only Firestore listener for `users/{uid}/project_templates`.
 *
 * Android writes via `SyncService.uploadRoomConfigFamily` →
 * `SyncMapper.projectTemplateToMap` (`ProjectTemplateEntity`). Each row
 * is a scaffold for spawning a project-with-tasks bundle from a fixed
 * blueprint. `task_templates_json` is a Gson array of inline task
 * definitions; consumers parse it lazily.
 *
 * **Naming note:** orthogonal to the `task_templates` collection
 * already wired in `useFirestoreSync` and to the v1.4.0 Projects
 * feature — see the doc on `ProjectTemplateEntity` for the history.
 *
 * Parity Sync Sweep B unit (2 of 23). Write + apply path deferred.
 */
export interface ProjectTemplate {
  /** Firestore doc id — authoritative identity on web. */
  cloud_id: string;
  /** Android Room rowid echo. */
  local_id: number | null;
  name: string;
  description: string | null;
  color: string | null;
  icon_emoji: string | null;
  category: string | null;
  /** Gson array of inline task definitions. Parsed lazily by callers. */
  task_templates_json: string;
  is_built_in: boolean;
  usage_count: number;
  last_used_at: number | null;
  created_at: number;
  updated_at: number;
}

function templatesCol(uid: string) {
  return collection(firestore, 'users', uid, 'project_templates');
}

function docToTemplate(id: string, data: DocumentData): ProjectTemplate {
  return {
    cloud_id: id,
    local_id: typeof data.localId === 'number' ? data.localId : null,
    name: typeof data.name === 'string' ? data.name : '',
    description: typeof data.description === 'string' ? data.description : null,
    color: typeof data.color === 'string' ? data.color : null,
    icon_emoji: typeof data.iconEmoji === 'string' ? data.iconEmoji : null,
    category: typeof data.category === 'string' ? data.category : null,
    task_templates_json:
      typeof data.taskTemplatesJson === 'string' ? data.taskTemplatesJson : '[]',
    is_built_in: data.isBuiltIn === true,
    usage_count: typeof data.usageCount === 'number' ? data.usageCount : 0,
    last_used_at: typeof data.lastUsedAt === 'number' ? data.lastUsedAt : null,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

export async function getProjectTemplates(
  uid: string,
): Promise<ProjectTemplate[]> {
  const snap = await getDocs(
    query(templatesCol(uid), orderBy('createdAt', 'desc')),
  );
  return snap.docs.map((d) => docToTemplate(d.id, d.data()));
}

/**
 * Subscribe to the user's project-template collection. Wired from
 * `useFirestoreSync`. Doc id collision with the existing
 * `task_templates` listener is avoided by routing through a separate
 * Firestore collection (`project_templates`) and a separate Zustand
 * store. Read-only.
 */
export function subscribeToProjectTemplates(
  uid: string,
  callback: (templates: ProjectTemplate[]) => void,
): Unsubscribe {
  const q = query(templatesCol(uid), orderBy('createdAt', 'desc'));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToTemplate(d.id, d.data())));
  });
}
