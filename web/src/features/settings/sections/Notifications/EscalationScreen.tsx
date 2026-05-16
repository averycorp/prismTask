import { useState } from 'react';
import { Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import {
  DEFAULT_AGGRESSIVE_ESCALATION_CHAIN,
  DEFAULT_ESCALATION_CHAIN,
  ESCALATION_STEP_ACTIONS,
  URGENCY_TIERS,
  decodeEscalationChain,
  encodeEscalationChain,
  type EscalationChain,
  type EscalationStep,
  type EscalationStepAction,
  type UrgencyTierKey,
} from '@/lib/notifications/escalationChain';
import {
  CardSurface,
  NotificationsSubScreenLayout,
  SubSectionHeading,
} from './SubScreenLayout';
import { Chip, SliderRow, ToggleRow } from './primitives';
import { useActiveProfile } from './useActiveProfile';

/**
 * Per-profile escalation-chain editor. Describes a sequence of
 * increasingly-intrusive steps that fire if a reminder is left
 * unattended. The wire format is the JSON encoded by
 * `encodeEscalationChain` — Android decodes the same shape via the
 * `NotificationProfileResolver` codec.
 */
export function EscalationScreen() {
  const activeProfile = useActiveProfile();
  const updateProfile = useNotificationProfilesStore((s) => s.updateProfile);

  const initial: EscalationChain = activeProfile
    ? decodeEscalationChain(activeProfile.escalation_chain_json)
    : DEFAULT_ESCALATION_CHAIN;

  const [chain, setChain] = useState<EscalationChain>(initial);
  const [saving, setSaving] = useState(false);

  if (!activeProfile) {
    return (
      <NotificationsSubScreenLayout title="Escalation Chain">
        <p className="text-sm text-[var(--color-text-secondary)]">
          No profiles yet — create one on Android first.
        </p>
      </NotificationsSubScreenLayout>
    );
  }

  const updateStep = (index: number, patch: Partial<EscalationStep>) =>
    setChain((c) => ({
      ...c,
      steps: c.steps.map((s, i) => (i === index ? { ...s, ...patch } : s)),
    }));

  const removeStep = (index: number) =>
    setChain((c) => ({
      ...c,
      steps: c.steps.filter((_, i) => i !== index),
    }));

  const addStep = () =>
    setChain((c) => ({
      ...c,
      steps: [
        ...c.steps,
        { action: 'standard', delayMs: 2 * 60 * 1000, triggerTiers: [] },
      ],
    }));

  const handleSave = async () => {
    let uid: string;
    try {
      uid = getFirebaseUid();
    } catch {
      toast.error('Sign in to save escalation preferences.');
      return;
    }
    setSaving(true);
    try {
      await updateProfile(uid, activeProfile.cloud_id, {
        escalationChainJson: encodeEscalationChain(chain),
        escalation: chain.enabled,
      });
      toast.success('Escalation chain saved.');
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
      title="Escalation Chain"
      subtitle={`Active profile: ${activeProfile.name}`}
    >
      <CardSurface>
        <ToggleRow
          label="Enable Escalation"
          description="Fire an increasingly intrusive sequence until interacted with."
          checked={chain.enabled}
          onChange={(v) => setChain((c) => ({ ...c, enabled: v }))}
        />
        <ToggleRow
          label="Stop on Interaction"
          description="Any tap, snooze, or dismiss cancels remaining steps."
          checked={chain.stopOnInteraction}
          onChange={(v) => setChain((c) => ({ ...c, stopOnInteraction: v }))}
        />
        <SliderRow
          label="Max Attempts"
          value={chain.maxAttempts}
          min={1}
          max={10}
          step={1}
          format={(v) => `${Math.round(v)}`}
          onChange={(v) =>
            setChain((c) => ({ ...c, maxAttempts: Math.round(v) }))
          }
        />
      </CardSurface>

      <SubSectionHeading>Steps</SubSectionHeading>
      <p className="mb-2 text-xs text-[var(--color-text-secondary)]">
        Each step picks how loudly that attempt fires:
        <br />· Gentle Ping — quiet sound, no vibration
        <br />· Standard Alert — the profile's normal sound + vibration
        <br />· Louder + Vibrate — stronger pattern, may bypass ringer
        <br />· Full-Screen Takeover — fills the lock screen
      </p>

      {chain.steps.length === 0 && (
        <CardSurface>
          <p className="text-sm text-[var(--color-text-secondary)]">
            No steps yet — add one below.
          </p>
        </CardSurface>
      )}

      {chain.steps.map((step, index) => (
        <StepCard
          key={index}
          step={step}
          index={index}
          onUpdate={(patch) => updateStep(index, patch)}
          onRemove={() => removeStep(index)}
        />
      ))}

      <div className="mb-2 flex flex-col gap-2">
        <Button variant="secondary" onClick={addStep}>
          Add Step
        </Button>
        <Button
          variant="secondary"
          onClick={() => setChain(DEFAULT_AGGRESSIVE_ESCALATION_CHAIN)}
        >
          Use Built-In Aggressive Chain
        </Button>
      </div>

      <div className="mt-5 flex justify-end gap-2">
        <Button onClick={handleSave} loading={saving}>
          Save
        </Button>
      </div>
    </NotificationsSubScreenLayout>
  );
}

function StepCard({
  step,
  index,
  onUpdate,
  onRemove,
}: {
  step: EscalationStep;
  index: number;
  onUpdate: (patch: Partial<EscalationStep>) => void;
  onRemove: () => void;
}) {
  return (
    <div className="mb-3 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <div className="mb-2 flex items-center justify-between">
        <p className="text-sm font-semibold text-[var(--color-text-primary)]">
          Step {index + 1}: {actionLabel(step.action)}
        </p>
        <button
          type="button"
          onClick={onRemove}
          aria-label={`Remove step ${index + 1}`}
          className="rounded-full p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-red-500"
        >
          <Trash2 className="h-4 w-4" />
        </button>
      </div>

      <div className="mb-2 flex flex-wrap gap-2">
        {ESCALATION_STEP_ACTIONS.map((a) => (
          <Chip
            key={a.key}
            label={a.label}
            selected={step.action === a.key}
            onClick={() => onUpdate({ action: a.key })}
          />
        ))}
      </div>

      <SliderRow
        label="Delay After Previous Step"
        value={Math.min(60, Math.round(step.delayMs / 60_000))}
        min={0}
        max={60}
        step={1}
        format={(v) => `${Math.round(v)} min`}
        onChange={(v) => onUpdate({ delayMs: Math.round(v) * 60_000 })}
      />

      <p className="mt-2 mb-1 text-xs font-medium text-[var(--color-text-secondary)]">
        Trigger Tiers
      </p>
      <p className="mb-2 text-[10px] text-[var(--color-text-secondary)]">
        Empty = applies to all tiers.
      </p>
      <div className="flex flex-wrap gap-2">
        {URGENCY_TIERS.map((tier) => {
          const enabled = step.triggerTiers.includes(tier.key);
          return (
            <Chip
              key={tier.key}
              label={tier.label}
              selected={enabled || step.triggerTiers.length === 0}
              onClick={() =>
                onUpdate({
                  triggerTiers: toggleTier(step.triggerTiers, tier.key),
                })
              }
            />
          );
        })}
      </div>
    </div>
  );
}

function actionLabel(action: EscalationStepAction): string {
  return ESCALATION_STEP_ACTIONS.find((a) => a.key === action)?.label ?? action;
}

function toggleTier(
  tiers: UrgencyTierKey[],
  key: UrgencyTierKey,
): UrgencyTierKey[] {
  return tiers.includes(key)
    ? tiers.filter((t) => t !== key)
    : [...tiers, key];
}
