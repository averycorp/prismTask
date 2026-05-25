import apiClient from './client';
import type {
  ProductivityScoreResponse,
  ProductivityScoreParams,
  TimeTrackingResponse,
  TimeTrackingParams,
  HabitCorrelationResponse,
} from '@/types/analytics';

/**
 * Backend analytics endpoints. The existing `dashboardApi.getAnalyticsSummary`
 * covers `/analytics/summary` — this module adds the three additional
 * endpoints the dashboard needs.
 *
 * Not wired here: `/analytics/project-progress`. That endpoint takes an
 * integer Postgres project_id, but the web client stores projects in
 * Firestore with string doc IDs. Wiring it needs either a backend change
 * (accept Firestore IDs) or a resolver mapping — out of scope for the
 * initial analytics slice.
 */
interface ReqOpts {
  suppressErrorToast?: boolean;
}

export const analyticsApi = {
  productivityScore(
    params: ProductivityScoreParams = {},
    opts: ReqOpts = {},
  ): Promise<ProductivityScoreResponse> {
    return apiClient
      .get('/analytics/productivity-score', {
        params,
        suppressErrorToast: opts.suppressErrorToast,
      })
      .then((r) => r.data);
  },

  timeTracking(
    params: TimeTrackingParams = {},
    opts: ReqOpts = {},
  ): Promise<TimeTrackingResponse> {
    return apiClient
      .get('/analytics/time-tracking', {
        params,
        suppressErrorToast: opts.suppressErrorToast,
      })
      .then((r) => r.data);
  },

  habitCorrelations(opts: ReqOpts = {}): Promise<HabitCorrelationResponse> {
    return apiClient
      .get('/analytics/habit-correlations', {
        suppressErrorToast: opts.suppressErrorToast,
      })
      .then((r) => r.data);
  },
};
