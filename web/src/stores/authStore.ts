import { create } from 'zustand';
import {
  signInWithPopup,
  signOut,
  onAuthStateChanged,
  type User as FbUser,
} from 'firebase/auth';
import { firebaseAuth, googleProvider } from '@/lib/firebase';
import type { User, FirebaseUser } from '@/types/auth';
import { authApi } from '@/api/auth';
import { setFirebaseUid } from '@/stores/firebaseUid';
import { useSettingsStore } from '@/stores/settingsStore';

/**
 * Tri-state deletion status that gates the authed UI.
 *
 *   unknown — sign-in is in flight, or the deletion check hasn't returned
 *             yet. The route tree shows a splash; sync MUST NOT run.
 *   active  — the account is in normal use (no pending deletion, or the
 *             grace window has lapsed and the next purge will surface
 *             through Firestore). Render the normal AppShell.
 *   pending — the backend reports a deletion scheduled within the grace
 *             window. RestorePendingGate takes over with a full-screen
 *             restore-or-sign-out prompt (parity with Android
 *             `AuthScreen.kt:72-80`).
 */
export type DeletionGateStatus = 'unknown' | 'active' | 'pending';

interface AuthState {
  /** FastAPI backend user (for NLP/AI features) */
  user: User | null;
  /** Firebase Auth user (for Firestore access) */
  firebaseUser: FirebaseUser | null;
  /** Firebase UID — the key for all Firestore paths */
  firebaseUid: string | null;
  /** JWT for FastAPI backend calls */
  accessToken: string | null;
  refreshToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;

  /**
   * Whether this account is in the deletion grace window. Drives
   * `RestorePendingGate`. Reset to `'unknown'` on every sign-in / hydrate
   * so the gate shows the splash until the backend confirms.
   */
  deletionStatus: DeletionGateStatus;
  /** ISO timestamp of when the account will be permanently deleted. */
  deletionScheduledFor: string | null;

  // Firebase Auth
  signInWithGoogle: () => Promise<void>;
  initFirebaseAuthListener: () => () => void;

  // Legacy email/password (calls FastAPI only)
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, name: string) => Promise<void>;

  logout: () => void;
  refreshAccessToken: () => Promise<void>;
  hydrateFromStorage: () => Promise<void>;
  fetchUser: () => Promise<void>;

  /**
   * Pull the latest deletion status from the backend and update the gate
   * state. Called after every sign-in / hydration path before the user is
   * allowed to leave `RestorePendingGate`.
   */
  refreshDeletionStatus: () => Promise<void>;
  /**
   * Cancel a pending deletion (the user tapped "Restore" inside the
   * grace window). DELETEs `/auth/me/deletion` and on success flips the
   * gate to `'active'`.
   */
  restoreAccount: () => Promise<void>;
  /**
   * The user chose to let the deletion proceed: sign out without
   * cancelling the pending deletion. Next sign-in either falls back into
   * RestorePending (still in window) or triggers permanent purge.
   */
  abandonRestore: () => Promise<void>;
}

function toFirebaseUser(fbUser: FbUser): FirebaseUser {
  return {
    uid: fbUser.uid,
    email: fbUser.email,
    displayName: fbUser.displayName,
    photoURL: fbUser.photoURL,
  };
}

/**
 * After Firebase Auth succeeds, ensure the user also has a FastAPI account
 * and obtain a JWT for backend API calls (NLP, AI features).
 *
 * Sends the Firebase ID token to POST /auth/firebase, which verifies it
 * server-side and finds-or-creates the backend user.  Falls back to the
 * legacy email/password flow if the new endpoint is unavailable.
 */
