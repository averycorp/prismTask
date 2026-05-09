import { describe, it, expect, vi, beforeEach } from 'vitest';

const {
  signInWithPopupMock,
  signOutMock,
  onAuthStateChangedMock,
  firebaseAuthMock,
  googleProviderMock,
  setFirebaseUidMock,
  authApiMock,
  getAiFeaturesEnabledMock,
  setAiFeaturesEnabledMock,
} = vi.hoisted(() => ({
  signInWithPopupMock: vi.fn(),
  signOutMock: vi.fn(),
  onAuthStateChangedMock: vi.fn(),
  firebaseAuthMock: { authStateReady: vi.fn().mockResolvedValue(undefined) },
  googleProviderMock: {},
  setFirebaseUidMock: vi.fn(),
  getAiFeaturesEnabledMock: vi.fn(),
  setAiFeaturesEnabledMock: vi.fn(),
  authApiMock: {
    login: vi.fn(),
    register: vi.fn(),
    firebaseLogin: vi.fn(),
    refresh: vi.fn(),
    me: vi.fn(),
    updateTier: vi.fn(),
    getDeletionStatus: vi.fn(),
    requestAccountDeletion: vi.fn(),
    cancelAccountDeletion: vi.fn(),
  },
}));

vi.mock('firebase/auth', () => ({
  signInWithPopup: signInWithPopupMock,
  signOut: signOutMock,
  onAuthStateChanged: onAuthStateChangedMock,
}));

vi.mock('@/lib/firebase', () => ({
  firebaseAuth: firebaseAuthMock,
  googleProvider: googleProviderMock,
}));

vi.mock('@/stores/firebaseUid', () => ({
  setFirebaseUid: setFirebaseUidMock,
}));

vi.mock('@/api/auth', () => ({
  authApi: authApiMock,
}));

vi.mock('@/api/firestore/aiPreferences', () => ({
  DEFAULT_AI_FEATURES_ENABLED: true,
  getAiFeaturesEnabled: getAiFeaturesEnabledMock,
  setAiFeaturesEnabled: setAiFeaturesEnabledMock,
}));

import { useAuthStore } from '@/stores/authStore';

function resetStore() {
  useAuthStore.setState({
    user: null,
    firebaseUser: null,
    firebaseUid: null,
    accessToken: null,
    refreshToken: null,
    isAuthenticated: false,
    isLoading: false,
    deletionStatus: 'unknown',
    deletionScheduledFor: null,
  });
}

