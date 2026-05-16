import { useEffect, useState } from 'react';
import {
  Bell,
  Check,
  ChevronLeft,
  ChevronRight,
  Clock,
  Layers,
  Volume2,
  Vibrate,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { useAuthStore } from '@/stores/authStore';
import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import { useCustomSoundsStore } from '@/stores/customSoundsStore';
import {
  isNotificationSupported,
  requestNotificationPermission,
  getNotificationPermission,
} from '@/utils/notifications';
import { toast } from 'sonner';
import { ProfilesScreen } from './ProfilesScreen';
import { SoundScreen } from './SoundScreen';
import { VibrationScreen } from './VibrationScreen';
import { QuietHoursScreen } from './QuietHoursScreen';
import { EscalationScreen } from './EscalationScreen';

type HubView = 'index' | 'profiles' | 'sound' | 'vibration' | 'quiet' | 'escalation';

const VIEW_TITLES: Record<HubView, string> = {
  index: 'Notifications',
  profiles: 'Profiles',
  sound: 'Sounds',
  vibration: 'Vibration',
  quiet: 'Quiet Hours',
  escalation: 'Escalation',
};

/**
 * Notifications hub: replaces the single-toggle Notifications section
 * with a drilldown into Profiles / Sounds / Vibration / Quiet Hours /
 * Escalation. Mirrors Android's `NotificationsScreen` -> per-section
 * nav (`app/src/main/java/com/averycorp/prismtask/ui/screens/notifications/`).
 *
 * Why this lives inside `SettingsScreen.tsx` rather than under its own
 * route: existing settings sections are inlined (DashboardSection,
 * BoundariesSection, …) and grow the same way. Drilldown is a local
 * view state on this component so we don't fragment the route table.
 *
 * Mounts its own Firestore subscriptions for profiles + active id +
 * custom sounds. Once parity unit 2 lands those listeners in
 * `useFirestoreSync`, the local mount becomes a no-op (the store is
 * already populated).
 */
export function NotificationsHub() {
  const [view, setView] = useState<HubView>('index');
  const uid = useAuthStore((s) => s.firebaseUid);

  // Subscribe lifecycle: own the listener mount here so the hub works
  // even before parity unit 2 wires it into useFirestoreSync. Cleans
  // up on unmount / uid change.
  const subscribeToProfiles = useNotificationProfilesStore(
    (s) => s.subscribeToProfiles,
  );
  const subscribeToActiveProfileId = useNotificationProfilesStore(
    (s) => s.subscribeToActiveProfileId,
  );
  const subscribeToCustomSounds = useCustomSoundsStore(
    (s) => s.subscribeToCustomSounds,
  );
  const resetProfiles = useNotificationProfilesStore((s) => s.reset);
  const resetSounds = useCustomSoundsStore((s) => s.reset);

  useEffect(() => {
    if (!uid) {
      resetProfiles();
      resetSounds();
      return;
    }
    const unsubs: Array<() => void> = [];
    try {
      unsubs.push(subscribeToProfiles(uid));
    } catch (err) {
      console.warn('[NotificationsHub] subscribeToProfiles failed', err);
    }
    try {
      unsubs.push(subscribeToActiveProfileId(uid));
    } catch (err) {
      console.warn('[NotificationsHub] subscribeToActiveProfileId failed', err);
    }
    try {
      unsubs.push(subscribeToCustomSounds(uid));
    } catch (err) {
      console.warn('[NotificationsHub] subscribeToCustomSounds failed', err);
    }
    return () => {
      unsubs.forEach((u) => {
        try {
          u();
        } catch {
          /* swallow */
        }
      });
    };
  }, [
    uid,
    subscribeToProfiles,
    subscribeToActiveProfileId,
    subscribeToCustomSounds,
    resetProfiles,
    resetSounds,
  ]);

  if (view !== 'index') {
    return (
      <div className="flex flex-col gap-3">
        <button
          type="button"
          onClick={() => setView('index')}
          className="flex items-center gap-1 text-sm font-medium text-[var(--color-accent)] hover:underline self-start"
          data-testid="notifications-hub-back"
        >
          <ChevronLeft className="h-4 w-4" /> Back to {VIEW_TITLES.index}
        </button>
        <h2 className="text-base font-semibold text-[var(--color-text-primary)]">
          {VIEW_TITLES[view]}
        </h2>
        {view === 'profiles' && <ProfilesScreen />}
        {view === 'sound' && <SoundScreen />}
        {view === 'vibration' && <VibrationScreen />}
        {view === 'quiet' && <QuietHoursScreen />}
        {view === 'escalation' && <EscalationScreen />}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <BrowserNotificationRow />
      <p className="text-xs text-[var(--color-text-secondary)]">
        Web is settings-only. Android delivers reminders and plays
        sounds — your changes here sync to your phone.
      </p>
      <ul className="flex flex-col gap-1.5">
        <HubLink
          icon={<Layers className="h-4 w-4" />}
          title="Profiles"
          subtitle="Pick the active profile"
          onClick={() => setView('profiles')}
          testId="hub-link-profiles"
        />
        <HubLink
          icon={<Volume2 className="h-4 w-4" />}
          title="Sounds"
          subtitle="Built-in + custom uploads"
          onClick={() => setView('sound')}
          testId="hub-link-sound"
        />
        <HubLink
          icon={<Vibrate className="h-4 w-4" />}
          title="Vibration"
          subtitle="Pattern and intensity"
          onClick={() => setView('vibration')}
          testId="hub-link-vibration"
        />
        <HubLink
          icon={<Clock className="h-4 w-4" />}
          title="Quiet Hours"
          subtitle="Defer notifications within a window"
          onClick={() => setView('quiet')}
          testId="hub-link-quiet"
        />
        <HubLink
          icon={<Bell className="h-4 w-4" />}
          title="Escalation"
          subtitle="Repeat with increasing intensity"
          onClick={() => setView('escalation')}
          testId="hub-link-escalation"
        />
      </ul>
    </div>
  );
}

function HubLink({
  icon,
  title,
  subtitle,
  onClick,
  testId,
}: {
  icon: React.ReactNode;
  title: string;
  subtitle: string;
  onClick: () => void;
  testId: string;
}) {
  return (
    <li>
      <button
        type="button"
        onClick={onClick}
        data-testid={testId}
        className="flex w-full items-center gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-3 text-left transition-colors hover:border-[var(--color-accent)]/60 hover:bg-[var(--color-bg-card)]"
      >
        <span className="text-[var(--color-accent)]">{icon}</span>
        <span className="flex-1">
          <span className="block text-sm font-semibold text-[var(--color-text-primary)]">
            {title}
          </span>
          <span className="block text-xs text-[var(--color-text-secondary)]">
            {subtitle}
          </span>
        </span>
        <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" />
      </button>
    </li>
  );
}

/**
 * Browser-side notification permission opt-in — preserved from the
 * legacy Notifications section so the existing capability isn't lost.
 */
function BrowserNotificationRow() {
  if (!isNotificationSupported()) {
    return (
      <p className="text-sm text-[var(--color-text-secondary)]">
        Browser notifications are not supported in this browser.
      </p>
    );
  }
  return (
    <div className="flex flex-col gap-2 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="text-sm font-medium text-[var(--color-text-primary)]">
            Browser Notifications
          </p>
          <p className="text-xs text-[var(--color-text-secondary)]">
            Get reminders while PrismTask is open in a browser tab.
          </p>
        </div>
        <Button
          variant="secondary"
          size="sm"
          onClick={async () => {
            const perm = await requestNotificationPermission();
            if (perm === 'granted') {
              toast.success('Notifications enabled!');
            } else if (perm === 'denied') {
              toast.error(
                'Notifications blocked. Please enable them in browser settings.',
              );
            }
          }}
        >
          {getNotificationPermission() === 'granted' ? (
            <>
              <Check className="h-3.5 w-3.5" /> Enabled
            </>
          ) : (
            'Enable Notifications'
          )}
        </Button>
      </div>
      <p className="text-xs text-[var(--color-text-secondary)]">
        Note: Notifications only work while PrismTask is open in a browser
        tab. For reliable reminders, install PrismTask as an app.
      </p>
    </div>
  );
}
