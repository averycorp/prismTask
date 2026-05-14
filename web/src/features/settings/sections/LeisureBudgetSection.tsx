import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, Plus, Pencil } from 'lucide-react';
import { useLeisureStore } from '@/stores/leisureStore';
import { LEISURE_CATEGORIES, LEISURE_CATEGORY_DISPLAY } from '@/types/leisure';

/**
 * Settings entry for the Leisure Budget v2.0 surface. The pool + activity
 * editor lives inside `LeisurePoolScreen.tsx`'s Manage section; this card
 * mirrors Android's settings → Daily Essentials → Leisure entry, surfacing
 * the daily target + category state, and routing to the full screen.
 */
export function LeisureBudgetSection() {
  const navigate = useNavigate();
  const fetchAll = useLeisureStore((s) => s.fetchAll);
  const settings = useLeisureStore((s) => s.settings);
  const activities = useLeisureStore((s) => s.activities);
  const customCategories = useLeisureStore((s) => s.customCategories);

  useEffect(() => {
    if (!settings) fetchAll();
  }, [settings, fetchAll]);

  const dailyTarget = settings?.daily_target_minutes ?? 60;
  const weekendTarget = settings?.weekend_target_minutes;
  const enabledCount =
    (settings?.enabled_categories?.length ?? LEISURE_CATEGORIES.length) +
    customCategories.length;

  return (
    <div className="space-y-3">
      <p className="text-sm text-[var(--color-text-secondary)]">
        Track a daily minimum across physical, social, creative, and passive
        leisure. Build out an activity pool to log sessions in one tap.
      </p>

      <div className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] p-3 text-sm">
        <div className="flex items-center justify-between">
          <span className="text-[var(--color-text-secondary)]">Daily minimum</span>
          <span className="font-semibold text-[var(--color-text-primary)]">
            {dailyTarget} min
          </span>
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-[var(--color-text-secondary)]">Weekend minimum</span>
          <span className="font-semibold text-[var(--color-text-primary)]">
            {weekendTarget == null ? 'Same as weekdays' : `${weekendTarget} min`}
          </span>
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-[var(--color-text-secondary)]">Categories</span>
          <span className="font-semibold text-[var(--color-text-primary)]">
            {enabledCount} enabled
          </span>
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-[var(--color-text-secondary)]">Pool size</span>
          <span className="font-semibold text-[var(--color-text-primary)]">
            {activities.length}{' '}
            {activities.length === 1 ? 'activity' : 'activities'}
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-2 sm:flex-row">
        <button
          type="button"
          onClick={() => navigate('/leisure')}
          className="inline-flex items-center justify-between gap-2 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm font-medium text-[var(--color-text-primary)] hover:bg-[var(--color-bg-tertiary)]"
        >
          <span className="inline-flex items-center gap-2">
            <Pencil className="h-4 w-4" />
            Manage Pool &amp; Categories
          </span>
          <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" />
        </button>
        <button
          type="button"
          onClick={() => navigate('/leisure')}
          className="inline-flex items-center justify-between gap-2 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm font-medium text-[var(--color-text-primary)] hover:bg-[var(--color-bg-tertiary)]"
        >
          <span className="inline-flex items-center gap-2">
            <Plus className="h-4 w-4" />
            Log Past Session
          </span>
          <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" />
        </button>
      </div>

      <details className="text-xs text-[var(--color-text-secondary)]">
        <summary className="cursor-pointer select-none">
          Built-in categories
        </summary>
        <ul className="mt-2 space-y-1">
          {LEISURE_CATEGORIES.map((cat) => {
            const enabled = settings?.enabled_categories?.includes(cat) ?? true;
            const display = LEISURE_CATEGORY_DISPLAY[cat];
            return (
              <li key={cat}>
                {display.emoji} {display.label}
                {!enabled && ' (disabled)'}
              </li>
            );
          })}
        </ul>
      </details>
    </div>
  );
}

export default LeisureBudgetSection;
