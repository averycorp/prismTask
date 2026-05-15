import { useState } from 'react';
import { Sparkles, ChevronDown, ChevronUp, X } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Tooltip } from '@/components/ui/Tooltip';
import type { PendingBuiltInUpdate } from '@/utils/builtInHabitReconciler';

/**
 * Web equivalent of the Android "built-in habit update available"
 * surface (Settings → Built-In Habits in `BuiltInUpdatesScreen.kt`).
 * Parity audit § B.4.
 *
 * Renders a single condensed banner above the habit list when one or
 * more built-in habits have a newer registry version. Tapping the
 * banner expands the per-template breakdown showing
 * "Vfrom → Vto · N changes" plus a per-template Dismiss button. The
 * "Apply on Android" copy is intentional: web cannot apply the diff
 * itself because the registry deltas (especially the self-care
 * steps) require the Android-side `SelfCareDao`. Web's job is to
 * surface the prompt and keep dismissals per-device.
 */
export interface BuiltInTemplateUpdateBannerProps {
  pending: PendingBuiltInUpdate[];
  onDismiss: (templateKey: string, version: number) => void;
}

export function BuiltInTemplateUpdateBanner({
  pending,
  onDismiss,
}: BuiltInTemplateUpdateBannerProps) {
  const [expanded, setExpanded] = useState(false);

  if (pending.length === 0) return null;

  const total = pending.length;
  const headline =
    total === 1
      ? `Template update available for "${pending[0].displayName}"`
      : `${total} built-in habits have template updates available`;

  return (
    <div
      role="region"
      aria-label="Built-in habit template updates available"
      className="mb-4 overflow-hidden rounded-xl border border-[var(--color-accent)]/30 bg-[var(--color-accent)]/5"
    >
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-[var(--color-accent)]/10"
        aria-expanded={expanded}
      >
        <Sparkles
          className="h-5 w-5 shrink-0 text-[var(--color-accent)]"
          aria-hidden="true"
        />
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-[var(--color-text-primary)]">
            {headline}
          </p>
          <p className="mt-0.5 truncate text-xs text-[var(--color-text-secondary)]">
            Open the Android app to review and apply changes.
          </p>
        </div>
        {expanded ? (
          <ChevronUp
            className="h-4 w-4 shrink-0 text-[var(--color-text-secondary)]"
            aria-hidden="true"
          />
        ) : (
          <ChevronDown
            className="h-4 w-4 shrink-0 text-[var(--color-text-secondary)]"
            aria-hidden="true"
          />
        )}
      </button>

      {expanded && (
        <ul className="border-t border-[var(--color-accent)]/20 px-4 py-3">
          {pending.map((update) => (
            <li
              key={update.templateKey}
              className="flex items-center gap-3 py-1.5"
            >
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm text-[var(--color-text-primary)]">
                  {update.displayName}
                </p>
                <p className="text-xs text-[var(--color-text-secondary)]">
                  v{update.fromVersion} → v{update.toVersion} ·{' '}
                  {pluralize(update.habitFieldChangeCount, 'change', 'changes')}
                </p>
              </div>
              <Tooltip content="Dismiss this update on this device" delay={300}>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => onDismiss(update.templateKey, update.toVersion)}
                  aria-label={`Dismiss update for ${update.displayName}`}
                >
                  <X className="h-4 w-4" />
                </Button>
              </Tooltip>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function pluralize(n: number, singular: string, plural: string): string {
  return `${n} ${n === 1 ? singular : plural}`;
}
