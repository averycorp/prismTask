import { Check, Minus } from 'lucide-react';

interface CheckboxProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  indeterminate?: boolean;
  priorityColor?: string;
  disabled?: boolean;
  label?: string;
  /**
   * Accessible name for the control. Required when there is no visible
   * `label` (the `role="checkbox"` button has no text content of its own,
   * so without this it is announced as an unlabeled checkbox). Falls back
   * to `label` when omitted.
   */
  ariaLabel?: string;
  className?: string;
}

export function Checkbox({
  checked,
  onChange,
  indeterminate = false,
  priorityColor,
  disabled = false,
  label,
  ariaLabel,
  className = '',
}: CheckboxProps) {
  const ringColor = priorityColor || 'var(--color-border)';
  const fillColor = priorityColor || 'var(--color-accent)';

  return (
    <label
      className={`inline-flex items-center gap-2 ${disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'} ${className}`}
    >
      <button
        type="button"
        role="checkbox"
        aria-checked={indeterminate ? 'mixed' : checked}
        aria-label={ariaLabel ?? label}
        disabled={disabled}
        onClick={() => !disabled && onChange(!checked)}
        className="relative flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-2 transition-all duration-150"
        style={{
          borderColor: checked || indeterminate ? fillColor : ringColor,
          backgroundColor: checked || indeterminate ? fillColor : 'transparent',
        }}
      >
        {checked && !indeterminate && (
          <Check className="h-3 w-3 text-white" strokeWidth={3} />
        )}
        {indeterminate && (
          <Minus className="h-3 w-3 text-white" strokeWidth={3} />
        )}
      </button>
      {label && (
        <span className="text-sm text-[var(--color-text-primary)]">{label}</span>
      )}
    </label>
  );
}
