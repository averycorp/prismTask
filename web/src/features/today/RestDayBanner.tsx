import { useState } from 'react';
import { Moon } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useRestDayStore } from '@/stores/restDayStore';

/**
 * Rest-Day surface on the Today screen. Web port of Android's
 * `ui/screens/today/components/RestDayBanner.kt`. Two states:
 *
 *  - **Resting today** — the user has marked today as a rest day. We
 *    render a soft full-width takeover header replacing the dense task
 *    list, with copy *"Resting today — see you tomorrow."* + secondary
 *    line *"Habit streaks stay safe."* + an "End Rest Day" button.
 *  - **Default** — render a small "Mark today as a rest day"
 *    affordance. Tapping opens the confirmation dialog mirrored from
 *    Android's `RestDayConfirmDialog` (`docs/REST_DAY.md:162–164`).
 *
 * Copy is intentionally descriptive and non-clinical (see
 * `docs/REST_DAY.md` § *Copy guidelines*): the surface states what is
 * happening and never tells the user what they should do. Web doesn't
 * fire the non-medication notifications Android pauses via
 * `RestDayGate`, so we trim the secondary line to just the streak
 * claim — the notification-pause half is web-irrelevant.
 *
 * The dialog uses "Yes, rest today" / "Not yet" (instead of
 * "Confirm" / "Cancel") per `REST_DAY.md:162–164` — "Not yet" is
 * softer than "Cancel" and preserves the rest-day option without
 * framing the dismissal as rejection.
 */
export function RestDayBanner() {
  const isResting = useRestDayStore((s) => s.isRestDayToday());
  const markToday = useRestDayStore((s) => s.markToday);
  const unmarkToday = useRestDayStore((s) => s.unmarkToday);

  const [confirmOpen, setConfirmOpen] = useState(false);

  if (isResting) {
    return (
      <div
        className="mb-4 overflow-hidden rounded-xl border border-[var(--color-accent)]/20 bg-gradient-to-br from-[var(--color-accent)]/10 to-[var(--color-accent)]/[0.04]"
        aria-label="Resting today. Habit streaks stay safe."
        role="region"
      >
        <div className="flex items-start gap-4 p-5">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-[var(--color-accent)]/15">
            <Moon
              className="h-5 w-5 text-[var(--color-accent)]"
              aria-hidden="true"
            />
          </div>
          <div className="min-w-0 flex-1">
            <h3 className="text-base font-semibold text-[var(--color-text-primary)]">
              Resting Today — See You Tomorrow.
            </h3>
            <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
              Habit streaks stay safe.
            </p>
            <div className="mt-3">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  void unmarkToday();
                }}
              >
                End Rest Day
              </Button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <>
      <div className="mb-3 flex items-center justify-end">
        <button
          type="button"
          onClick={() => setConfirmOpen(true)}
          className="inline-flex items-center gap-1.5 rounded-full border border-[var(--color-border)] px-3 py-1 text-xs text-[var(--color-text-secondary)] transition-colors hover:border-[var(--color-accent)]/40 hover:text-[var(--color-text-primary)]"
          aria-label="Mark today as a rest day"
        >
          <Moon className="h-3.5 w-3.5" aria-hidden="true" />
          Mark Today as a Rest Day
        </button>
      </div>

      <ConfirmDialog
        isOpen={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={() => {
          void markToday();
          setConfirmOpen(false);
        }}
        title="Mark Today as a Rest Day?"
        message="Habit streaks won't break. Tasks scheduled for today stay where they are."
        confirmLabel="Yes, Rest Today"
        cancelLabel="Not Yet"
      />
    </>
  );
}