describe('authStore — deletion-status sign-in gating', () => {
  beforeEach(() => {
    resetStore();
    Object.values(authApiMock).forEach((fn) => fn.mockReset());
    getAiFeaturesEnabledMock.mockReset();
    getAiFeaturesEnabledMock.mockResolvedValue(true);
    setAiFeaturesEnabledMock.mockReset();
    setAiFeaturesEnabledMock.mockResolvedValue(undefined);
    signInWithPopupMock.mockReset();
    setFirebaseUidMock.mockReset();
    localStorage.clear();

    // Default: backend account exchange + me() succeed and return tokens
    authApiMock.firebaseLogin.mockResolvedValue({
      access_token: 'jwt-access',
      refresh_token: 'jwt-refresh',
      token_type: 'bearer',
    });
    authApiMock.me.mockResolvedValue({
      id: 1,
      email: 'user@example.com',
      name: 'User',
      tier: 'FREE',
      created_at: '2026-01-01',
      updated_at: '2026-01-01',
    });

    signInWithPopupMock.mockResolvedValue({
      user: {
        uid: 'fb-uid',
        email: 'user@example.com',
        displayName: 'User',
        photoURL: null,
        getIdToken: vi.fn().mockResolvedValue('id-token'),
      },
    });
  });

  it('calls getDeletionStatus after Firebase sign-in succeeds', async () => {
    authApiMock.getDeletionStatus.mockResolvedValueOnce({
      deletion_pending_at: null,
      deletion_scheduled_for: null,
      deletion_initiated_from: null,
    });

    await useAuthStore.getState().signInWithGoogle();

    expect(authApiMock.getDeletionStatus).toHaveBeenCalledTimes(1);
  });

  it('routes to RestorePending (deletionStatus="pending") when backend reports pending deletion', async () => {
    authApiMock.getDeletionStatus.mockResolvedValueOnce({
      deletion_pending_at: '2026-04-25T12:00:00Z',
      deletion_scheduled_for: '2026-05-25T12:00:00Z',
      deletion_initiated_from: 'android',
    });

    await useAuthStore.getState().signInWithGoogle();

    const state = useAuthStore.getState();
    expect(state.deletionStatus).toBe('pending');
    expect(state.deletionScheduledFor).toBe('2026-05-25T12:00:00Z');
    // The user IS authenticated (Firebase sign-in succeeded); the gate
    // is what blocks the AppShell, not isAuthenticated.
    expect(state.isAuthenticated).toBe(true);
  });

  it('proceeds to active state when no deletion is pending', async () => {
    authApiMock.getDeletionStatus.mockResolvedValueOnce({
      deletion_pending_at: null,
      deletion_scheduled_for: null,
      deletion_initiated_from: null,
    });

    await useAuthStore.getState().signInWithGoogle();

    const state = useAuthStore.getState();
    expect(state.deletionStatus).toBe('active');
    expect(state.deletionScheduledFor).toBeNull();
    expect(state.isAuthenticated).toBe(true);
  });

  it('keeps the gate at "unknown" if the deletion-status check throws (fail-closed)', async () => {
    authApiMock.getDeletionStatus.mockRejectedValueOnce(new Error('Network down'));

    await useAuthStore.getState().signInWithGoogle();

    // Fail-closed: the splash stays up rather than letting the user
    // silently leak through to the AppShell.
    expect(useAuthStore.getState().deletionStatus).toBe('unknown');
  });

  it('orders the deletion check BEFORE fetchUser so the gate is set before the AppShell could render', async () => {
    const callOrder: string[] = [];
    authApiMock.getDeletionStatus.mockImplementationOnce(async () => {
      callOrder.push('getDeletionStatus');
      return {
        deletion_pending_at: '2026-04-25T12:00:00Z',
        deletion_scheduled_for: '2026-05-25T12:00:00Z',
        deletion_initiated_from: 'android',
      };
    });
    authApiMock.me.mockImplementationOnce(async () => {
      callOrder.push('me');
      return {
        id: 1,
        email: 'user@example.com',
        name: 'User',
        tier: 'FREE' as const,
        created_at: '2026-01-01',
        updated_at: '2026-01-01',
      };
    });

    await useAuthStore.getState().signInWithGoogle();

    const deletionIdx = callOrder.indexOf('getDeletionStatus');
    const meIdx = callOrder.indexOf('me');
    expect(deletionIdx).toBeGreaterThanOrEqual(0);
    expect(meIdx).toBeGreaterThan(deletionIdx);
  });

  it('restoreAccount calls cancelAccountDeletion and flips the gate to active', async () => {
    useAuthStore.setState({
      deletionStatus: 'pending',
      deletionScheduledFor: '2026-05-25T12:00:00Z',
    });
    authApiMock.cancelAccountDeletion.mockResolvedValueOnce({
      deletion_pending_at: null,
      deletion_scheduled_for: null,
      deletion_initiated_from: null,
    });

    await useAuthStore.getState().restoreAccount();

    expect(authApiMock.cancelAccountDeletion).toHaveBeenCalledTimes(1);
    const state = useAuthStore.getState();
    expect(state.deletionStatus).toBe('active');
    expect(state.deletionScheduledFor).toBeNull();
  });

  it('restoreAccount surfaces the API error so the screen can show it (no silent flip to active)', async () => {
    useAuthStore.setState({
      deletionStatus: 'pending',
      deletionScheduledFor: '2026-05-25T12:00:00Z',
    });
    authApiMock.cancelAccountDeletion.mockRejectedValueOnce(
      new Error('Backend exploded'),
    );

    await expect(useAuthStore.getState().restoreAccount()).rejects.toThrow(
      'Backend exploded',
    );
    // Stays pending — the user never restored.
    expect(useAuthStore.getState().deletionStatus).toBe('pending');
  });

  it('abandonRestore signs out without cancelling the deletion', async () => {
    useAuthStore.setState({
      deletionStatus: 'pending',
      deletionScheduledFor: '2026-05-25T12:00:00Z',
      isAuthenticated: true,
    });
    signOutMock.mockResolvedValueOnce(undefined);

    await useAuthStore.getState().abandonRestore();

    expect(authApiMock.cancelAccountDeletion).not.toHaveBeenCalled();
    expect(signOutMock).toHaveBeenCalledTimes(1);
    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(false);
    expect(state.deletionStatus).toBe('unknown');
  });

  it('logout clears deletion state', () => {
    useAuthStore.setState({
      deletionStatus: 'pending',
      deletionScheduledFor: '2026-05-25T12:00:00Z',
      isAuthenticated: true,
    });
    signOutMock.mockResolvedValueOnce(undefined);

    useAuthStore.getState().logout();

    const state = useAuthStore.getState();
    expect(state.deletionStatus).toBe('unknown');
    expect(state.deletionScheduledFor).toBeNull();
  });
});
