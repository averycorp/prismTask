import { useState } from 'react';
import { RotateCcw, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { doc, setDoc } from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { useAuthStore } from '@/stores/authStore';
import { useBatchStore } from '@/stores/batchStore';
import { useOnboardingStore } from '@/stores/onboardingStore';
import { replaceBatchHistoryForUser } from '@/lib/idb/batchHistoryStore';

/**
 * Debug section — local-state maintenance tools that used to live on
 * ad-hoc Android DebugTier / DebugOnboarding sections. All actions are
 * scoped to this client and / or the user's own data.
 */
export function DebugSection() {
  const uid = useAuthStore((s) => s.firebaseUser?.uid);
  const resetOnboarding = useOnboardingStore((s) => s.reset);
  const hydrateBatch = useBatchStore((s) => s.hydrate);

  const [confirm, setConfirm] = useState<
    'replayOnboarding' | 'clearBatchHistory' | null
  >(null);
  const [busy, setBusy] = useState(false);

  const replayOnboarding = async () => {
    if (!uid) return;
    setBusy(true);
    try {
      // Clear the server-side completion flag so OnboardingGate
      // redirects to /onboarding on next route change.
      await setDoc(
        doc(firestore, 'users', uid),
        { onboardingCompletedAt: null },
        { merge: true },
      );
      resetOnboarding();
      toast.success('Onboarding reset — navigate anywhere to trigger it.');
    } catch (e) {
      toast.error((e as Error).message || 'Failed to reset onboarding');
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  };

  const clearBatchHistory = async () => {
    if (!uid) return;
    try {
      // Clear both the IDB store (current persistence) and any leftover
      // localStorage payload from the pre-IDB build, in case the user
      // hasn't gone through a hydrate cycle since the migration.
      try {
        localStorage.removeItem(`prismtask_batch_history_${uid}`);
      } catch {
        // ignore
      }
      await replaceBatchHistoryForUser(uid, []);
      await hydrateBatch(uid);
      toast.success('Batch history cleared on this device.');
    } catch (e) {
      toast.error((e as Error).message || 'Failed to clear history');
    } finally {
      setConfirm(null);
    }
  };

  return (
    <div className="flex flex-col gap-3 text-sm">
      <p className="text-xs text-[var(--color-text-secondary)]">
        Maintenance utilities — these only touch your own account and this
        browser's local storage.
      </p>

      <Row
        title="Replay onboarding"
        description="Clears the server-side completion flag. You'll see the wizard again on next navigation."
        button={
          <Button
            variant="secondary"
            size="sm"
            onClick={() => setConfirm('replayOnboarding')}
          >
            <RotateCcw className="mr-1 h-3.5 w-3.5" />
            Replay
          </Button>
        }
      />
      <Row
        title="Clear batch history"
        description="Wipes the 24h batch-undo log in this browser. Other devices are unaffected."
        button={
          <Button
            variant="secondary"
            size="sm"
            onClick={() => setConfirm('clearBatchHistory')}
          >
            <Trash2 className="mr-1 h-3.5 w-3.5" />
            Clear
          </Button>
        }
      />

      <ConfirmDialog
        isOpen={confirm === 'replayOnboarding'}
        onClose={() => setConfirm(null)}
        onConfirm={replayOnboarding}
        title="Replay onboarding?"
        message="The onboarding wizard will appear again on your next navigation. Existing tasks, habits, and projects are unaffected."
        confirmLabel="Replay"
        loading={busy}
      />
      <ConfirmDialog
        isOpen={confirm === 'clearBatchHistory'}
        onClose={() => setConfirm(null)}
        onConfirm={clearBatchHistory}
        title="Clear batch history?"
        message="Recent batch commands won't be undoable from Settings anymore. The 30-second toast undo on any in-flight batch still works."
        confirmLabel="Clear"
        variant="danger"
      />
    </div>
  );
}

function Row({
  title,
  description,
  button,
}: {
  title: string;
  description: string;
  button: React.ReactNode;
}) {
  return (
    <div className="flex items-start justify-between gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3">
      <div>
        <p className="text-sm font-medium text-[var(--color-text-primary)]">
          {title}
        </p>
        <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
          {description}
        </p>
      </div>
      {button}
    </div>
  );
}
