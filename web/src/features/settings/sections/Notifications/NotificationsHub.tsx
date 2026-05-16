import { Link } from 'react-router-dom';
import {
  Bell,
  ChevronRight,
  Layers,
  Music2,
  Vibrate,
  Moon,
  Zap,
} from 'lucide-react';
import { useActiveProfile } from './useActiveProfile';

/**
 * Notifications Hub — the entry screen for the per-profile notification
 * customizer. Mirrors Android's `NotificationsScreen.kt` sub-section
 * fan-out (Profiles / Sound / Vibration / Quiet Hours / Escalation).
 *
 * Each sub-screen edits the currently-active profile, so the hub
 * surfaces which profile is active up front — without that hint, users
 * have no way to tell that "Sound" / "Vibration" / etc. edit a single
 * profile rather than a global setting.
 */
export function NotificationsHub() {
  const activeProfile = useActiveProfile();

  return (
    <div className="mx-auto max-w-2xl">
      <div className="mb-6 flex items-center gap-3">
        <Bell className="h-7 w-7 text-[var(--color-accent)]" />
        <div className="flex-1">
          <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
            Notifications
          </h1>
          <p className="text-sm text-[var(--color-text-secondary)]">
            Tune how PrismTask sounds, buzzes, and escalates.
          </p>
        </div>
      </div>

      <div className="mb-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
        <p className="text-xs font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">
          Active Profile
        </p>
        <p className="mt-1 text-lg font-semibold text-[var(--color-text-primary)]">
          {activeProfile?.name ?? 'No Profile Selected'}
        </p>
        <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
          Sound, vibration, quiet hours, and escalation edits below apply
          to this profile. Switch profiles in the Profiles screen.
        </p>
      </div>

      <NavRow
        to="/settings/notifications/profiles"
        icon={<Layers className="h-5 w-5" />}
        title="Profiles"
        description="Switch between named delivery bundles."
      />
      <NavRow
        to="/settings/notifications/sound"
        icon={<Music2 className="h-5 w-5" />}
        title="Sound"
        description="Pick the tone, volume, and fade for the active profile."
      />
      <NavRow
        to="/settings/notifications/vibration"
        icon={<Vibrate className="h-5 w-5" />}
        title="Vibration"
        description="Choose a pattern, intensity, and repetition."
      />
      <NavRow
        to="/settings/notifications/quiet-hours"
        icon={<Moon className="h-5 w-5" />}
        title="Quiet Hours"
        description="Defer notifications during a recurring window."
      />
      <NavRow
        to="/settings/notifications/escalation"
        icon={<Zap className="h-5 w-5" />}
        title="Escalation Chain"
        description="Step up intrusiveness when a reminder is ignored."
      />
    </div>
  );
}

function NavRow({
  to,
  icon,
  title,
  description,
}: {
  to: string;
  icon: React.ReactNode;
  title: string;
  description: string;
}) {
  return (
    <Link
      to={to}
      className="mb-2 flex items-center gap-3 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4 transition-colors hover:border-[var(--color-accent)]/60 hover:bg-[var(--color-bg-secondary)]"
    >
      <span className="text-[var(--color-accent)]">{icon}</span>
      <div className="flex-1">
        <p className="text-sm font-semibold text-[var(--color-text-primary)]">
          {title}
        </p>
        <p className="text-xs text-[var(--color-text-secondary)]">
          {description}
        </p>
      </div>
      <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" />
    </Link>
  );
}
