import { useEffect, useMemo, useState } from 'react';
import { Sun, X } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';
import { useSettingsStore } from '@/stores/settingsStore';
import { useAdvancedTuningStore } from '@/stores/advancedTuningStore';
import { useLogicalToday } from '@/utils/useLogicalToday';
import { startOfLogicalDayMs } from '@/utils/dayBoundary';
import { shouldShowMorningCheckInBanner } from '@/lib/morningCheckInBanner';
import { useCheckInLogsStore } from '@/stores/checkInLogsStore';
import { MorningCheckInStepper } from '@/features/checkin/MorningCheckInStepper';

/**
 * Polished morning check-in banner — web port of Android's
 * `app/src/main/java/com/averycorp/prismtask/ui/screens/today/components/MorningCheckInBanner.kt`.
 *
 * Rendered above the Today task lists during the user's morning window
 * `[SoD, SoD + cutoff)`. Visibility is decided by the pure
 * `shouldShowMorningCheckInBanner` helper so behavior matches Android
 * end-to-end (no hardcoded `hour < 11` check).
 *
 * Tapping the CTA opens the existing `MorningCheckInStepper` modal —
 * the same flow surfaced by `MorningCheckInCard`. The banner stays
 * lean: it's the "you haven't logged today" nudge; the full card +
 * streak chip live below it via the existing `MorningCheckInCard`.
 *
 * Dismissed state is persisted per-logical-day in `localStorage`
 * (Android persists in `MorningCheckInPreferences.bannerDismissedDate`;
 * web stays client-only for now — the banner is a nudge, not a
 * source-of-truth, and a fresh device will re-prompt until the user
 * either checks in or hides the feature in Settings).
 */
const DISMISS_KEY = 'prismtask_morning_checkin_banner_dismissed_iso';

function readDismissedIso(): string | null {
  try {
    return localStorage.getItem(DISMISS_KEY);
  } catch {
    return null;
  }
}

function writeDismissedIso(iso: string): void {
  try {
    localStorage.setItem(DISMISS_KEY, iso);
  } catch {
    // Storage quota / disabled — non-fatal; banner re-prompts next page load.
  }
}

export function MorningCheckInBanner() {
  const featureEnabled = useSettingsStore((s) => s.showMorningCheckIn);
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  // Read the availability window (hours after SoD) from Advanced Tuning
  // prefs, synced cross-device with Android via Firestore. Mirrors
  // `MorningCheckInPromptCutoff(windowHours = 12)`.
  const windowHours = useAdvancedTuningStore(
    (s) => s.prefs.morningCheckInWindowHours,
  );
  const todayIso = useLogicalToday(startOfDayHour);

  // Pull check-in logs from the existing store. We watch the cached
  // collection rather than firing a one-off fetch so a check-in
  // submitted via the modal hides the banner without a refresh.
  const logs = useCheckInLogsStore((s) => s.logs);

  const [now, setNow] = useState(() => Date.now());
  const [dismissedIso, setDismissedIso] = useState<string | null>(() =>
    readDismissedIso(),
  );
  const [modalOpen, setModalOpen] = useState(false);

  // Re-tick once a minute so the banner self-hides when the cutoff
  // hour passes without the user reloading the page. Only arms when
  // the feature is enabled — there's nothing to update otherwise.
  useEffect(() => {
    if (!featureEnabled) return;
    const interval = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(interval);
  }, [featureEnabled]);

  const todayStart = useMemo(
    () => startOfLogicalDayMs(new Date(now), startOfDayHour),
    [now, startOfDayHour],
  );

  const alreadyCheckedInToday = useMemo(
    () => logs.some((log) => log.date_iso === todayIso),
    [logs, todayIso],
  );

  const dismissedToday = dismissedIso === todayIso;

  const visible = shouldShowMorningCheckInBanner({
    now,
    todayStart,
    windowHours,
    featureEnabled,
    alreadyCheckedInToday,
    dismissedToday,
  });

  if (!visible) return null;

  const greeting = (() => {
    const hour = new Date(now).getHours();
    if (hour < 12) return 'Good Morning';
    if (hour < 18) return 'Good Afternoon';
    return 'Good Evening';
  })();

  const onDismiss = () => {
    writeDismissedIso(todayIso);
    setDismissedIso(todayIso);
  };

  return (
    <>
      <div
        className="relative mb-4 overflow-hidden rounded-xl border border-[var(--color-accent)]/20 bg-gradient-to-br from-[var(--color-accent)]/12 to-[var(--color-accent)]/5 p-4"
        role="region"
        aria-label={`Morning check-in card. ${greeting}.`}
        data-testid="morning-checkin-banner"
      >
        <button
          type="button"
          onClick={onDismiss}
          className="absolute right-2 top-2 rounded p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-card)] hover:text-[var(--color-text-primary)]"
          aria-label="Dismiss Check-In Banner"
        >
          <X className="h-4 w-4" />
        </button>
        <div className="flex items-start gap-3 pr-6">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[var(--color-accent)]/18">
            <Sun className="h-5 w-5 text-[var(--color-accent)]" aria-hidden="true" />
          </div>
          <div className="min-w-0 flex-1">
            <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">
              {greeting}
            </h3>
            <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
              How's Your Energy? Take A Minute To Log Mood, Balance, And
              Today's Plan.
            </p>
            <div className="mt-3">
              <Button
                size="sm"
                variant="primary"
                onClick={() => setModalOpen(true)}
              >
                Start Check-In
              </Button>
            </div>
          </div>
        </div>
      </div>

      <Modal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        title="Morning Check-In"
        size="md"
      >
        <MorningCheckInStepper
          dateIso={todayIso}
          initial={null}
          onSaved={() => {
            setModalOpen(false);
          }}
          onClose={() => setModalOpen(false)}
        />
      </Modal>
    </>
  );
}
