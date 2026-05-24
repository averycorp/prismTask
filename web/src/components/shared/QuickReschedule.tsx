import { useState, useRef, useEffect } from 'react';
import {
  Calendar,
  Sun,
  ArrowRight,
  CalendarDays,
  CalendarClock,
} from 'lucide-react';
import { format, addDays, nextMonday } from 'date-fns';

interface QuickRescheduleProps {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (date: string) => void;
  anchorPoint?: { x: number; y: number };
}

export function QuickReschedule({
  isOpen,
  onClose,
  onSelect,
  anchorPoint,
}: QuickRescheduleProps) {
  const [showDatePicker, setShowDatePicker] = useState(false);
  const [customDate, setCustomDate] = useState('');
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [isOpen, onClose]);

  // Reset state when isOpen transitions (derived state pattern)
  const [prevIsOpen, setPrevIsOpen] = useState(isOpen);
  if (isOpen !== prevIsOpen) {
    setPrevIsOpen(isOpen);
    if (!isOpen) {
      setShowDatePicker(false);
      setCustomDate('');
    }
  }

  if (!isOpen) return null;

  const today = new Date();
  const options = [
    {
      label: 'Today',
      icon: Sun,
      date: format(today, 'yyyy-MM-dd'),
      displayDate: today,
    },
    {
      label: 'Tomorrow',
      icon: ArrowRight,
      date: format(addDays(today, 1), 'yyyy-MM-dd'),
      displayDate: addDays(today, 1),
    },
    {
      label: 'Next Monday',
      icon: CalendarDays,
      date: format(nextMonday(today), 'yyyy-MM-dd'),
      displayDate: nextMonday(today),
    },
    {
      label: 'Next Week',
      icon: CalendarClock,
      date: format(addDays(today, 7), 'yyyy-MM-dd'),
      displayDate: addDays(today, 7),
    },
  ];

  const style = anchorPoint
    ? {
        position: 'fixed' as const,
        top: Math.min(anchorPoint.y, typeof window !== 'undefined' ? window.innerHeight - 300 : 0),
        left: Math.min(anchorPoint.x, typeof window !== 'undefined' ? window.innerWidth - 220 : 0),
      }
    : {};

  return (
    <div
      ref={menuRef}
      className="z-50 min-w-[200px] rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] p-1 shadow-lg"
      style={style}
    >
      <div className="px-3 py-1.5 text-xs font-medium uppercase tracking-wider text-[var(--color-text-secondary)]">
        Reschedule
      </div>
      {options.map((opt) => (
        <button
          key={opt.label}
          onClick={() => {
            onSelect(opt.date);
            onClose();
          }}
          className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)] transition-colors"
        >
          <opt.icon className="h-4 w-4 text-[var(--color-text-secondary)]" />
          {opt.label}
          <span className="ml-auto text-xs text-[var(--color-text-secondary)]">
            {format(opt.displayDate, 'MMM d')}
          </span>
        </button>
      ))}
      <div className="my-1 border-t border-[var(--color-border)]" />
      {!showDatePicker ? (
        <button
          onClick={() => setShowDatePicker(true)}
          className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)] transition-colors"
        >
          <Calendar className="h-4 w-4 text-[var(--color-text-secondary)]" />
          Pick Date...
        </button>
      ) : (
        <div className="px-3 py-2">
          <input
            type="date"
            value={customDate}
            onChange={(e) => setCustomDate(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && customDate) {
                onSelect(customDate);
                onClose();
              }
            }}
            className="w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            autoFocus
          />
          {customDate && (
            <button
              onClick={() => {
                onSelect(customDate);
                onClose();
              }}
              className="mt-1 w-full rounded-md bg-[var(--color-accent)] px-2 py-1 text-xs text-white hover:opacity-90"
            >
              Set Date
            </button>
          )}
        </div>
      )}
    </div>
  );
}
