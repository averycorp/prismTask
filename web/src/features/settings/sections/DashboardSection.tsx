import { ChevronUp, ChevronDown, Check } from 'lucide-react';
import { useDashboardStore } from '@/stores/dashboardStore';
import { DEFAULT_SECTION_ORDER } from '@/api/firestore/dashboardPreferences';

/**
 * Settings → Dashboard subsection. Cross-device parity with Android's
 * `DashboardSection.kt` (parity audit C.1f).
 *
 * Two controls:
 *   1. Visible sections — per-section show/hide toggle. Hidden
 *      sections never render on Today.
 *   2. Section order — move-up / move-down arrows reorder dashboard
 *      sections. Matches Android's `ReorderableRow` (button-driven
 *      reorder; no HTML5 DnD on web for accessibility-by-default).
 *
 * Writes round-trip through `useDashboardStore` which mirrors them
 * into Firestore at `users/{uid}/prefs/dashboard_prefs`. A signed-in
 * user's tweak on web will land on Android via the existing
 * `GenericPreferenceSyncService` reader and vice versa.
 */
export function DashboardSection() {
  const sectionOrder = useDashboardStore((s) => s.sectionOrder);
  const hiddenSections = useDashboardStore((s) => s.hiddenSections);
  const setSectionOrder = useDashboardStore((s) => s.setSectionOrder);
  const setSectionHidden = useDashboardStore((s) => s.setSectionHidden);

  const moveSection = (index: number, delta: -1 | 1) => {
    const target = index + delta;
    if (target < 0 || target >= sectionOrder.length) return;
    const next = [...sectionOrder];
    [next[index], next[target]] = [next[target], next[index]];
    setSectionOrder(next);
  };

  return (
    <div className="flex flex-col gap-5">
      <div>
        <p className="mb-1 text-sm font-medium text-[var(--color-text-primary)]">
          Visible Sections
        </p>
        <p className="mb-3 text-xs text-[var(--color-text-secondary)]">
          Hide a section to remove it from Today entirely. Hidden
          sections still sync to your other devices.
        </p>
        <div className="flex flex-col gap-1">
          {DEFAULT_SECTION_ORDER.map((key) => {
            const visible = !hiddenSections.includes(key);
            return (
              <button
                key={key}
                onClick={() => setSectionHidden(key, visible)}
                className="flex items-center gap-2 rounded-md px-2 py-2 text-left transition-colors hover:bg-[var(--color-bg-secondary)]"
                role="switch"
                aria-checked={visible}
                aria-label={`Toggle ${SECTION_LABELS[key] ?? key} visibility`}
              >
                <span
                  className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-md border ${
                    visible
                      ? 'border-[var(--color-accent)] bg-[var(--color-accent)]'
                      : 'border-[var(--color-border)] bg-transparent'
                  }`}
                  aria-hidden="true"
                >
                  {visible && <Check className="h-3 w-3 text-white" />}
                </span>
                <span className="text-sm text-[var(--color-text-primary)]">
                  {SECTION_LABELS[key] ?? key}
                </span>
              </button>
            );
          })}
        </div>
      </div>

      <div>
        <p className="mb-1 text-sm font-medium text-[var(--color-text-primary)]">
          Section Order
        </p>
        <p className="mb-3 text-xs text-[var(--color-text-secondary)]">
          Reorder the dashboard sections on Today. The same order is
          used on your other signed-in devices.
        </p>
        <ol className="flex flex-col gap-1">
          {sectionOrder.map((key, index) => (
            <li
              key={key}
              className="flex items-center gap-2 rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2"
            >
              <span className="flex-1 text-sm text-[var(--color-text-primary)]">
                {SECTION_LABELS[key] ?? key}
              </span>
              <button
                onClick={() => moveSection(index, -1)}
                disabled={index === 0}
                className="rounded p-1 text-[var(--color-text-secondary)] transition-colors hover:bg-[var(--color-bg-card)] hover:text-[var(--color-text-primary)] disabled:cursor-not-allowed disabled:opacity-30"
                aria-label={`Move ${SECTION_LABELS[key] ?? key} up`}
              >
                <ChevronUp className="h-4 w-4" />
              </button>
              <button
                onClick={() => moveSection(index, 1)}
                disabled={index === sectionOrder.length - 1}
                className="rounded p-1 text-[var(--color-text-secondary)] transition-colors hover:bg-[var(--color-bg-card)] hover:text-[var(--color-text-primary)] disabled:cursor-not-allowed disabled:opacity-30"
                aria-label={`Move ${SECTION_LABELS[key] ?? key} down`}
              >
                <ChevronDown className="h-4 w-4" />
              </button>
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}

/**
 * Labels mirror Android's `sectionLabels` map (see
 * `app/.../ui/screens/settings/SettingsUtils.kt`). Keys held in sync
 * with Android's `DashboardPreferences.DEFAULT_ORDER`.
 */
const SECTION_LABELS: Record<string, string> = {
  progress: 'Progress Card',
  overdue: 'From Earlier',
  today_tasks: 'Today Tasks',
  daily_essentials: 'Daily Essentials',
  habits: 'Habits',
  plan_more: 'Plan More',
  completed: 'Completed',
};
