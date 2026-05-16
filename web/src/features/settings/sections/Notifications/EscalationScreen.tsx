import { useEffect, useMemo, useState } from 'react';
import { Trash2, Plus } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import {
  decodeEscalationChain,
  encodeEscalationChain,
  updateProfile,
  type EscalationChain,
  type EscalationStep,
  type EscalationStepAction,
  type UrgencyTierKey,
} from '@/api/firestore/notificationProfiles';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { ESCALATION_ACTIONS, URGENCY_TIERS } from './notificationCatalog';
import type { NotificationProfile } from '@/api/firestore/notificationProfiles';

const DEFAULT_AGGRESSIVE: EscalationChain = {
  enabled: true,
  stopOnInteraction: true,
  maxAttempts: 4,
  steps: [
    { action: 'gentle', delayMs: 0, triggerTiers: [] },
    {
      action: 'standard',
      delayMs: 2 * 60 * 1000,
      triggerTiers: ['medium', 'high', 'critical'],
    },
    {
      action: 'loud',
      delayMs: 5 * 60 * 1000,
      triggerTiers: ['high', 'critical'],
    },
    {
      action: 'full_screen',
      delayMs: 10 * 60 * 1000,
      triggerTiers: ['critical'],
    },
  ],
};

/**
 * Per-profile escalation chain editor. Mirrors Android's
 * `NotificationEscalationScreen` step builder:
 *  - Toggle enabled / stop-on-interaction
 *  - Max attempts (1..10)
 *  - Per-step action (gentle / standard / loud / full_screen)
 *  - Per-step delay in minutes
 *  - Per-step trigger tier whitelist (empty = all tiers)
 *
 * Persists back to `notification_profiles[i].escalationChainJson` as
 * the wire format consumed by `NotificationProfileResolver.decodeEscalationChain`.
 */
export function EscalationScreen() {
  const profiles = useNotificationProfilesStore((s) => s.profiles);
  const profilesInitialized = useNotificationProfilesStore((s) => s.initialized);

  const [targetProfileId, setTargetProfileId] = useState<string | null>(null);
  const targetProfile = useMemo<NotificationProfile | null>(() => {
    if (profiles.length === 0) return null;
    if (targetProfileId) {
      return profiles.find((p) => p.cloudId === targetProfileId) ?? profiles[0];
    }
    return profiles[0];
  }, [profiles, targetProfileId]);

  const [chain, setChain] = useState<EscalationChain>(() =>
    decodeEscalationChain(targetProfile?.escalationChainJson ?? null),
  );

  // Re-seed when the target profile changes.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: reseed form from selected profile
    setChain(decodeEscalationChain(targetProfile?.escalationChainJson ?? null));
  }, [targetProfile?.cloudId, targetProfile?.escalationChainJson]);

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

  const handleSave = async () => {
    let uid: string | null = null;
    try {
      uid = getFirebaseUid();
    } catch {
      uid = null;
    }
    if (!uid) {
      toast.error('Sign in to save escalation chain.');
      return;
    }
    setSaving(true);
    try {
      await updateProfile(uid, targetProfile.cloudId, {
        escalationChainJson: encodeEscalationChain(chain),
        escalation: chain.enabled,
      });
      toast.success('Escalation chain saved');
    } catch (e) {
      toast.error((e as Error).message || 'Failed to save escalation chain');
    } finally {
      setSaving(false);
    }
  };

  const addStep = () => {
    setChain((c) => ({
      ...c,
      steps: [
        ...c.steps,
        { action: 'standard', delayMs: 2 * 60 * 1000, triggerTiers: [] },
      ],
    }));
  };

  const updateStep = (index: number, patch: Partial<EscalationStep>) => {
    setChain((c) => ({
      ...c,
      steps: c.steps.map((s, i) => (i === index ? { ...s, ...patch } : s)),
    }));
  };

  const deleteStep = (index: number) => {
    setChain((c) => ({
      ...c,
      steps: c.steps.filter((_, i) => i !== index),
    }));
  };

  return (
    <div className="flex flex-col gap-4">
      <ProfileSelector
        profiles={profiles}
        selected={targetProfile.cloudId}
        onChange={setTargetProfileId}
      />

      <ToggleRow
        label="Enable Escalation"
        description="Fire an increasingly intrusive sequence until interacted with"
        checked={chain.enabled}
        onChange={(enabled) => setChain((c) => ({ ...c, enabled }))}
      />
      <ToggleRow
        label="Stop on Interaction"
        description="Any tap, snooze, or dismiss cancels remaining steps"
        checked={chain.stopOnInteraction}
        onChange={(stopOnInteraction) =>
          setChain((c) => ({ ...c, stopOnInteraction }))
        }
      />

      <div>
        <label className="mb-1 block text-sm font-medium text-[var(--color-text-primary)]">
          Max Attempts
        </label>
        <input
          type="number"
          min={1}
          max={10}
          value={chain.maxAttempts}
          onChange={(e) =>
            setChain((c) => ({
              ...c,
              maxAttempts: Math.max(1, Math.min(10, parseInt(e.target.value) || 1)),
            }))
          }
          className="w-24 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        />
      </div>

      <div>
        <h3 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
          Steps
        </h3>
        {chain.steps.length === 0 ? (
          <p className="text-xs text-[var(--color-text-secondary)]">
            No steps yet — add one below.
          </p>
        ) : (
          <ul className="flex flex-col gap-3">
            {chain.steps.map((step, index) => (
              <StepRow
                key={index}
                step={step}
                onUpdate={(patch) => updateStep(index, patch)}
                onDelete={() => deleteStep(index)}
              />
            ))}
          </ul>
        )}
        <div className="mt-3 flex flex-wrap gap-2">
          <Button variant="secondary" size="sm" onClick={addStep}>
            <Plus className="h-4 w-4" /> Add Step
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => setChain(DEFAULT_AGGRESSIVE)}
          >
            Use Built-In Aggressive Chain
          </Button>
        </div>
      </div>

      <div className="flex justify-end pt-2">
        <Button onClick={handleSave} loading={saving} data-testid="escalation-save">
          Save Escalation
        </Button>
      </div>
    </div>
  );
}

