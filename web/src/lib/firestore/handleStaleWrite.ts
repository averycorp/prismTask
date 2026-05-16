import { toast } from 'sonner';
import type { SafeMergeResult } from './safeMergeDoc';

/**
 * Canonical UI glue for the `{ ok: false, reason: 'stale' }` branch of
 * [safeMergeDoc]. Surfaces a `sonner` toast informing the user the
 * write was rejected because the remote got there first, then hands
 * the freshly-read remote doc to a Zustand-store reducer so the local
 * view reconciles with what Android wrote.
 *
 * Callers should NOT retry the write — the user's edit is gone; they
 * see the toast and re-apply if they still want the change. This is
 * intentional: silent retry would re-introduce the exact race the
 * guard was protecting against.
 *
 * Usage:
 *   const result = await safeMergeDoc<Task>(ref, patch, expected);
 *   if (!result.ok) {
 *     handleStaleWrite(result, (remote) =>
 *       useTaskStore.getState().applyRemote(taskId, remote),
 *     );
 *     return;
 *   }
 */
export function handleStaleWrite<T>(
  result: Extract<SafeMergeResult<T>, { ok: false }>,
  refreshLocalStore: (remote: T) => void,
  options: { toastMessage?: string } = {},
): void {
  toast.message(
    options.toastMessage ?? 'Stale write — refreshed from server.',
  );
  try {
    refreshLocalStore(result.remote);
  } catch (err) {
    // A Zustand reducer throwing here would otherwise propagate out of
    // an `await safeMergeDoc()` site that's already in the "the write
    // failed" branch — confusing the caller and double-surfacing the
    // failure. Swallow + log.
    console.error('[handleStaleWrite] refreshLocalStore threw', err);
  }
}
