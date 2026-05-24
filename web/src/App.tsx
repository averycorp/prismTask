import { useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';
import { Toaster } from 'sonner';
import { router } from '@/routes';
import { useAuthStore } from '@/stores/authStore';
import { useBatchStore } from '@/stores/batchStore';
import { useOnboardingStore } from '@/stores/onboardingStore';
import { applyA11yToDocument, useA11yStore } from '@/stores/a11yStore';
import { useFirestoreSync } from '@/hooks/useFirestoreSync';
import { ErrorBoundary, ErrorBoundaryFallback } from '@/components/shared/ErrorBoundary';
import { OfflineBanner } from '@/components/shared/OfflineBanner';
import { PrismThemeProvider } from '@/theme/PrismThemeProvider';
import { useHabitStore } from '@/stores/habitStore';
import { useTaskStore } from '@/stores/taskStore';

if (typeof window !== 'undefined') {
  (window as any).useAuthStore = useAuthStore;
  (window as any).useOnboardingStore = useOnboardingStore;
  (window as any).useHabitStore = useHabitStore;
  (window as any).useTaskStore = useTaskStore;
  (window as any).router = router;
}

export default function App() {
  const hydrateFromStorage = useAuthStore((s) => s.hydrateFromStorage);

  const initFirebaseAuthListener = useAuthStore((s) => s.initFirebaseAuthListener);
  const firebaseUid = useAuthStore((s) => s.firebaseUser?.uid);
  const hydrateBatch = useBatchStore((s) => s.hydrate);

  // Initialize Firebase Auth listener + hydrate JWT tokens on mount
  useEffect(() => {
    const unsubscribe = initFirebaseAuthListener();
    hydrateFromStorage();
    return unsubscribe;
  }, [initFirebaseAuthListener, hydrateFromStorage]);

  // Load per-uid batch history from localStorage once the user is known
  // so SettingsScreen + Snackbar undo have access after a refresh.
  useEffect(() => {
    if (firebaseUid) hydrateBatch(firebaseUid);
  }, [firebaseUid, hydrateBatch]);

  // Hydrate onboarding status from Firestore on sign-in. Reset back to
  // "unknown" on sign-out so the next user sees the onboarding flow
  // gate correctly.
  const hydrateOnboarding = useOnboardingStore((s) => s.hydrate);
  const resetOnboarding = useOnboardingStore((s) => s.reset);
  useEffect(() => {
    if (firebaseUid) {
      hydrateOnboarding(firebaseUid);
    } else {
      resetOnboarding();
    }
  }, [firebaseUid, hydrateOnboarding, resetOnboarding]);

  // Wire Firestore real-time listeners (tasks, projects, tags, habits,
  // habit completions, medication slot defs, medication preferences)
  // for the duration of the signed-in session. Until this PR landed,
  // every `subscribeTo*` function in `web/src/api/firestore/*.ts` was
  // defined but never invoked, so cross-device updates only appeared
  // after a manual page refresh.
  useFirestoreSync(firebaseUid);

  // Theme application is owned by `<PrismThemeProvider>` below — it
  // subscribes to the same store and fires `applyThemeToDocument` on
  // every key change. Mounting it inside the tree (rather than a
  // top-level effect here) keeps the React tree the single source of
  // truth for the active variant.

  // Apply accessibility overrides on mount (AccessibilitySection re-applies
  // when the user toggles controls, but the initial load needs this).
  const a11yFontScale = useA11yStore((s) => s.fontScale);
  const a11yHighContrast = useA11yStore((s) => s.highContrast);
  const a11yReducedMotion = useA11yStore((s) => s.reducedMotion);
  useEffect(() => {
    applyA11yToDocument({
      fontScale: a11yFontScale,
      highContrast: a11yHighContrast,
      reducedMotion: a11yReducedMotion,
    });
  }, [a11yFontScale, a11yHighContrast, a11yReducedMotion]);

  // Register service worker for PWA
  useEffect(() => {
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('/sw.js').catch(() => {
        // Service worker registration failed — silent fallback
      });
    }
  }, []);

  return (
    <ErrorBoundary FallbackComponent={ErrorBoundaryFallback}>
      <PrismThemeProvider>
        <OfflineBanner />
        <RouterProvider router={router} />
        <Toaster
          position="bottom-right"
          toastOptions={{
            style: {
              background: 'var(--color-bg-card)',
              color: 'var(--color-text-primary)',
              border: '1px solid var(--color-border)',
            },
          }}
        />
      </PrismThemeProvider>
    </ErrorBoundary>
  );
}