function StepRow({
  step,
  onUpdate,
  onDelete,
}: {
  step: EscalationStep;
  onUpdate: (patch: Partial<EscalationStep>) => void;
  onDelete: () => void;
}) {
  return (
    <li className="rounded-lg border border-[var(--color-border)] p-3">
      <div className="flex items-center justify-between gap-2">
        <select
          value={step.action}
          onChange={(e) =>
            onUpdate({ action: e.target.value as EscalationStepAction })
          }
          className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        >
          {ESCALATION_ACTIONS.map((a) => (
            <option key={a.key} value={a.key}>
              {a.label}
            </option>
          ))}
        </select>
        <button
          type="button"
          onClick={onDelete}
          aria-label="Remove step"
          className="rounded-md p-1.5 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-red-600"
        >
          <Trash2 className="h-4 w-4" />
        </button>
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-2">
        <label className="text-xs text-[var(--color-text-secondary)]">
          Delay after previous (minutes):
        </label>
        <input
          type="number"
          min={0}
          max={60}
          value={Math.round(step.delayMs / 60_000)}
          onChange={(e) =>
            onUpdate({
              delayMs:
                Math.max(0, Math.min(60, parseInt(e.target.value) || 0)) *
                60_000,
            })
          }
          className="w-20 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        />
      </div>

      <div className="mt-2">
        <p className="text-xs text-[var(--color-text-secondary)] mb-1">
          Trigger tiers (empty = all):
        </p>
        <div className="flex flex-wrap gap-1.5">
          {URGENCY_TIERS.map((tier) => {
            const checked = step.triggerTiers.includes(tier.key);
            return (
              <button
                key={tier.key}
                type="button"
                onClick={() => {
                  const next: UrgencyTierKey[] = checked
                    ? step.triggerTiers.filter((t) => t !== tier.key)
                    : [...step.triggerTiers, tier.key];
                  onUpdate({ triggerTiers: next });
                }}
                className={`rounded-full px-2.5 py-0.5 text-xs font-medium transition-colors ${
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
    </li>
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
