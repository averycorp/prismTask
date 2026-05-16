import { useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import {
  AnalogClockPicker,
  useAnalogClockState,
} from '@/components/AnalogClockPicker';
import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import { useSettingsStore } from '@/stores/settingsStore';
import {
  decodeQuietHours,
  encodeQuietHours,
  isOvernightWindow,
  updateProfile,
  type QuietHoursWindow,
  type UrgencyTierKey,
} from '@/api/firestore/notificationProfiles';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { DAY_OF_WEEK_OPTIONS, URGENCY_TIERS } from './notificationCatalog';
import type { NotificationProfile } from '@/api/firestore/notificationProfiles';

/**
 * Quiet hours editor for the active (or user-selected) profile.
 *
 * Time inputs use the canonical AnalogClockPicker — per the
 * `feedback-time-input-use-clock-not-slider` memory, every time field
 * renders as a 3-hand dial, never as a slider. Persists back to
 * `notification_profiles[i].quietHoursJson` as the Android wire
 * format produced by `NotificationProfileResolver.encodeQuietHours`.
 */
export function QuietHoursScreen() {
  const profiles = useNotificationProfilesStore((s) => s.profiles);
  const profilesInitialized = useNotificationProfilesStore((s) => s.initialized);
  const timeFormat = useSettingsStore((s) => s.timeFormat);
  const is24Hour = timeFormat === '24h';

  const [targetProfileId, setTargetProfileId] = useState<string | null>(null);
  const targetProfile = useMemo<NotificationProfile | null>(() => {
    if (profiles.length === 0) return null;
    if (targetProfileId) {
      return profiles.find((p) => p.cloudId === targetProfileId) ?? profiles[0];
    }
    return profiles[0];
  }, [profiles, targetProfileId]);

  const [window, setWindow] = useState<QuietHoursWindow>(() =>
    decodeQuietHours(targetProfile?.quietHoursJson ?? null),
  );

  // Re-seed local state when the user switches profile target.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: reseed local form from selected profile
    setWindow(decodeQuietHours(targetProfile?.quietHoursJson ?? null));
  }, [targetProfile?.cloudId, targetProfile?.quietHoursJson]);

  const [pickerOpen, setPickerOpen] = useState<'start' | 'end' | null>(null);
  const [saving, setSaving] = useState(false);

  if (!profilesInitialized) {
    return <EmptyState message="Loading profiles…" />;
  }
  if (!targetProfile) {
    return (
      <EmptyState
        message="No notification profiles to configure"
        hint="Create a profile on Android first."
      />
    );
  }

  const toggleDay = (key: string) => {
    setWindow((w) => {
      const has = w.days.includes(key);
      return {
        ...w,
        days: has ? w.days.filter((d) => d !== key) : [...w.days, key],
      };
    });
  };

  const toggleTier = (tier: UrgencyTierKey) => {
    setWindow((w) => {
      const has = w.priorityOverrideTiers.includes(tier);
      return {
        ...w,
        priorityOverrideTiers: has
          ? w.priorityOverrideTiers.filter((t) => t !== tier)
          : [...w.priorityOverrideTiers, tier],
      };
    });
  };

  const handleSave = async () => {
    let uid: string | null = null;
    try {
      uid = getFirebaseUid();
    } catch {
      uid = null;
    }
    if (!uid) {
      toast.error('Sign in to save quiet hours.');
      return;
    }
    setSaving(true);
    try {
      await updateProfile(uid, targetProfile.cloudId, {
        quietHoursJson: encodeQuietHours(window),
      });
      toast.success('Quiet hours saved');
    } catch (e) {
      toast.error((e as Error).message || 'Failed to save quiet hours');
    } finally {
      setSaving(false);
    }
  };

  const overnight = isOvernightWindow(window);

  return (
    <div className="flex flex-col gap-4">
      <ProfileSelector
        profiles={profiles}
        selected={targetProfile.cloudId}
        onChange={setTargetProfileId}
      />

      <ToggleRow
        label="Enable Quiet Hours"
        description="Defer notifications to the end of the window"
        checked={window.enabled}
        onChange={(enabled) => setWindow((w) => ({ ...w, enabled }))}
      />

      {window.enabled && (
        <>
          <div>
            <h3 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
              Window
            </h3>
            <div className="flex flex-col gap-2">
              <TimeRow
                label="Starts At"
                hour={window.startHour}
                minute={window.startMinute}
                is24Hour={is24Hour}
                onClick={() => setPickerOpen('start')}
              />
              <TimeRow
                label="Ends At"
                hour={window.endHour}
                minute={window.endMinute}
                is24Hour={is24Hour}
                onClick={() => setPickerOpen('end')}
              />
              <p className="text-xs text-[var(--color-text-secondary)]">
                {overnight
                  ? `Overnight window — starts at ${formatHm(window.startHour, window.startMinute, is24Hour)} today and ends at ${formatHm(window.endHour, window.endMinute, is24Hour)} tomorrow.`
                  : 'Same-day window.'}
              </p>
            </div>
          </div>

          <div>
            <h3 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
              Active Days
            </h3>
            <div className="flex flex-wrap gap-1.5">
              {DAY_OF_WEEK_OPTIONS.map((day) => {
                const checked = window.days.includes(day.key);
                return (
                  <button
                    key={day.key}
                    type="button"
                    onClick={() => toggleDay(day.key)}
                    className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                      checked
                        ? 'bg-[var(--color-accent)] text-white'
                        : 'border border-[var(--color-border)] text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]'
                    }`}
                  >
                    {day.label}
                  </button>
                );
              })}
            </div>
          </div>

          <div>
            <h3 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
              Break-Through Allowlist
            </h3>
            <p className="mb-2 text-xs text-[var(--color-text-secondary)]">
              Urgency tiers that can still fire during quiet hours.
              Allow High and Critical so medication doses and
              time-sensitive reminders aren't silenced.
            </p>
            <div className="flex flex-wrap gap-1.5">
              {URGENCY_TIERS.map((tier) => {
                const checked = window.priorityOverrideTiers.includes(tier.key);
                return (
                  <button
                    key={tier.key}
                    type="button"
                    onClick={() => toggleTier(tier.key)}
                    className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                      checked
                        ? 'bg-[var(--color-accent)] text-white'
                        : 'border border-[var(--color-border)] text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]'
                    }`}
                  >
                    {tier.label}
                  </button>
                );
              })}
            </div>
          </div>
        </>
      )}

      <div className="flex justify-end pt-2">
        <Button onClick={handleSave} loading={saving} data-testid="quiet-hours-save">
          Save Quiet Hours
        </Button>
      </div>

      {pickerOpen && (
        <QuietHoursTimePickerModal
          isOpen={true}
          title={pickerOpen === 'start' ? 'Quiet Hours Start' : 'Quiet Hours End'}
          initialHour={
            pickerOpen === 'start' ? window.startHour : window.endHour
          }
          initialMinute={
            pickerOpen === 'start' ? window.startMinute : window.endMinute
          }
          is24Hour={is24Hour}
          onClose={() => setPickerOpen(null)}
          onSave={(hour, minute) => {
            if (pickerOpen === 'start') {
              setWindow((w) => ({ ...w, startHour: hour, startMinute: minute }));
            } else {
              setWindow((w) => ({ ...w, endHour: hour, endMinute: minute }));
            }
            setPickerOpen(null);
          }}
        />
      )}
    </div>
  );
}

