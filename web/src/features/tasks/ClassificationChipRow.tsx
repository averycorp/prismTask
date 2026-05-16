import { Loader2, Sparkles } from 'lucide-react';

/**
 * Shared three-chip picker used by Organize-tab classification dimensions
 * (TaskMode, CognitiveLoad). Mirrors Android's chip-row Organize-tab
 * pattern: three explicit values + an "Auto" affordance, with the
 * `UNCATEGORIZED` state represented by no chip selected. Extracted so
 * the three classification rows stay visually + behaviorally in sync.
 *
 * The `LifeCategory` row inside `TaskEditor` still uses a `<select>` today
 * because it lives on a different upgrade path (AI-backed Claude
 * classify); migrating that picker to this primitive is tracked
 * separately. Until then this component is the canonical chip-row shape.
 */

export interface ChipOption<T extends string> {
  /** Enum value written through to the task entity. */
  value: T;
  /** Human-readable label shown on the chip. */
  label: string;
}

export interface ClassificationChipRowProps<T extends string> {
  /** Field title rendered above the chip row (e.g. "Task Mode"). */
  label: string;
  /** Currently-selected value; empty string means "Uncategorized". */
  value: T | '';
  /** Three chip values to render. */
  options: ChipOption<T>[];
  /** Called when the user taps a chip (or taps the active chip to clear). */
  onChange: (next: T | '') => void;
  /** Called when the user taps the Auto button. */
  onAutoClick?: () => void;
  /** When true, Auto button shows a spinner and is disabled. */
  autoBusy?: boolean;
  /** When true, Auto button is disabled and tooltipped. */
  autoDisabled?: boolean;
  /** Tooltip shown on the Auto button (varies by disabled reason). */
  autoTooltip?: string;
  /** Accessible label for the Auto button. */
  autoAriaLabel?: string;
  /** Helper text rendered below the chip row. */
  helperText?: string;
}

export function ClassificationChipRow<T extends string>({
  label,
  value,
  options,
  onChange,
  onAutoClick,
  autoBusy = false,
  autoDisabled = false,
  autoTooltip,
  autoAriaLabel,
  helperText,
}: ClassificationChipRowProps<T>) {
  return (
    <div>
      <div className="mb-1 flex items-center justify-between">
        <span className="block text-xs font-medium text-[var(--color-text-secondary)]">
          {label}
        </span>
        {onAutoClick && (
          <button
            type="button"
            onClick={onAutoClick}
            disabled={autoDisabled || autoBusy}
            aria-label={autoAriaLabel ?? `Auto-classify ${label}`}
            title={autoTooltip}
            className="inline-flex items-center gap-1 rounded-full border border-[var(--color-accent)]/40 bg-[var(--color-accent)]/10 px-2 py-0.5 text-[10px] font-medium text-[var(--color-accent)] transition hover:bg-[var(--color-accent)]/20 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {autoBusy ? (
              <Loader2 className="h-3 w-3 animate-spin" />
            ) : (
              <Sparkles className="h-3 w-3" />
            )}
            Auto
          </button>
        )}
      </div>
      <div
        role="radiogroup"
        aria-label={label}
        className="flex flex-wrap gap-1.5"
      >
        {options.map((opt) => {
          const selected = value === opt.value;
          return (
            <button
              key={opt.value}
              type="button"
              role="radio"
              aria-checked={selected}
              onClick={() => onChange(selected ? '' : opt.value)}
              className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition-colors ${
                selected
                  ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                  : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:border-[var(--color-accent)]'
              }`}
            >
              {opt.label}
            </button>
          );
        })}
      </div>
      {helperText && (
        <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
          {helperText}
        </p>
      )}
    </div>
  );
}
