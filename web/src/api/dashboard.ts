import apiClient from './client';
import type { DashboardSummary, AnalyticsSummary } from '@/types/api';

export const dashboardApi = {
  getSummary(): Promise<DashboardSummary> {
    return apiClient.get('/dashboard/summary').then((r) => r.data);
  },

  getAnalyticsSummary(
    opts: { suppressErrorToast?: boolean } = {},
  ): Promise<AnalyticsSummary> {
    return apiClient
      .get('/analytics/summary', { suppressErrorToast: opts.suppressErrorToast })
      .then((r) => r.data);
  },
};