function ProfileSelector({
  profiles,
  selected,
  onChange,
}: {
  profiles: ReadonlyArray<NotificationProfile>;
  selected: string;
  onChange: (id: string) => void;
}) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
        Profile
      </label>
      <select
        value={selected}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
      >
        {profiles.map((p) => (
          <option key={p.cloudId} value={p.cloudId}>
            {p.name || 'Untitled Profile'}
          </option>
        ))}
      </select>
    </div>
  );
}

function ToggleRow({
  label,
  description,
  checked,
  onChange,
}: {
  label: string;
  description?: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between py-2">
      <div>
        <p className="text-sm font-medium text-[var(--color-text-primary)]">
          {label}
        </p>
        {description && (
          <p className="text-xs text-[var(--color-text-secondary)]">
            {description}
          </p>
        )}
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full transition-colors ${
          checked ? 'bg-[var(--color-accent)]' : 'bg-[var(--color-border)]'
        }`}
      >
        <span
          className={`inline-block h-5 w-5 transform rounded-full bg-white shadow transition-transform ${
            checked ? 'translate-x-[22px]' : 'translate-x-0.5'
          } mt-0.5`}
        />
      </button>
    </div>
  );
}

function TimeRow({
  label,
  hour,
  minute,
  is24Hour,
  onClick,
}: {
  label: string;
  hour: number;
  minute: number;
  is24Hour: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-center justify-between rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-3 text-left hover:border-[var(--color-accent)]/60 hover:bg-[var(--color-bg-card)] focus:border-[var(--color-accent)] focus:outline-none"
    >
      <span className="text-sm font-medium text-[var(--color-text-primary)]">
        {label}
      </span>
      <span className="font-mono text-sm font-semibold text-[var(--color-text-primary)]">
        {formatHm(hour, minute, is24Hour)}
      </span>
    </button>
  );
}

function QuietHoursTimePickerModal({
  isOpen,
  title,
  initialHour,
  initialMinute,
  is24Hour,
  onClose,
  onSave,
}: {
  isOpen: boolean;
  title: string;
  initialHour: number;
  initialMinute: number;
  is24Hour: boolean;
  onClose: () => void;
  onSave: (hour: number, minute: number) => void;
}) {
  if (!isOpen) return null;
  return (
    <Modal isOpen={isOpen} onClose={onClose} title={title} size="sm">
      <QuietHoursTimePickerBody
        initialHour={initialHour}
        initialMinute={initialMinute}
        is24Hour={is24Hour}
        onCancel={onClose}
        onSave={onSave}
      />
    </Modal>
  );
}

function QuietHoursTimePickerBody({
  initialHour,
  initialMinute,
  is24Hour,
  onCancel,
  onSave,
}: {
  initialHour: number;
  initialMinute: number;
  is24Hour: boolean;
  onCancel: () => void;
  onSave: (hour: number, minute: number) => void;
}) {
  const api = useAnalogClockState({
    initialHour,
    initialMinute,
    initialSecond: 0,
    is24Hour,
  });
  return (
    <div className="flex flex-col items-center gap-4">
      <AnalogClockPicker api={api} />
      <div className="flex w-full justify-end gap-2 pt-2">
        <Button variant="secondary" onClick={onCancel}>
          Cancel
        </Button>
        <Button onClick={() => onSave(api.state.hour, api.state.minute)}>
          Set
        </Button>
      </div>
    </div>
  );
}

function EmptyState({ message, hint }: { message: string; hint?: string }) {
  return (
    <div className="rounded-lg border border-dashed border-[var(--color-border)] p-6 text-center">
      <p className="text-sm font-medium text-[var(--color-text-primary)]">
        {message}
      </p>
      {hint && (
        <p className="mt-1 text-xs text-[var(--color-text-secondary)]">{hint}</p>
      )}
    </div>
  );
}

export function formatHm(hour: number, minute: number, is24Hour: boolean): string {
  const mm = minute.toString().padStart(2, '0');
  if (is24Hour) {
    return `${hour.toString().padStart(2, '0')}:${mm}`;
  }
  const displayHour = hour === 0 ? 12 : hour > 12 ? hour - 12 : hour;
  const suffix = hour < 12 ? 'AM' : 'PM';
  return `${displayHour}:${mm} ${suffix}`;
}