async function ensureBackendAccount(fbUser: FbUser): Promise<void> {
  const email = fbUser.email;
  if (!email) return;

  // Preferred path: send the Firebase ID token for server-side verification
  try {
    const idToken = await fbUser.getIdToken();
    const tokens = await authApi.firebaseLogin({
      firebase_token: idToken,
      name: fbUser.displayName || undefined,
    });
    localStorage.setItem('prismtask_access_token', tokens.access_token);
    localStorage.setItem('prismtask_refresh_token', tokens.refresh_token);
    return;
  } catch {
    // Firebase endpoint unavailable — fall back to legacy flow
  }

  // Legacy fallback: synthetic email/password login, then register
  try {
    const tokens = await authApi.login({
      email,
      password: `firebase_${fbUser.uid}`,
    });
    localStorage.setItem('prismtask_access_token', tokens.access_token);
    localStorage.setItem('prismtask_refresh_token', tokens.refresh_token);
    return;
  } catch {
    // Login failed — try to register
  }

  try {
    const tokens = await authApi.register({
      email,
      name: fbUser.displayName || email.split('@')[0],
      password: `firebase_${fbUser.uid}`,
    });
    localStorage.setItem('prismtask_access_token', tokens.access_token);
    localStorage.setItem('prismtask_refresh_token', tokens.refresh_token);
  } catch {
    // Backend unavailable — Firestore still works without JWT
    console.warn('Could not establish backend account. NLP/AI features may be unavailable.');
  }
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  firebaseUser: null,
  firebaseUid: null,
  accessToken: null,
  refreshToken: null,
  isAuthenticated: false,
  isLoading: true,
  deletionStatus: 'unknown',
  deletionScheduledFor: null,

  signInWithGoogle: async () => {
    // Reset the gate so any cached "active" from a previous session
    // can't briefly leak the AppShell while the backend check is in
    // flight.
    set({ deletionStatus: 'unknown', deletionScheduledFor: null });

    const result = await signInWithPopup(firebaseAuth, googleProvider);
    const fbUser = result.user;

    setFirebaseUid(fbUser.uid);
    set({
      firebaseUser: toFirebaseUser(fbUser),
      firebaseUid: fbUser.uid,
      isAuthenticated: true,
      isLoading: false,
    });

    // Pull cross-device synced settings (e.g. AI-features opt-out,
    // Start-of-Day hour) from Firestore so Android's values propagate
    // immediately. Best-effort.
    void useSettingsStore.getState().loadAiFeaturesFromFirestore(fbUser.uid);
    void useSettingsStore.getState().loadStartOfDayHourFromFirestore(fbUser.uid);

    // Link with FastAPI backend for NLP/AI features
    await ensureBackendAccount(fbUser);

    const accessToken = localStorage.getItem('prismtask_access_token');
    const refreshToken = localStorage.getItem('prismtask_refresh_token');
    set({ accessToken, refreshToken });

    // Check deletion status BEFORE attempting to fetch the user profile
    // / proceed to the AppShell, mirroring Android's
    // `AuthScreen.kt:72-80` ordering. If the account is pending
    // deletion, RestorePendingGate intercepts the next render and the
    // user can't reach any sync surface until they restore or sign out.
    await get().refreshDeletionStatus();

    // Try to fetch the backend user profile
    try {
      await get().fetchUser();
    } catch {
      // Backend user fetch failed — non-critical
    }
  },

  initFirebaseAuthListener: () => {
    const unsubscribe = onAuthStateChanged(firebaseAuth, async (fbUser) => {
      if (fbUser) {
        setFirebaseUid(fbUser.uid);
        set({
          firebaseUser: toFirebaseUser(fbUser),
          firebaseUid: fbUser.uid,
          isAuthenticated: true,
          isLoading: false,
        });

        // Pull cross-device synced settings (e.g. AI-features opt-out,
        // Start-of-Day hour) from Firestore so Android's values
        // propagate on session restore.
        void useSettingsStore.getState().loadAiFeaturesFromFirestore(fbUser.uid);
        void useSettingsStore.getState().loadStartOfDayHourFromFirestore(fbUser.uid);

        // Restore JWT tokens from localStorage
        const accessToken = localStorage.getItem('prismtask_access_token');
        const refreshToken = localStorage.getItem('prismtask_refresh_token');
        if (accessToken) {
          set({ accessToken, refreshToken });
          // Re-check deletion status on every auth-state change so a
          // background tab / hot reload that still has Firebase
          // credentials can't slip past the gate.
          try {
            await get().refreshDeletionStatus();
          } catch {
            // Non-critical — gate stays at 'unknown' until next
            // explicit refresh.
          }
          try {
            await get().fetchUser();
          } catch {
            // Non-critical
          }
        }
      } else {
        setFirebaseUid(null);
        set({
          firebaseUser: null,
          firebaseUid: null,
          user: null,
          accessToken: null,
          refreshToken: null,
          isAuthenticated: false,
          isLoading: false,
          deletionStatus: 'unknown',
          deletionScheduledFor: null,
        });
      }
    });
    return unsubscribe;
  },

  login: async (email, password) => {
    set({ deletionStatus: 'unknown', deletionScheduledFor: null });
    const tokens = await authApi.login({ email, password });
    localStorage.setItem('prismtask_access_token', tokens.access_token);
    localStorage.setItem('prismtask_refresh_token', tokens.refresh_token);
    set({
      accessToken: tokens.access_token,
      refreshToken: tokens.refresh_token,
      isAuthenticated: true,
    });
    await get().refreshDeletionStatus();
    await get().fetchUser();
  },

  register: async (email, password, name) => {
    const tokens = await authApi.register({ email, name, password });
    localStorage.setItem('prismtask_access_token', tokens.access_token);
    localStorage.setItem('prismtask_refresh_token', tokens.refresh_token);
    set({
      accessToken: tokens.access_token,
      refreshToken: tokens.refresh_token,
      isAuthenticated: true,
      // Brand-new accounts can't be in deletion-pending state.
      deletionStatus: 'active',
      deletionScheduledFor: null,
    });
    await get().fetchUser();
  },

  logout: () => {
    signOut(firebaseAuth).catch(() => {});
    setFirebaseUid(null);
    localStorage.removeItem('prismtask_access_token');
    localStorage.removeItem('prismtask_refresh_token');
    set({
      user: null,
      firebaseUser: null,
      firebaseUid: null,
      accessToken: null,
      refreshToken: null,
      isAuthenticated: false,
      deletionStatus: 'unknown',
      deletionScheduledFor: null,
    });
  },

  refreshAccessToken: async () => {
    const refreshToken = get().refreshToken;
    if (!refreshToken) throw new Error('No refresh token');

    const tokens = await authApi.refresh(refreshToken);
    localStorage.setItem('prismtask_access_token', tokens.access_token);
    localStorage.setItem('prismtask_refresh_token', tokens.refresh_token);
    set({
      accessToken: tokens.access_token,
      refreshToken: tokens.refresh_token,
    });
  },

  hydrateFromStorage: async () => {
    // Firebase Auth listener handles Firebase state automatically.
    // Here we just restore JWT tokens for the backend.
    const accessToken = localStorage.getItem('prismtask_access_token');
    const refreshToken = localStorage.getItem('prismtask_refresh_token');

    if (accessToken) {
      set({ accessToken, refreshToken });
      try {
        await get().fetchUser();
      } catch {
        if (refreshToken) {
          try {
            await get().refreshAccessToken();
            await get().fetchUser();
          } catch {
            // Non-critical — Firestore works without JWT
          }
        }
      }
    }

    // Wait for Firebase auth to be ready before checking currentUser,
    // so we don't prematurely set isLoading: false before the persisted
    // session is resolved (race condition fix).
    await firebaseAuth.authStateReady();
    if (!firebaseAuth.currentUser) {
      set({ isLoading: false });
    }
  },

  fetchUser: async () => {
    const user = await authApi.me();
    set({ user });
  },

  refreshDeletionStatus: async () => {
    try {
      const status = await authApi.getDeletionStatus();
      // Backend returns `deletion_pending_at: null` when the account is
      // active. The grace window is server-driven; the client treats any
      // non-null pending mark as "pending" and lets the user decide
      // before sync runs.
      const pending = status.deletion_pending_at != null;
      set({
        deletionStatus: pending ? 'pending' : 'active',
        deletionScheduledFor: status.deletion_scheduled_for,
      });
    } catch {
      // If we can't read the status, fail CLOSED (mirrors Android's
      // `checkDeletionStatus` Result.failure: surface the choice rather
      // than let the user silently proceed and overwrite a deletion
      // mark). Stay at 'unknown' so the gate keeps the splash.
      // Caller may retry; sign-out path resets to 'unknown' anyway.
    }
  },

  restoreAccount: async () => {
    await authApi.cancelAccountDeletion();
    set({
      deletionStatus: 'active',
      deletionScheduledFor: null,
    });
  },

  abandonRestore: async () => {
    // Don't cancel the pending deletion — the user explicitly chose to
    // let it proceed. Just sign out so the next sign-in re-enters the
    // gate (or triggers permanent purge once the grace period lapses).
    get().logout();
  },
}));
