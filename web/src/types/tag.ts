export interface Tag {
  id: string;
  user_id: string;
  name: string;
  color: string | null;
  /**
   * Web-only persistence field powering the drag-to-reorder UI on
   * `features/tags/TagManagementScreen.tsx`. Android stores tags ordered
   * by `created_at DESC` (`TagEntity.kt`); the web layer adds an
   * optional `sortOrder` column to Firestore that, when present, takes
   * precedence over creation order. Tags missing the column fall back
   * to legacy created-at order so cross-device sync stays additive.
   */
  sort_order: number;
  /**
   * Web-only archive flag (parity gap on Android — listed in unit 23
   * spec). Archived tags stay queryable but are hidden from the active
   * tag list and excluded from search results.
   */
  archived: boolean;
  created_at: string;
}

export interface TagCreate {
  name: string;
  color?: string;
  sort_order?: number;
}

export interface TagUpdate {
  name?: string;
  color?: string;
  sort_order?: number;
  archived?: boolean;
}
