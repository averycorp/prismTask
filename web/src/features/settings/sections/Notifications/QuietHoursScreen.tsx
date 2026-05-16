import { useState } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';
import {
  AnalogClockPicker,
  useAnalogClockState,
} from '@/components/AnalogClockPicker';
import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import {
  DAY_OF_WEEK_LABELS,
  DEFAULT_QUIET_HOURS,
  URGENCY_TIERS,
  decodeQuietHours,
  encodeQuietHours,
  type QuietHoursWindow,
  type UrgencyTierKey,
} from '@/lib/notifications/escalationChain';
import {
  CardSurface,
  NotificationsSubScreenLayout,
  SubSectionHeading,
} from './SubScreenLayout';
import { Chip, ToggleRow } from './primitives';
import { useActiveProfile } from './useActiveProfile';

/**
 * Quiet-hours editor — defers notifications during a window the user
 * declares. Uses the canonical [AnalogClockPicker] for the start/end
 * pickers per the standing time-input convention.
 */
export function QuietHoursScreen() {
  const activeProfile = useActiveProfile();
  const updateProfile = useNotificationProfilesStore((s) => s.updateProfile);
  const timeFormat = useSettingsStore((s) => s.timeFormat);

  const initial: QuietHoursWindow = activeProfile
    ? decodeQuietHours(activeProfile.quiet_hours_json)
    : DEFAULT_QUIET_HOURS;

  const [enabled, setEnabled] = useState<boolean>(initial.enabled);
  const [startHour, setStartHour] = useState<number>(initial.startHour);
  const [startMinute, setStartMinute] = useState<number>(initial.startMinute);
  const [endHour, setEndHour] = useState<number>(initial.endHour);
  const [endMinute, setEndMinute] = useState<number>(initial.endMinute);
  const [days, setDays] = useState<number[]>([...initial.days]);
  const [breakThrough, setBreakThrough] = useState<UrgencyTierKey[]>([
    ...initial.priorityOverrideTiers,
  ]);
  const [editing, setEditing] = useState<'start' | 'end' | null>(null);
  const [saving, setSaving] = useState(false);

  if (!activeProfile) {
    return (
      <NotificationsSubScreenLayout title="Quiet Hours">
        <p className="text-sm text-[var(--color-text-secondary)]">
          No profiles yet — create one on Android first.
        </p>
      </NotificationsSubScreenLayout>
    );
  }

  const isOvernight =
    startHour > endHour || (startHour === endHour && startMinute > endMinute);

  const toggleDay = (day: number) => {
    setDays((prev) =>
      prev.includes(day)
        ? prev.filter((d) => d !== day)
        : [...prev, day].sort((a, b) => a - b),
    );
  };
  const toggleTier = (tier: UrgencyTierKey) => {
    setBreakThrough((prev) =>
      prev.includes(tier) ? prev.filter((t) => t !== tier) : [...prev, tier],
    );
  };

  const handleSave = async () => {
    let uid: string;
    try {
      uid = getFirebaseUid();
    } catch {
      toast.error('Sign in to save quiet-hours preferences.');
      return;
    }
    const window: QuietHoursWindow = {
      enabled,
      startHour,
      startMinute,
      endHour,
      endMinute,
      days,
      priorityOverrideTiers: breakThrough,
    };
    setSaving(true);
    try {
      await updateProfile(uid, activeProfile.cloud_id, {
        quietHoursJson: encodeQuietHours(window),
      });
      toast.success('Quiet hours saved.');
    } catch (err) {
      toast.error(
        `Could not save: ${(err as Error).message ?? 'unknown error'}`,
      );
    } finally {
      setSaving(false);
    }
  };

  return (
    <NotificationsSubScreenLayout
      title="Quiet Hours"
      subtitle={`Active profile: ${activeProfile.name}`}
    >
      <CardSurface>
        <ToggleRow
          label="Enable Quiet Hours"
          description="Defer notifications until the window closes."
          checked={enabled}
          onChange={setEnabled}
        />
      </CardSurface>

      {enabled && (
        <>
          <SubSectionHeading>Window</SubSectionHeading>
          <CardSurface>
            <TimeRow
              label="Starts At"
              hour={startHour}
              minute={startMinute}
              is24Hour={timeFormat === '24h'}
              onClick={() => setEditing('start')}
            />
            <TimeRow
              label="Ends At"
              hour={endHour}
              minute={endMinute}
              is24Hour={timeFormat === '24h'}
              onClick={() => setEditing('end')}
            />
            <p className="mt-2 text-xs text-[var(--color-text-secondary)]">
              {isOvernight
                ? 'Overnight window — starts today and ends tomorrow.'
                : 'Same-day window.'}
            </p>
          </CardSurface>

          <SubSectionHeading>Days</SubSectionHeading>
          <div className="mb-2 flex flex-wrap gap-2">
            {DAY_OF_WEEK_LABELS.map((d) => (
              <Chip
                key={d.day}
                label={d.short}
                selected={days.includes(d.day)}
                onClick={() => toggleDay(d.day)}
              />
            ))}
          </div>

          <SubSectionHeading>Break-Through Allowlist</SubSectionHeading>
          <p className="mb-2 text-xs text-[var(--color-text-secondary)]">
            Tiers selected here still fire during quiet hours. Keep High
            and Critical on so medication doses and time-sensitive
            reminders aren't silenced.
          </p>
          <div className="mb-2 flex flex-wrap gap-2">
            {URGENCY_TIERS.map((t) => (
              <Chip
                key={t.key}
                label={t.label}
                selected={breakThrough.includes(t.key)}
                onClick={() => toggleTier(t.key)}
              />
            ))}
          </div>
        </>
      )}

      <div className="mt-5 flex justify-end gap-2">
        <Button onClick={handleSave} loading={saving}>
          Save
        </Button>
      </div>

      {editing && (
        <ClockPickerModal
          title={editing === 'start' ? 'Quiet Hours Start' : 'Quiet Hours End'}
          initialHour={editing === 'start' ? startHour : endHour}
          initialMinute={editing === 'start' ? startMinute : endMinute}
          is24Hour={timeFormat === '24h'}
          onClose={() => setEditing(null)}
          onSave={(h, m) => {
            if (editing === 'start') {
              setStartHour(h);
              setStartMinute(m);
            } else {
              setEndHour(h);
              setEndMinute(m);
            }
            setEditing(null);
          }}
        />
      )}
    </NotificationsSubScreenLayout>
  );
}

