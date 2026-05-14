import apiClient from './client';
import type {
  LeisureActivity,
  LeisureActivityCreate,
  LeisureActivityUpdate,
  LeisureSession,
  LeisureSessionCreate,
  LeisureSettings,
  LeisureSettingsUpdate,
} from '@/types/leisure';
import { isCustomCategoryId } from '@/types/leisure';

/**
 * REST client for the Leisure Budget v2.0 backend routes mounted at
 * `/api/v1/leisure` (see `backend/app/routers/leisure.py`). Mirrors the
 * Android `LeisureSyncService` request shape exactly so cross-platform
 * users see the same activity pool + session history.
 *
 * Activities and sessions tagged with a *custom* category (the
 * `custom:<uuid8>` id namespace, local-only per
 * `LeisureBudgetPreferences.kt`) are filtered out at the network boundary
 * before any write. The backend's `LeisureCategoryT` CHECK constraint pins
 * synced rows to the four built-in buckets, so a custom-tagged write would
 * 422 on the server. The Android client follows the same rule by simply
 * never pushing rows whose `category` starts with `custom:`.
 */
function isSyncableCategory(category: string): boolean {
  return !isCustomCategoryId(category);
}

export const leisureApi = {
  // ── Activities ─────────────────────────────────────────────

  listActivities(enabledOnly = false): Promise<LeisureActivity[]> {
    return apiClient
      .get<LeisureActivity[]>('/leisure/activities', {
        params: enabledOnly ? { enabled_only: true } : undefined,
      })
      .then((r) => r.data);
  },

  /**
   * Push a new activity to the backend. Throws synchronously (before any
   * network round-trip) if the caller hands us a custom-category-tagged
   * row — those stay on-device.
   */
  createActivity(body: LeisureActivityCreate): Promise<LeisureActivity> {
    if (!isSyncableCategory(body.category)) {
      return Promise.reject(
        new Error(
          `Cannot sync activity with custom category "${body.category}" — ` +
            'custom categories are device-local. Store this activity in ' +
            'the local leisureStore only.',
        ),
      );
    }
    return apiClient
      .post<LeisureActivity>('/leisure/activities', body)
      .then((r) => r.data);
  },

  updateActivity(
    activityId: string,
    body: LeisureActivityUpdate,
  ): Promise<LeisureActivity> {
    if (body.category !== undefined && !isSyncableCategory(body.category)) {
      return Promise.reject(
        new Error(
          `Cannot reassign synced activity to custom category ` +
            `"${body.category}" — delete the activity remotely and re-add ` +
            'it locally if the user really wants a custom category.',
        ),
      );
    }
    return apiClient
      .patch<LeisureActivity>(`/leisure/activities/${activityId}`, body)
      .then((r) => r.data);
  },

  deleteActivity(activityId: string): Promise<void> {
    return apiClient
      .delete(`/leisure/activities/${activityId}`)
      .then(() => undefined);
  },

  // ── Sessions ───────────────────────────────────────────────

  listSessions(opts?: {
    since?: string;
    limit?: number;
  }): Promise<LeisureSession[]> {
    return apiClient
      .get<LeisureSession[]>('/leisure/sessions', {
        params: {
          ...(opts?.since ? { since: opts.since } : {}),
          ...(opts?.limit ? { limit: opts.limit } : {}),
        },
      })
      .then((r) => r.data);
  },

  /**
   * Push a session. Custom-category sessions stay on-device — same logic
   * as activities. The caller is expected to handle local-only writes via
   * the leisureStore.
   */
  createSession(body: LeisureSessionCreate): Promise<LeisureSession> {
    if (!isSyncableCategory(body.category)) {
      return Promise.reject(
        new Error(
          `Cannot sync session with custom category "${body.category}" — ` +
            'custom categories are device-local.',
        ),
      );
    }
    return apiClient
      .post<LeisureSession>('/leisure/sessions', body)
      .then((r) => r.data);
  },

  // ── Settings ───────────────────────────────────────────────

  getSettings(): Promise<LeisureSettings> {
    return apiClient
      .get<LeisureSettings>('/leisure/settings')
      .then((r) => r.data);
  },

  updateSettings(body: LeisureSettingsUpdate): Promise<LeisureSettings> {
    return apiClient
      .patch<LeisureSettings>('/leisure/settings', body)
      .then((r) => r.data);
  },
};

export default leisureApi;
