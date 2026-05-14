import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * Parity audit item G.7 — logout must terminate the Firestore client
 * and clear the IndexedDB offline cache so a different user signing
 * in on the same browser can't read the previous user's stale docs.
 */

const {
  signOutMock,
  onAuthStateChangedMock,
  signInWithPopupMock,
  firebaseAuthMock,
  firestoreMock,
  googleProviderMock,
  terminateMock,
  clearIndexedDbPersistenceMock,
  setFirebaseUidMock,
  authApiMock,
  getAiFeaturesEnabledMock,
  setAiFeaturesEnabledMock,
} = vi.hoisted(() => ({
  signOutMock: vi.fn().mockResolvedValue(undefined),
  onAuthStateChangedMock: vi.fn(),
  signInWithPopupMock: vi.fn(),
  firebaseAuthMock: { authStateReady: vi.fn().mockResolvedValue(undefined) },
  firestoreMock: { __mock: 'firestore' },
  googleProviderMock: {},
  terminateMock: vi.fn().mockResolvedValue(undefined),
  clearIndexedDbPersistenceMock: vi.fn().mockResolvedValue(undefined),
  setFirebaseUidMock: vi.fn(),
  getAiFeaturesEnabledMock: vi.fn().mockResolvedValue(true),
  setAiFeaturesEnabledMock: vi.fn().mockResolvedValue(undefined),
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

vi.mock('firebase/firestore', () => ({
  terminate: terminateMock,
  clearIndexedDbPersistence: clearIndexedDbPersistenceMock,
}));

vi.mock('@/lib/firebase', () => ({
  firebaseAuth: firebaseAuthMock,
  firestore: firestoreMock,
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

/**
 * Wait for queued microtasks (the IIFE inside logout) to settle. A
 * handful of macrotask hops is enough to let the chained awaits resolve
 * under the default mocks above.
 */
async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
  }
}

function resetStore() {
  useAuthStore.setState({
    user: null,
    firebaseUser: null,
    firebaseUid: null,
    accessToken: null,
    refreshToken: null,
    isAuthenticated: true,
    isLoading: false,
    deletionStatus: 'active',
    deletionScheduledFor: null,
  });
}

describe('authStore — logout clears Firestore cache (parity G.7)', () => {
  let assignMock: ReturnType<typeof vi.fn>;
  let originalLocation: Location;

  beforeEach(() => {
    resetStore();
    terminateMock.mockReset().mockResolvedValue(undefined);
    clearIndexedDbPersistenceMock.mockReset().mockResolvedValue(undefined);
    signOutMock.mockReset().mockResolvedValue(undefined);
    setFirebaseUidMock.mockReset();
    localStorage.clear();
    localStorage.setItem('prismtask_access_token', 'jwt-a');
    localStorage.setItem('prismtask_refresh_token', 'jwt-r');

    // Stub window.location so the reload doesn't tear jsdom apart.
    originalLocation = window.location;
    assignMock = vi.fn();
    // @ts-expect-error -- replacing read-only location for the test.
    delete window.location;
    // @ts-expect-error -- partial Location is fine for the assertions.
    window.location = { assign: assignMock };
  });

  afterEach(() => {
    // @ts-expect-error -- restoring the original Location.
    window.location = originalLocation;
  });

  it('clears the JWT pair and resets auth state synchronously', () => {
    useAuthStore.getState().logout();

    expect(localStorage.getItem('prismtask_access_token')).toBeNull();
    expect(localStorage.getItem('prismtask_refresh_token')).toBeNull();
    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(false);
    expect(state.firebaseUid).toBeNull();
    expect(state.user).toBeNull();
    expect(state.accessToken).toBeNull();
  });

  it('calls terminate(firestore) then clearIndexedDbPersistence(firestore)', async () => {
    useAuthStore.getState().logout();
    await flushMicrotasks();

    expect(terminateMock).toHaveBeenCalledTimes(1);
    expect(terminateMock).toHaveBeenCalledWith(firestoreMock);
    expect(clearIndexedDbPersistenceMock).toHaveBeenCalledTimes(1);
    expect(clearIndexedDbPersistenceMock).toHaveBeenCalledWith(firestoreMock);

    // Ordering matters — clearIndexedDbPersistence only works after
    // terminate().
    const terminateOrder = terminateMock.mock.invocationCallOrder[0];
    const clearOrder = clearIndexedDbPersistenceMock.mock.invocationCallOrder[0];
    expect(terminateOrder).toBeLessThan(clearOrder);
  });

  it('still clears the cache when signOut fails (best-effort sign-out)', async () => {
    signOutMock.mockRejectedValueOnce(new Error('network'));

    useAuthStore.getState().logout();
    await flushMicrotasks();

    expect(terminateMock).toHaveBeenCalledTimes(1);
    expect(clearIndexedDbPersistenceMock).toHaveBeenCalledTimes(1);
  });

  it('swallows clearIndexedDbPersistence failures (multi-tab case) so logout still completes', async () => {
    clearIndexedDbPersistenceMock.mockRejectedValueOnce(
      new Error('failed-precondition: other tabs'),
    );
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    useAuthStore.getState().logout();
    await flushMicrotasks();

    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('forces a reload to /login so the firestore handle is rebuilt for the next sign-in', async () => {
    useAuthStore.getState().logout();
    await flushMicrotasks();

    expect(assignMock).toHaveBeenCalledWith('/login');
  });
});
