import { useMemo, useState } from 'react';
import { X } from 'lucide-react';
import { useLeisureStore, type LeisureCategoryRef } from '@/stores/leisureStore';
import { LEISURE_CATEGORIES, type LeisureActivity } from '@/types/leisure';

/**
 * Web port of `app/.../ui/screens/leisure/LogPastLeisureSheet.kt`. Used
 * to backfill a leisure session for an arbitrary past datetime (or now)
 * — either picking an existing pool activity OR entering free-text +
 * auto-adding it to the pool (Q2 lock parity with Android). Custom
 * categories are filtered out of the picker because the underlying
 * REST write would 422 server-side.
 */
export interface LogPastLeisureDialogProps {
  open: boolean;
  onClose: () => void;
}

export function LogPastLeisureDialog({ open, onClose }: LogPastLeisureDialogProps) {
  const activities = useLeisureStore((s) => s.activities);
  const settings = useLeisureStore((s) => s.settings);
  const customCategories = useLeisureStore((s) => s.customCategories);
  const refForId = useLeisureStore((s) => s.refForId);
  const logManualSession = useLeisureStore((s) => s.logManualSession);
  const getVisibleCategoryRefs = useLeisureStore((s) => s.getVisibleCategoryRefs);

  const visibleRefs = useMemo(
    () => getVisibleCategoryRefs().filter((r) => r.kind === 'built-in'),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [getVisibleCategoryRefs, settings, customCategories],
  );

  const [selectedActivityId, setSelectedActivityId] = useState<string | null>(null);
  const [freeText, setFreeText] = useState('');
  const [categoryId, setCategoryId] = useState<string>(
    visibleRefs[0]?.id ?? LEISURE_CATEGORIES[0],
  );
  const [duration, setDuration] = useState('30');
  const [loggedAt, setLoggedAt] = useState(toLocalInputValue(new Date()));
  const [submitting, setSubmitting] = useState(false);

  const selectedActivity: LeisureActivity | null = selectedActivityId
    ? activities.find((a) => a.id === selectedActivityId) ?? null
    : null;

  // Always include the picked-activity's category in the selectable list,
  // even when that category has been disabled in settings (mirror Android).
  const selectableRefs: LeisureCategoryRef[] = useMemoSelectableRefs(
    visibleRefs,
    refForId(categoryId),
  );
  const currentRef =
    selectableRefs.find((r) => r.id === categoryId) ?? selectableRefs[0];

  const grouped = useMemo(() => {
    const map = new Map<string, LeisureActivity[]>();
    for (const a of activities.filter((x) => x.enabled)) {
      const key = a.category;
      const arr = map.get(key) ?? [];
      arr.push(a);
      map.set(key, arr);
    }
    return map;
  }, [activities]);

  if (!open) return null;

  const durationNumber = Number(duration);
  const canSubmit =
    !submitting &&
    (selectedActivity || freeText.trim().length > 0) &&
    currentRef != null &&
    durationNumber >= 1;

  const handleSubmit = async () => {
    if (!canSubmit || !currentRef) return;
    setSubmitting(true);
    try {
      const iso = loggedAt
        ? new Date(loggedAt).toISOString()
        : new Date().toISOString();
      await logManualSession({
        activityId: selectedActivityId,
        freeTextName: selectedActivityId == null ? freeText : null,
        categoryId: currentRef.id,
        durationMinutes: durationNumber,
        loggedAtIso: iso,
      });
      onClose();
      setSelectedActivityId(null);
      setFreeText('');
      setDuration('30');
      setLoggedAt(toLocalInputValue(new Date()));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="log-past-leisure-title"
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/60 sm:items-center"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="max-h-[90vh] w-full max-w-md overflow-auto rounded-t-lg border border-[var(--color-border)] bg-[var(--color-bg-primary)] p-4 sm:rounded-lg">
        <div className="flex items-center justify-between">
          <h3
            id="log-past-leisure-title"
            className="text-base font-semibold text-[var(--color-text-primary)]"
          >
            Log Past Leisure
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="rounded p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-tertiary)]"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Activity picker */}
        <label className="mt-3 block text-sm">
          <span className="text-[var(--color-text-secondary)]">Activity</span>
          <select
            value={selectedActivityId ?? ''}
            onChange={(e) => {
              const val = e.target.value;
              if (val === '') {
                setSelectedActivityId(null);
              } else {
                const a = activities.find((x) => x.id === val);
                if (a) {
                  setSelectedActivityId(a.id);
                  setCategoryId(a.category);
                  if (a.default_duration_minutes != null) {
                    setDuration(String(a.default_duration_minutes));
                  }
                }
              }
            }}
            className="mt-1 w-full rounded border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2 text-sm text-[var(--color-text-primary)]"
          >
            <option value="">Free text…</option>
            {LEISURE_CATEGORIES.map((cat) => {
              const items = grouped.get(cat) ?? [];
              if (items.length === 0) return null;
              return (
                <optgroup key={cat} label={cat}>
                  {items.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.name}
                      {a.default_duration_minutes != null
                        ? ` · ${a.default_duration_minutes} min`
                        : ''}
                    </option>
                  ))}
                </optgroup>
              );
            })}
          </select>
        </label>

        {selectedActivityId == null && (
          <label className="mt-2 block text-sm">
            <span className="text-[var(--color-text-secondary)]">
              Activity name (free text)
            </span>
            <input
              value={freeText}
              onChange={(e) => setFreeText(e.target.value)}
              placeholder="e.g. Read a book"
              className="mt-1 w-full rounded border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2 text-sm text-[var(--color-text-primary)]"
            />
            <span className="mt-1 block text-xs text-[var(--color-text-secondary)]">
              Free-text entries auto-add to the pool so you can re-use them.
            </span>
          </label>
        )}

        <label className="mt-2 block text-sm">
          <span className="text-[var(--color-text-secondary)]">Category</span>
          <select
            value={categoryId}
            onChange={(e) => setCategoryId(e.target.value)}
            className="mt-1 w-full rounded border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2 text-sm text-[var(--color-text-primary)]"
          >
            {selectableRefs.map((r) => (
              <option key={r.id} value={r.id}>
                {r.emoji} {r.label}
              </option>
            ))}
          </select>
        </label>

        <label className="mt-2 block text-sm">
          <span className="text-[var(--color-text-secondary)]">Duration (min)</span>
          <input
            type="number"
            inputMode="numeric"
            min={1}
            value={duration}
            onChange={(e) => setDuration(e.target.value.replace(/[^0-9]/g, ''))}
            className="mt-1 w-full rounded border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2 text-sm text-[var(--color-text-primary)]"
          />
        </label>

        <label className="mt-2 block text-sm">
          <span className="text-[var(--color-text-secondary)]">When</span>
          <input
            type="datetime-local"
            value={loggedAt}
            onChange={(e) => setLoggedAt(e.target.value)}
            className="mt-1 w-full rounded border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2 text-sm text-[var(--color-text-primary)]"
          />
        </label>

        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded px-3 py-1.5 text-sm text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-tertiary)]"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!canSubmit}
            className="rounded bg-[var(--color-accent)] px-3 py-1.5 text-sm text-white disabled:opacity-40"
          >
            {submitting ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * Build the selectable list, ensuring the picked activity's category is
 * always present even when it's been hidden / removed from settings.
 */
function useMemoSelectableRefs(
  visibleRefs: LeisureCategoryRef[],
  fallbackRef: LeisureCategoryRef | null,
): LeisureCategoryRef[] {
  return useMemo(() => {
    const seen = new Set<string>();
    const out: LeisureCategoryRef[] = [];
    for (const r of visibleRefs) {
      if (!seen.has(r.id)) {
        out.push(r);
        seen.add(r.id);
      }
    }
    if (fallbackRef && !seen.has(fallbackRef.id)) {
      out.push(fallbackRef);
    }
    return out;
  }, [visibleRefs, fallbackRef]);
}

function toLocalInputValue(d: Date): string {
  // <input type="datetime-local"> needs `YYYY-MM-DDTHH:mm` in local time —
  // not ISO, which is UTC. Pad and slice.
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}`
  );
}

export default LogPastLeisureDialog;
