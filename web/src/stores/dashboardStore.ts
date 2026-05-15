import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  DEFAULT_COLLAPSED_SECTIONS,
  DEFAULT_HIDDEN_SECTIONS,
  DEFAULT_PROGRESS_STYLE,
  DEFAULT_SECTION_ORDER,
  getDashboardPreferences,
  setHiddenSections as setHiddenSectionsOnFirestore,
  setProgressStyle as setProgressStyleOnFirestore,
  setSectionOrder as setSectionOrderOnFirestore,
  subscribeToDashboardPreferences,
  type DashboardPreferencesSnapshot,
} from '@/api/firestore/dashboardPreferences';
import { getFirebaseUid } from '@/stores/firebaseUid';

/**
 * Thin Zustand store exposing the cross-device Today-dashboard
 * preferences mirrored from `users/{uid}/prefs/dashboard_prefs`.
 *
 * Parity audit C.1f — pairs the foundational Firestore mirror that
 * landed in PR #1371 (parity A.5b) with a web UI consumer:
 *   - `TodayScreen` reads `sectionOrder` + `hiddenSections` to render
 *     its dynamic sections in user-configured order and skip hidden
 *     ones.
 *   - Settings → Dashboard exposes a reorder UI + per-section toggles
 *     that write back through the store.
 *
 * Shape mirrors Android's `DashboardPreferences` DataStore so a
 * cross-device order/visibility tweak round-trips identically.
 * Defaults match Android's `DEFAULT_ORDER` (minus the `habits` slot,
 * which web renders inline rather than as a dashboard section — the
 * mirror still tolerates and round-trips it from Android writes).
 *
 * Self-echo suppression is intentionally NOT implemented here: the
 * Firestore listener applies remote snapshots unconditionally, last-
 * write-wins. Same convention as the rest of the parity-A.5b stores
 * (see `medicationPreferencesStore`, `boundaryRulesStore`). The
 * cross-device delay is sub-second in practice; the local optimistic
 * apply means the user sees their change immediately regardless.
 */

interface DashboardState {
  sectionOrder: string[];
  hiddenSections: string[];
  progressStyle: string;
  collapsedSections: string[];
  loaded: boolean;

  /** Pull cross-device synced dashboard prefs at sign-in. Best-effort. */
  load: (uid: string) => Promise<void>;

  /** Reorder the dashboard sections. Optimistic + push to Firestore. */
  setSectionOrder: (order: string[]) => void;

  /** Show/hide a single section. Optimistic + push to Firestore. */
  setSectionHidden: (key: string, hidden: boolean) => void;

  /** Pick a progress-card style. Optimistic + push to Firestore. */
  setProgressStyle: (style: string) => void;

  /** Apply a remote snapshot. Used by the Firestore listener. */
  applyRemoteSnapshot: (snapshot: DashboardPreferencesSnapshot) => void;

  /** Wire the Firestore real-time listener. */
  subscribeToPrefs: (uid: string) => Unsubscribe;

  /** Reset to Android defaults — used on sign-out. */
  reset: () => void;
}

function currentUid(): string | null {
  try {
    return getFirebaseUid();
  } catch {
    return null;
  }
}

/**
 * If the user customized their order before a new section key was
 * introduced, append the missing keys so the Settings UI surface
 * still shows them. Mirrors Android's `DashboardPreferences.getSectionOrder`
 * append-missing-defaults behavior.
 */
function reconcileOrder(stored: string[]): string[] {
  const missing = DEFAULT_SECTION_ORDER.filter((k) => !stored.includes(k));
  return missing.length === 0 ? stored : [...stored, ...missing];
}

export const useDashboardStore = create<DashboardState>((set, get) => ({
  sectionOrder: [...DEFAULT_SECTION_ORDER],
  hiddenSections: [...DEFAULT_HIDDEN_SECTIONS],
  progressStyle: DEFAULT_PROGRESS_STYLE,
  collapsedSections: [...DEFAULT_COLLAPSED_SECTIONS],
  loaded: false,

  load: async (uid: string) => {
    try {
      const snap = await getDashboardPreferences(uid);
      set({
        sectionOrder: reconcileOrder(snap.sectionOrder),
        hiddenSections: snap.hiddenSections,
        progressStyle: snap.progressStyle,
        collapsedSections: snap.collapsedSections,
        loaded: true,
      });
    } catch (e) {
      console.warn('[dashboardStore] load failed', e);
      set({ loaded: true });
    }
  },

  setSectionOrder: (order: string[]) => {
    const reconciled = reconcileOrder(order);
    set({ sectionOrder: reconciled });
    const uid = currentUid();
    if (uid) {
      void setSectionOrderOnFirestore(uid, reconciled).catch((err) => {
        console.warn('[dashboardStore] setSectionOrder firestore push failed', err);
      });
    }
  },

  setSectionHidden: (key: string, hidden: boolean) => {
    const prev = get().hiddenSections;
    const next = hidden
      ? Array.from(new Set([...prev, key]))
      : prev.filter((k) => k !== key);
    if (
      next.length === prev.length &&
      next.every((k, i) => k === prev[i])
    ) {
      return;
    }
    set({ hiddenSections: next });
    const uid = currentUid();
    if (uid) {
      void setHiddenSectionsOnFirestore(uid, next).catch((err) => {
        console.warn('[dashboardStore] setHiddenSections firestore push failed', err);
      });
    }
  },

  setProgressStyle: (style: string) => {
    if (style === get().progressStyle) return;
    set({ progressStyle: style });
    const uid = currentUid();
    if (uid) {
      void setProgressStyleOnFirestore(uid, style).catch((err) => {
        console.warn('[dashboardStore] setProgressStyle firestore push failed', err);
      });
    }
  },

  applyRemoteSnapshot: (snapshot) => {
    set({
      sectionOrder: reconcileOrder(snapshot.sectionOrder),
      hiddenSections: snapshot.hiddenSections,
      progressStyle: snapshot.progressStyle,
      collapsedSections: snapshot.collapsedSections,
      loaded: true,
    });
  },

  subscribeToPrefs: (uid: string) =>
    subscribeToDashboardPreferences(uid, (snapshot) => {
      get().applyRemoteSnapshot(snapshot);
    }),

  reset: () =>
    set({
      sectionOrder: [...DEFAULT_SECTION_ORDER],
      hiddenSections: [...DEFAULT_HIDDEN_SECTIONS],
      progressStyle: DEFAULT_PROGRESS_STYLE,
      collapsedSections: [...DEFAULT_COLLAPSED_SECTIONS],
      loaded: false,
    }),
}));
