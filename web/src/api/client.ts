import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { toast } from 'sonner';

declare module 'axios' {
  // Per-request opt-out from the global error-toast interceptor.
  export interface AxiosRequestConfig {
    suppressErrorToast?: boolean;
  }
}

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ||
  'https://averytask-production.up.railway.app/api/v1';

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

/**
 * Path prefixes that egress user data to Anthropic via the backend.
 * Mirrors Android's `AiFeatureGateInterceptor.AI_PATH_PREFIXES` in
 * `app/src/main/java/com/averycorp/prismtask/data/remote/api/AiFeatureGateInterceptor.kt`.
 *
 * Keep this list in sync when adding a new Anthropic-touching backend route.
 */
export const AI_PATH_PREFIXES: readonly string[] = [
  '/ai/',
  '/tasks/parse',
  '/syllabus/parse',
];

export const HTTP_451_UNAVAILABLE_FOR_LEGAL_REASONS = 451;
export const HEADER_AI_FEATURES = 'X-PrismTask-AI-Features';
export const HEADER_VALUE_DISABLED = 'disabled';

const SYNTHETIC_BODY = {
  detail:
    'AI features are disabled in PrismTask Settings → AI Features. Re-enable to use this feature.',
};

/**
 * Provider for the master AI-features opt-out flag, read on every request to
 * an Anthropic-touching path. Defaults to "enabled" until the settings store
 * registers itself, so module-load-order during tests doesn't accidentally
 * fail-closed on requests issued before the store hydrates.
 *
 * The settings store calls `setAiFeaturesEnabledProvider(...)` once on
 * module init (zustand store creation runs at import time).
 */
let aiFeaturesEnabledProvider: () => boolean = () => true;

export function setAiFeaturesEnabledProvider(provider: () => boolean): void {
  aiFeaturesEnabledProvider = provider;
}

function isAiTouchingPath(url: string | undefined): boolean {
  if (!url) return false;
  // Match against the path component only — strip query string and any
  // accidental fully-qualified URL prefix.
  let path = url;
  try {
    if (/^https?:\/\//i.test(url)) {
      path = new URL(url).pathname;
    } else {
      // Drop query string from a relative URL.
      const q = url.indexOf('?');
      if (q >= 0) path = url.slice(0, q);
    }
  } catch {
    // If URL parsing fails, fall through with the raw url.
  }
  return AI_PATH_PREFIXES.some((prefix) => path.startsWith(prefix));
}

/**
 * Synthetic axios error mimicking a backend 451 response. Calling code that
 * already handles network/HTTP failures (forms, mutation toasts, etc.) sees
 * a normal AxiosError and reacts the same way it would for a real backend
 * rejection — see Android's `AiFeatureGateInterceptor` for the parity
 * implementation that returns the same shape from OkHttp.
 */
export function buildAiDisabledError(
  config: InternalAxiosRequestConfig,
): AxiosError {
  const error = new AxiosError(
    'AI features disabled in PrismTask Settings',
    'ERR_AI_FEATURES_DISABLED',
    config,
    undefined,
    {
      status: HTTP_451_UNAVAILABLE_FOR_LEGAL_REASONS,
      statusText: 'Unavailable For Legal Reasons',
      headers: { [HEADER_AI_FEATURES]: HEADER_VALUE_DISABLED },
      config,
      data: SYNTHETIC_BODY,
    },
  );
  return error;
}

