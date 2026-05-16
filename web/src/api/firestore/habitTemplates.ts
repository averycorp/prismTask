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
 * Read-only Firestore listener for `users/{uid}/habit_templates`.
 *
 * Android writes via `SyncService.uploadRoomConfigFamily` →
 * `SyncMapper.habitTemplateToMap` (`HabitTemplateEntity`). Templates
 * are reusable habit blueprints — the apply path spawns a Habit from
 * the blueprint and bumps `usage_count` / `last_used_at`.
 *
 * Parity Sync Sweep B unit (2 of 23). Write + apply path deferred to
 * the habit-templates UI unit.
 */
export interface HabitTemplate {
  /** Firestore doc id — authoritative identity on web. */
  cloud_id: string;
  /** Android Room rowid echo. */
  local_id: number | null;
  name: string;
  description: string | null;
  icon_emoji: string | null;
  color: string | null;
  category: string | null;
  /** `DAILY` | `WEEKLY` | … — see Android's `HabitFrequency` enum. */
  frequency: string;
  target_count: number;
  /** CSV of day-of-week ints (1=Mon..7=Sun). Empty = every day. */
  active_days_csv: string;
  is_built_in: boolean;
  usage_count: number;
  last_used_at: number | null;
  created_at: number;
  updated_at: number;
}

function templatesCol(uid: string) {
  return collection(firestore, 'users', uid, 'habit_templates');
}

function docToTemplate(id: string, data: DocumentData): HabitTemplate {
  return {
    cloud_id: id,
    local_id: typeof data.localId === 'number' ? data.localId : null,
    name: typeof data.name === 'string' ? data.name : '',
    description: typeof data.description === 'string' ? data.description : null,
    icon_emoji: typeof data.iconEmoji === 'string' ? data.iconEmoji : null,
    color: typeof data.color === 'string' ? data.color : null,
    category: typeof data.category === 'string' ? data.category : null,
    frequency: typeof data.frequency === 'string' ? data.frequency : 'DAILY',
    target_count: typeof data.targetCount === 'number' ? data.targetCount : 1,
    active_days_csv:
      typeof data.activeDaysCsv === 'string' ? data.activeDaysCsv : '',
    is_built_in: data.isBuiltIn === true,
    usage_count: typeof data.usageCount === 'number' ? data.usageCount : 0,
    last_used_at: typeof data.lastUsedAt === 'number' ? data.lastUsedAt : null,
    created_at: typeof data.createdAt === 'number' ? data.createdAt : Date.now(),
    updated_at: typeof data.updatedAt === 'number' ? data.updatedAt : 0,
  };
}

export async function getHabitTemplates(uid: string): Promise<HabitTemplate[]> {
  const snap = await getDocs(
    query(templatesCol(uid), orderBy('createdAt', 'desc')),
  );
  return snap.docs.map((d) => docToTemplate(d.id, d.data()));
}

/**
 * Subscribe to the user's habit-template collection. Wired from
 * `useFirestoreSync` so a template authored on Android shows up in any
 * web-side template picker (downstream UI). Read-only.
 */
export function subscribeToHabitTemplates(
  uid: string,
  callback: (templates: HabitTemplate[]) => void,
): Unsubscribe {
  const q = query(templatesCol(uid), orderBy('createdAt', 'desc'));
  return onSnapshot(q, (snap) => {
    callback(snap.docs.map((d) => docToTemplate(d.id, d.data())));
  });
}
