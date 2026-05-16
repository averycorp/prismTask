import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Loader2, Plus, SlidersHorizontal, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import {
  createRule,
  deleteRule,
  updateRule,
  type BoundaryRule,
  type BoundaryRuleType,
} from '@/api/firestore/boundaryRules';
import { useBoundaryRulesStore } from '@/stores/boundaryRulesStore';
import { getFirebaseUid } from '@/stores/firebaseUid';

const RULE_OPTIONS: { type: BoundaryRuleType; label: string; hint: string }[] = [
  {
    type: 'daily_task_cap',
    label: 'Daily task cap',
    hint: 'Max active tasks per day. Warn banner fires when crossed.',
  },
  {
    type: 'work_hours_window',
    label: 'Work hours window',
    hint: 'Declared start/end hour (0–23). Info nudge fires outside it.',
  },
  {
    type: 'weekly_hour_budget',
    label: 'Weekly hour budget',
    hint: 'Informational — feeds the burnout score.',
  },
];

export function BoundariesSection() {
  // Read directly from the live store. The Firestore listener wired
  // by useFirestoreSync keeps this in sync across devices, so a rule
  // added on the Android client surfaces here without a page refresh
  // (parity audit § A.1b).
  const rules = useBoundaryRulesStore((s) => s.rules);
  const [newType, setNewType] = useState<BoundaryRuleType>('daily_task_cap');
  const [newValue, setNewValue] = useState(12);
  const [newEndHour, setNewEndHour] = useState(18);
  const [newLabel, setNewLabel] = useState('');
  const [creating, setCreating] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<BoundaryRule | null>(null);

  const uid = (() => {
    try {
      return getFirebaseUid();
    } catch {
      return null;
    }
  })();

  const handleCreate = async () => {
    if (!uid) return;
    const label = newLabel.trim() || defaultLabel(newType, newValue, newEndHour);
    setCreating(true);
    try {
      const created = await createRule(uid, {
        type: newType,
        label,
        value: newValue,
        secondary_value: newType === 'work_hours_window' ? newEndHour : null,
      });
      // Listener will fold this into the store on the next snapshot —
      // no local mutation needed.
      setNewLabel('');
      toast.success(`Added rule "${created.label}"`);
    } catch (e) {
      toast.error((e as Error).message || 'Create failed');
    } finally {
      setCreating(false);
    }
  };

  const handleToggle = async (rule: BoundaryRule, enabled: boolean) => {
    if (!uid) return;
    try {
      await updateRule(uid, rule.id, { enabled });
      // Listener reconciles — no optimistic local update.
    } catch (e) {
      toast.error((e as Error).message || 'Update failed');
    }
  };

  const handleDelete = async () => {
    if (!uid || !deleteTarget) return;
    try {
      await deleteRule(uid, deleteTarget.id);
      // Listener reconciles — no optimistic local update.
      toast.success('Rule deleted');
    } catch (e) {
      toast.error((e as Error).message || 'Delete failed');
    } finally {
      setDeleteTarget(null);
    }
  };

  if (!uid) {
    return (
      <p className="text-xs text-[var(--color-text-secondary)]">
        Sign in to configure boundary rules.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3 text-sm">
      <p className="text-xs text-[var(--color-text-secondary)]">
        Declare limits on how you work — the enforcer checks them live
        from Today, and the burnout score reacts when they're crossed.
      </p>

      <Link
        to="/boundaries"
        className="flex items-center justify-between rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] transition-colors hover:bg-[var(--color-bg-card)]"
        data-testid="boundaries-manage-link"
      >
        <span className="flex items-center gap-2">
          <SlidersHorizontal className="h-4 w-4" aria-hidden="true" />
          Manage Boundary Rules
        </span>
        <span className="text-xs text-[var(--color-text-secondary)]">
          Open full editor
        </span>
      </Link>

      {rules.length === 0 ? (
        <p className="rounded-md border border-dashed border-[var(--color-border)] p-3 text-xs text-[var(--color-text-secondary)]">
          No rules yet. Add one below.
        </p>
      ) : (
        <ul className="flex flex-col gap-1.5">
          {rules.map((rule) => (
            <li
              key={rule.id}
              className="flex items-start gap-2 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2.5"
            >
              <div className="min-w-0 flex-1">
                <p className="text-sm text-[var(--color-text-primary)]">
                  {rule.label}
                </p>
                <p className="mt-0.5 text-[11px] uppercase tracking-wide text-[var(--color-text-secondary)]">
                  {rule.type.replace(/_/g, ' ')}
                </p>
              </div>
              <label className="mt-0.5 flex items-center gap-1 text-xs">
                <input
                  type="checkbox"
                  checked={rule.enabled}
                  onChange={(e) => handleToggle(rule, e.target.checked)}
                  className="h-3.5 w-3.5 rounded border-[var(--color-border)] text-[var(--color-accent)]"
                />
                Enabled
              </label>
              <button
                onClick={() => setDeleteTarget(rule)}
                className="text-[var(--color-text-secondary)] hover:text-red-500"
                title="Delete"
                aria-label={`Delete ${rule.label}`}
              >
                <Trash2 className="h-4 w-4" aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="rounded-md border border-dashed border-[var(--color-border)] p-3">
        <div className="mb-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
          <label className="text-sm">
            <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
              Rule type
            </span>
            <select
              value={newType}
              onChange={(e) => setNewType(e.target.value as BoundaryRuleType)}
              className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            >
              {RULE_OPTIONS.map((opt) => (
                <option key={opt.type} value={opt.type}>
                  {opt.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm">
            <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
              {newType === 'work_hours_window'
                ? 'Start hour (0–23)'
                : newType === 'weekly_hour_budget'
                ? 'Hours / week'
                : 'Max tasks'}
            </span>
            <input
              type="number"
              min={0}
              max={newType === 'work_hours_window' ? 23 : 999}
              value={newValue}
              onChange={(e) => setNewValue(Number(e.target.value) || 0)}
              className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            />
          </label>
          {newType === 'work_hours_window' && (
            <label className="text-sm">
              <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                End hour (0–23)
              </span>
              <input
                type="number"
                min={0}
                max={23}
                value={newEndHour}
                onChange={(e) => setNewEndHour(Number(e.target.value) || 0)}
                className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
              />
            </label>
          )}
          <label className="text-sm sm:col-span-2">
            <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
              Label (optional)
            </span>
            <input
              type="text"
              value={newLabel}
              onChange={(e) => setNewLabel(e.target.value)}
              placeholder={defaultLabel(newType, newValue, newEndHour)}
              className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            />
          </label>
        </div>
        <p className="mb-2 text-[11px] text-[var(--color-text-secondary)]">
          {RULE_OPTIONS.find((o) => o.type === newType)?.hint}
        </p>
        <div className="flex justify-end">
          <Button size="sm" onClick={handleCreate} disabled={creating}>
            {creating ? (
              <Loader2 className="mr-1 h-3 w-3 animate-spin" />
            ) : (
              <Plus className="mr-1 h-3 w-3" />
            )}
            Add rule
          </Button>
        </div>
      </div>

      <ConfirmDialog
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleDelete}
        title="Delete boundary rule?"
        message={`"${deleteTarget?.label}" will stop enforcing. You can always add it back.`}
        confirmLabel="Delete"
        variant="danger"
      />
    </div>
  );
}

function defaultLabel(
  type: BoundaryRuleType,
  value: number,
  endHour: number,
): string {
  if (type === 'daily_task_cap') return `Max ${value} tasks/day`;
  if (type === 'work_hours_window')
    return `${String(value).padStart(2, '0')}:00–${String(endHour).padStart(2, '0')}:00 work`;
  return `${value}h/week budget`;
}