// Request interceptor: AI-features gate + JWT attachment.
apiClient.interceptors.request.use((config) => {
  // Gate Anthropic-touching paths client-side. Mirrors Android's
  // AiFeatureGateInterceptor: when the user has disabled AI in Settings,
  // we short-circuit with a synthetic 451 instead of hitting the backend
  // so no PrismTask data reaches Anthropic.
  if (isAiTouchingPath(config.url) && !aiFeaturesEnabledProvider()) {
    return Promise.reject(buildAiDisabledError(config));
  }

  const token = localStorage.getItem('prismtask_access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response interceptor: handle 401 with token refresh + global error toasts
let isRefreshing = false;
let failedQueue: Array<{
  resolve: (value: unknown) => void;
  reject: (reason: unknown) => void;
}> = [];

const processQueue = (error: unknown) => {
  failedQueue.forEach((promise) => {
    if (error) {
      promise.reject(error);
    } else {
      promise.resolve(undefined);
    }
  });
  failedQueue = [];
};

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    // Per-request opt-out: callers issuing best-effort / background requests
    // (e.g. the briefing auto-generate on mount, analytics widgets that are
    // expected to be empty) set `suppressErrorToast` so a transient failure
    // doesn't flash a global error toast. Token-refresh (401) still runs.
    const suppressToast = Boolean(originalRequest?.suppressErrorToast);

    // Network error (no response)
    if (!error.response) {
      if (!suppressToast) toast.error('Network error. Check your connection.');
      return Promise.reject(error);
    }

    const status = error.response?.status;

    // Handle 401 with token refresh
    if (status === 401 && !originalRequest._retry) {
      // Don't retry auth endpoints
      if (
        originalRequest.url?.includes('/auth/login') ||
        originalRequest.url?.includes('/auth/register') ||
        originalRequest.url?.includes('/auth/refresh') ||
        originalRequest.url?.includes('/auth/firebase')
      ) {
        return Promise.reject(error);
      }

      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        }).then(() => apiClient(originalRequest));
      }

      originalRequest._retry = true;
      isRefreshing = true;

      const refreshToken = localStorage.getItem('prismtask_refresh_token');
      if (!refreshToken) {
        isRefreshing = false;
        localStorage.removeItem('prismtask_access_token');
        localStorage.removeItem('prismtask_refresh_token');
        // Let the auth store handle the redirect via React state.
        // Dynamic import avoids circular dependency (client → authStore → auth → client).
        const { useAuthStore } = await import('@/stores/authStore');
        useAuthStore.setState({ isAuthenticated: false, isLoading: false });
        return Promise.reject(error);
      }

      try {
        const response = await axios.post(`${API_BASE_URL}/auth/refresh`, {
          refresh_token: refreshToken,
        });

        const { access_token, refresh_token } = response.data;
        localStorage.setItem('prismtask_access_token', access_token);
        localStorage.setItem('prismtask_refresh_token', refresh_token);

        processQueue(null);
        return apiClient(originalRequest);
      } catch (refreshError) {
        processQueue(refreshError);
        localStorage.removeItem('prismtask_access_token');
        localStorage.removeItem('prismtask_refresh_token');
        // Let the auth store handle the redirect via React state.
        // Dynamic import avoids circular dependency (client → authStore → auth → client).
        const { useAuthStore } = await import('@/stores/authStore');
        useAuthStore.setState({ isAuthenticated: false, isLoading: false });
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    // 410 Gone — backend's get_active_user middleware returns this when
    // the account is pending deletion. Any in-flight or subsequent
    // mutation request will hit this. Force a logout so the web client
    // doesn't sit in a zombie state with a soft-deleted account: clear
    // the JWT pair and flip the auth store to unauthenticated. The
    // user's confirmation flow already calls logout() once on success,
    // but a racing request can land afterwards — this is the safety net.
    if (status === 410) {
      localStorage.removeItem('prismtask_access_token');
      localStorage.removeItem('prismtask_refresh_token');
      const { useAuthStore } = await import('@/stores/authStore');
      useAuthStore.getState().logout();
      toast.error('Your account has been scheduled for deletion. You have been signed out.');
      return Promise.reject(error);
    }

    // 451 Unavailable For Legal Reasons — synthetic response from the
    // request-side AI features gate (parity with Android's
    // AiFeatureGateInterceptor). Surface a clear, actionable toast rather
    // than the generic network error one.
    if (status === HTTP_451_UNAVAILABLE_FOR_LEGAL_REASONS) {
      toast.error('AI features are disabled. Re-enable them in Settings → AI Features.');
      return Promise.reject(error);
    }

    // Handle other HTTP errors with user-facing toasts
    if (suppressToast) {
      // Caller handles its own error UX — skip the global toast.
    } else if (status === 403) {
      toast.error("You don't have permission for this action.");
    } else if (status === 429) {
      const retryAfter = error.response.headers?.['retry-after'];
      const seconds = retryAfter ? parseInt(retryAfter, 10) : 30;
      toast.error(`Too many requests. Please wait ${seconds}s and try again.`);
    } else if (status >= 500) {
      toast.error('Server error. Please try again.');
    }
    // 422 errors are handled inline by forms — no global toast

    return Promise.reject(error);
  },
);

export default apiClient;
