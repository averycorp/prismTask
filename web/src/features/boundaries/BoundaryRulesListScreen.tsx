import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  ChevronLeft,
  Clock,
  Plus,
  ShieldAlert,
  SlidersHorizontal,
  Trash2,
  Zap,
} from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState } from '@/components/ui/EmptyState';
import { useBoundaryRulesStore } from '@/stores/boundaryRulesStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import {
  deleteRule,
  updateRule,
  type BoundaryRule,
  type BoundaryRuleType,
} from '@/api/firestore/boundaryRules';
import { ruleTypeLabel, summarizeRule } from './boundaryFormat';

/**
 * Dedicated CRUD list for boundary rules — web port of Android's
 * `ui/screens/boundaries/BoundaryRulesScreen.kt`.
 *
 * Each row shows a type chip, a one-line summary, an enable toggle,
 * and edit + delete buttons. Reads from the live `boundaryRulesStore`
 * so cross-device edits land here without a refresh (parity audit
 * § A.1b). Mutations go through the existing Firestore helpers; the
 * snapshot listener reconciles after the round-trip.
 *
 * The compact `BoundariesSection` in Settings still works for fast
 * single-rule edits — this screen is the full editor with day chips
 * and AnalogClockPicker time inputs.
 */
export function BoundaryRulesListScreen() {
  const rules = useBoundaryRulesStore((s) => s.rules);
  const navigate = useNavigate();
  const [deleteTarget, setDeleteTarget] = useState<BoundaryRule | null>(null);

  const uid = readUid();

  if (!uid) {
    return (
      <div className="mx-auto max-w-3xl p-4">
        <p className="text-sm text-[var(--color-text-secondary)]">
          Sign in to manage Boundary Rules.
        </p>
      </div>
    );
  }

  const handleToggle = async (rule: BoundaryRule, enabled: boolean) => {
    try {
      await updateRule(uid, rule.id, { enabled });
    } catch (e) {
      toast.error((e as Error).message || 'Update failed');
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteRule(uid, deleteTarget.id);
      toast.success('Rule deleted');
    } catch (e) {
      toast.error((e as Error).message || 'Delete failed');
    } finally {
      setDeleteTarget(null);
    }
  };

  return (
    <div className="mx-auto max-w-3xl p-4">
      <header className="mb-6 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <Link
            to="/settings"
            className="rounded-md p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]"
            aria-label="Back to Settings"
          >
            <ChevronLeft className="h-5 w-5" />
          </Link>
          <h1 className="text-2xl font-semibold text-[var(--color-text-primary)]">
            Boundary Rules
          </h1>
        </div>
        <Button
          onClick={() => navigate('/boundaries/new')}
          aria-label="New Rule"
        >
          <Plus className="mr-1 h-4 w-4" aria-hidden="true" />
          New Rule
        </Button>
      </header>

      <p className="mb-4 text-sm text-[var(--color-text-secondary)]">
        Declare limits on how you work. The Today banner and burnout
        scorer react when rules trip — nothing is enforced punitively.
      </p>

      {rules.length === 0 ? (
        <EmptyState
          icon={<ShieldAlert className="h-10 w-10 opacity-60" />}
          title="No rules yet"
          description='Try "No work after 18:00" or "Max 4 hours/day on Work".'
          actionLabel="New Rule"
          onAction={() => navigate('/boundaries/new')}
        />
      ) : (
        <ul className="flex flex-col gap-2" data-testid="boundary-rules-list">
          {rules.map((rule) => (
            <BoundaryRuleRow
              key={rule.id}
              rule={rule}
              onEdit={() => navigate(`/boundaries/${rule.id}`)}
              onToggle={(enabled) => handleToggle(rule, enabled)}
              onDelete={() => setDeleteTarget(rule)}
            />
          ))}
        </ul>
      )}

      <ConfirmDialog
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleDelete}
        title="Delete Boundary Rule?"
        message={`"${deleteTarget?.label}" will stop enforcing. You can always add it back.`}
        confirmLabel="Delete"
        variant="danger"
      />
    </div>
  );
}

function BoundaryRuleRow({
  rule,
  onEdit,
  onToggle,
  onDelete,
}: {
  rule: BoundaryRule;
  onEdit: () => void;
  onToggle: (enabled: boolean) => void;
  onDelete: () => void;
}) {
  const summary = summarizeRule(rule);
  return (
    <li
      className="flex items-start gap-3 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3"
      data-testid="boundary-rule-row"
    >
      <RuleTypeChip type={rule.type} />
      <div className="min-w-0 flex-1">
        <button
          type="button"
          onClick={onEdit}
          className="block w-full text-left"
          data-testid="boundary-rule-edit-trigger"
        >
          <p className="truncate text-sm font-medium text-[var(--color-text-primary)]">
            {rule.label || ruleTypeLabel(rule.type)}
          </p>
          <p className="mt-0.5 truncate text-xs text-[var(--color-text-secondary)]">
            {summary}
          </p>
        </button>
      </div>
      <label className="mt-0.5 flex items-center gap-1 text-xs">
        <input
          type="checkbox"
          checked={rule.enabled}
          onChange={(e) => onToggle(e.target.checked)}
          className="h-3.5 w-3.5 rounded border-[var(--color-border)] text-[var(--color-accent)]"
        />
        <span className="text-[var(--color-text-secondary)]">Enabled</span>
      </label>
      <button
        type="button"
        onClick={onEdit}
        className="rounded-md p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]"
        aria-label={`Edit ${rule.label}`}
        title="Edit"
      >
        <SlidersHorizontal className="h-4 w-4" aria-hidden="true" />
      </button>
      <button
        type="button"
        onClick={onDelete}
        className="rounded-md p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-red-500"
        aria-label={`Delete ${rule.label}`}
        title="Delete"
      >
        <Trash2 className="h-4 w-4" aria-hidden="true" />
      </button>
    </li>
  );
}

function RuleTypeChip({ type }: { type: BoundaryRuleType }) {
  const meta = chipMeta(type);
  return (
    <span
      className={`mt-0.5 inline-flex shrink-0 items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${meta.cls}`}
    >
      <meta.Icon className="h-3 w-3" aria-hidden="true" />
      {meta.label}
    </span>
  );
}

function chipMeta(type: BoundaryRuleType) {
  switch (type) {
    case 'work_hours_window':
      return {
        Icon: Clock,
        label: 'Work Hours',
        cls: 'border-blue-500/30 bg-blue-500/5 text-blue-600',
      };
    case 'category_limit':
      return {
        Icon: SlidersHorizontal,
        label: 'Category Limit',
        cls: 'border-emerald-500/30 bg-emerald-500/5 text-emerald-600',
      };
    case 'escalation':
      return {
        Icon: Zap,
        label: 'Escalation',
        cls: 'border-amber-500/30 bg-amber-500/5 text-amber-600',
      };
    case 'weekly_hour_budget':
      return {
        Icon: SlidersHorizontal,
        label: 'Weekly Budget',
        cls: 'border-emerald-500/30 bg-emerald-500/5 text-emerald-600',
      };
    case 'daily_task_cap':
    default:
      return {
        Icon: ShieldAlert,
        label: 'Daily Cap',
        cls: 'border-rose-500/30 bg-rose-500/5 text-rose-600',
      };
  }
}

function readUid(): string | null {
  try {
    return getFirebaseUid();
  } catch {
    return null;
  }
}
