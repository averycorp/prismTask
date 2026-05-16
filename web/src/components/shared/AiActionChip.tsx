/**
 * AI Coach action chip — the per-message chip rendered under an assistant
 * bubble (Mark Complete / Reschedule / Just Drop It / Preview Batch / ...).
 *
 * Idempotency: the parent passes an `actionsInFlight` set keyed by the chip's
 * stable signature (see `actionSignature` in `@/features/chat/chatActions`).
 * A chip whose signature is in the set is rendered disabled, blocking a
 * second tap from re-firing the same backend mutation. Mirrors Android
 * `ChatScreen.kt:264-292` — the in-flight set is held by the ViewModel and
 * cleared once the action's `Result.flow` emits.
 *
 * Extracted from `ChatScreen.tsx` so the chip styling + idempotency logic
 * lives in one place and can be reused from any future chat surface (e.g.
 * the coach-from-task drawer in PR-5).
 */
import type { ChatActionPayload } from '@/types/chat';
import {
  actionLabel,
  actionSignature,
} from '@/features/chat/chatActions';

export interface AiActionChipProps {
  action: ChatActionPayload;
  /** Set of signatures whose actions are currently in-flight. */
  disabledSignatures: Set<string>;
  onClick: (action: ChatActionPayload) => void;
}

export function AiActionChip({
  action,
  disabledSignatures,
  onClick,
}: AiActionChipProps) {
  const sig = actionSignature(action);
  const disabled = sig != null && disabledSignatures.has(sig);
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={() => onClick(action)}
      className="rounded-full bg-[var(--color-accent)]/15 px-3 py-1 text-xs font-medium text-[var(--color-accent)] transition hover:bg-[var(--color-accent)]/25 disabled:cursor-not-allowed disabled:opacity-50"
    >
      {actionLabel(action)}
    </button>
  );
}