function ClockPickerModal({
  title,
  initialHour,
  initialMinute,
  is24Hour,
  onClose,
  onSave,
}: {
  title: string;
  initialHour: number;
  initialMinute: number;
  is24Hour: boolean;
  onClose: () => void;
  onSave: (hour: number, minute: number) => void;
}) {
  const api = useAnalogClockState({
    initialHour,
    initialMinute,
    is24Hour,
  });
  return (
    <Modal isOpen onClose={onClose} title={title} size="sm">
      <div className="flex flex-col items-center gap-4">
        <AnalogClockPicker api={api} />
        <div className="flex w-full justify-end gap-2 pt-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => onSave(api.state.hour, api.state.minute)}>
            Set
          </Button>
        </div>
      </div>
    </Modal>
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
      className="flex w-full items-center justify-between py-2 text-left"
    >
      <p className="text-sm font-medium text-[var(--color-text-primary)]">
        {label}
      </p>
      <p className="font-mono text-base font-semibold text-[var(--color-accent)]">
        {formatTime(hour, minute, is24Hour)}
      </p>
    </button>
  );
}

function formatTime(hour: number, minute: number, is24Hour: boolean): string {
  const m = minute.toString().padStart(2, '0');
  if (is24Hour) return `${hour.toString().padStart(2, '0')}:${m}`;
  const suffix = hour < 12 ? 'AM' : 'PM';
  const display = hour === 0 ? 12 : hour > 12 ? hour - 12 : hour;
  return `${display}:${m} ${suffix}`;
}
