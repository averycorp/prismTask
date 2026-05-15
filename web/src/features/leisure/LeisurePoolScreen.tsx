import { useEffect, useMemo, useState } from 'react';
import {
  Plus,
  Pencil,
  Trash2,
  Check,
  X,
  ChevronDown,
  ChevronRight,
  History,
} from 'lucide-react';
import { format, parseISO } from 'date-fns';
import { useLeisureStore, type LeisureCategoryRef } from '@/stores/leisureStore';
import { useSettingsStore } from '@/stores/settingsStore';
import {
  LEISURE_CATEGORIES,
  LEISURE_CATEGORY_DISPLAY,
  type LeisureActivity,
} from '@/types/leisure';
import { LogPastLeisureDialog } from './LogPastLeisureDialog';

/**
 * Web port of `app/.../ui/screens/leisure/LeisurePoolScreen.kt`. Lean
 * re-implementation that preserves the visual hierarchy (Today hero card →
 * Quick-Log category tiles → Recent activity → Manage section) and the four
 * key dialog flows (Add, Edit, Check-off duration, Pick activity from
 * category). Custom categories surface visually but writes against them are
 * blocked at the store layer with a helpful error.
 */
export function LeisurePoolScreen() {
  const fetchAll = useLeisureStore((s) => s.fetchAll);
  const isLoading = useLeisureStore((s) => s.isLoading);
  const error = useLeisureStore((s) => s.error);
  const activities = useLeisureStore((s) => s.activities);
  const sessions = useLeisureStore((s) => s.sessions);
  const settings = useLeisureStore((s) => s.settings);
  const customCategories = useLeisureStore((s) => s.customCategories);
  const setDailyTarget = useLeisureStore((s) => s.setDailyTarget);
  const setWeekendTarget = useLeisureStore((s) => s.setWeekendTarget);
  const setCategoryEnabled = useLeisureStore((s) => s.setCategoryEnabled);
  const addCustomCategory = useLeisureStore((s) => s.addCustomCategory);
  const removeCustomCategory = useLeisureStore((s) => s.removeCustomCategory);
  const setActivityEnabled = useLeisureStore((s) => s.setActivityEnabled);
  const deleteActivity = useLeisureStore((s) => s.deleteActivity);
  const refForId = useLeisureStore((s) => s.refForId);
  const getVisibleCategoryRefs = useLeisureStore((s) => s.getVisibleCategoryRefs);
  const getMinutesLoggedToday = useLeisureStore((s) => s.getMinutesLoggedToday);
  const getMinutesByCategoryIdToday = useLeisureStore((s) => s.getMinutesByCategoryIdToday);
  const getTargetMinutesToday = useLeisureStore((s) => s.getTargetMinutesToday);
  // Subscribed for re-render so the per-day window recomputes when a
  // cross-device Start-of-Day update lands (see `dayBoundary.ts`).
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);

  // Getters read live store state — depend on the slices they consume so
  // recomputation lands when settings/customCategories/sessions change. The
  // getter identities are stable across the store's lifecycle (zustand
  // returns the same function ref), so adding them to deps is harmless.
  const visibleRefs = useMemo(
    () => getVisibleCategoryRefs(),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [getVisibleCategoryRefs, settings, customCategories],
  );
  const minutesLogged = useMemo(
    () => getMinutesLoggedToday(),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [getMinutesLoggedToday, sessions, startOfDayHour],
  );
  const minutesByCat = useMemo(
    () => getMinutesByCategoryIdToday(),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [getMinutesByCategoryIdToday, sessions, startOfDayHour],
  );
  const targetMinutes = getTargetMinutesToday();

  const [addOpen, setAddOpen] = useState(false);
  const [addInitialCategoryId, setAddInitialCategoryId] = useState<string | null>(null);
  const [editingActivity, setEditingActivity] = useState<LeisureActivity | null>(null);
  const [checkingOff, setCheckingOff] = useState<LeisureActivity | null>(null);
  const [pickingCategory, setPickingCategory] = useState<LeisureCategoryRef | null>(null);
  const [quickLogCategory, setQuickLogCategory] = useState<LeisureCategoryRef | null>(null);
  const [addCustomOpen, setAddCustomOpen] = useState(false);
  const [manageOpen, setManageOpen] = useState(false);
  const [logPastOpen, setLogPastOpen] = useState(false);

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  const activitiesByCategoryId = useMemo(() => {
    const map: Record<string, LeisureActivity[]> = {};
    for (const a of activities) {
      (map[a.category] ??= []).push(a);
    }
    return map;
  }, [activities]);

  const sessionsByDay = useMemo(() => {
    const map = new Map<string, typeof sessions>();
    for (const s of sessions) {
      const key = format(parseISO(s.logged_at), 'yyyy-MM-dd');
      const arr = map.get(key) ?? [];
      arr.push(s);
      map.set(key, arr);
    }
    return Array.from(map.entries())
      .map(([dateKey, items]) => ({
        dateKey,
        items: items.slice().sort((a, b) => b.logged_at.localeCompare(a.logged_at)),
        totalMinutes: items.reduce((sum, x) => sum + x.duration_minutes, 0),
      }))
      .sort((a, b) => b.dateKey.localeCompare(a.dateKey));
  }, [sessions]);

  const progressFraction = targetMinutes > 0
    ? Math.min(1, minutesLogged / targetMinutes)
    : 0;

  return (
    <div className="mx-auto max-w-2xl space-y-4 p-4">
      <header className="flex items-center justify-between gap-2">
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          Leisure Minimum
        </h1>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setLogPastOpen(true)}
            className="inline-flex items-center gap-1 rounded-full border border-[var(--color-border)] px-3 py-1.5 text-sm font-medium text-[var(--color-text-primary)] hover:bg-[var(--color-bg-tertiary)]"
          >
            <History className="h-4 w-4" /> Log Past
          </button>
          <button
            type="button"
            onClick={() => {
              setAddInitialCategoryId(visibleRefs[0]?.id ?? LEISURE_CATEGORIES[0]);
              setAddOpen(true);
            }}
            className="inline-flex items-center gap-1 rounded-full bg-[var(--color-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90"
          >
            <Plus className="h-4 w-4" /> Activity
          </button>
        </div>
      </header>

      {error && (
        <div className="rounded border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-300">
          {error}
        </div>
      )}

      {/* Today hero */}
      <section className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-4">
        <div className="flex items-end justify-between">
          <div>
            <div className="text-xs uppercase tracking-wide text-[var(--color-text-secondary)]">
              Today
            </div>
            <div className="mt-1 flex items-end gap-1">
              <span className="text-4xl font-bold text-[var(--color-accent)]">
                {minutesLogged}
              </span>
              <span className="pb-1 text-sm text-[var(--color-text-secondary)]">min</span>
            </div>
          </div>
          {targetMinutes > 0 && (
            <div className="pb-1 text-sm text-[var(--color-text-secondary)]">
              of {targetMinutes} min
            </div>
          )}
        </div>
        {targetMinutes > 0 ? (
          <div className="mt-3 h-2 overflow-hidden rounded bg-[var(--color-bg-tertiary)]">
            <div
              className="h-full rounded bg-[var(--color-accent)] transition-all"
              style={{ width: `${(progressFraction * 100).toFixed(1)}%` }}
            />
          </div>
        ) : (
          <div className="mt-2 text-xs text-[var(--color-text-secondary)]">
            Set a daily minimum below to track progress.
          </div>
        )}
        {visibleRefs.length > 0 && minutesLogged > 0 && (
          <div className="mt-4 grid grid-cols-4 gap-2 border-t border-[var(--color-border)] pt-3">
            {visibleRefs.slice(0, 4).map((ref) => {
              const mins = minutesByCat[ref.id] ?? 0;
              return (
                <div
                  key={ref.id}
                  className="flex flex-col items-center text-center"
                  title={`${ref.label}: ${mins} min today`}
                >
                  <span className="text-lg">{ref.emoji}</span>
                  <span className="text-xs font-semibold text-[var(--color-text-primary)]">
                    {mins}
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </section>

      {/* Quick log tiles */}
      <section>
        <h2 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
          Quick Log
        </h2>
        <div className="grid grid-cols-2 gap-2">
          {visibleRefs.map((ref) => (
            <button
              key={ref.id}
              type="button"
              onClick={() => setPickingCategory(ref)}
              className="flex items-start justify-between rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3 text-left hover:border-[var(--color-accent)]"
            >
              <div className="flex flex-col">
                <span className="text-xl">{ref.emoji}</span>
                <span className="mt-1 text-sm font-semibold text-[var(--color-text-primary)]">
                  {ref.label}
                </span>
                <span className="text-xs text-[var(--color-text-secondary)]">
                  {activitiesByCategoryId[ref.id]?.filter((a) => a.enabled).length ?? 0}{' '}
                  activities
                </span>
              </div>
              <Plus className="h-4 w-4 text-[var(--color-accent)]" />
            </button>
          ))}
        </div>
      </section>

      {/* Recent activity */}
      <section>
        <h2 className="mb-2 text-sm font-semibold text-[var(--color-text-primary)]">
          Recent Activity
        </h2>
        {sessionsByDay.length === 0 && !isLoading && (
          <p className="text-xs text-[var(--color-text-secondary)]">
            Nothing logged yet — tap a category above to start.
          </p>
        )}
        <div className="space-y-2">
          {sessionsByDay.map((day) => (
            <div
              key={day.dateKey}
              className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3"
            >
              <div className="flex items-center justify-between text-sm">
                <span className="font-semibold text-[var(--color-text-primary)]">
                  {format(parseISO(day.dateKey), 'EEE, MMM d')}
                </span>
                <span className="text-xs text-[var(--color-text-secondary)]">
                  {day.totalMinutes} min · {day.items.length} logged
                </span>
              </div>
              <ul className="mt-2 space-y-1">
                {day.items.slice(0, 8).map((sess) => {
                  const activity = sess.activity_id
                    ? activities.find((a) => a.id === sess.activity_id)
                    : null;
                  const ref = refForId(sess.category);
                  return (
                    <li
                      key={sess.id}
                      className="flex items-center gap-2 text-xs text-[var(--color-text-secondary)]"
                    >
                      <span>{ref?.emoji ?? '•'}</span>
                      <span className="text-[var(--color-text-primary)]">
                        {activity?.name ?? ref?.label ?? 'Leisure'}
                      </span>
                      <span className="ml-auto">
                        {format(parseISO(sess.logged_at), 'h:mm a')} · {sess.duration_minutes} min
                      </span>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </div>
      </section>

      {/* Manage */}
      <section className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)]">
        <button
          type="button"
          onClick={() => setManageOpen((v) => !v)}
          className="flex w-full items-center justify-between p-3 text-left"
        >
          <div>
            <span className="text-sm font-semibold text-[var(--color-text-primary)]">
              Manage
            </span>
            <span className="ml-2 text-xs text-[var(--color-text-secondary)]">
              {settings?.daily_target_minutes ?? 60} min/day ·{' '}
              {visibleRefs.length} categories
            </span>
          </div>
          {manageOpen ? (
            <ChevronDown className="h-4 w-4 text-[var(--color-text-secondary)]" />
          ) : (
            <ChevronRight className="h-4 w-4 text-[var(--color-text-secondary)]" />
          )}
        </button>
        {manageOpen && (
          <div className="space-y-4 border-t border-[var(--color-border)] p-3">
            {/* Daily minimum */}
            <div>
              <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                Daily Minimum
              </h3>
              <label className="block text-sm">
                <span className="text-[var(--color-text-secondary)]">
                  {settings?.daily_target_minutes ?? 60} minutes
                </span>
                <input
                  type="range"
                  min={0}
                  max={240}
                  step={5}
                  value={settings?.daily_target_minutes ?? 60}
                  onChange={(e) => setDailyTarget(Number(e.target.value))}
                  className="w-full"
                />
              </label>
              <div className="mt-2 flex items-center gap-2 text-sm">
                <input
                  id="weekend-toggle"
                  type="checkbox"
                  checked={settings?.weekend_target_minutes != null}
                  onChange={(e) =>
                    setWeekendTarget(
                      e.target.checked
                        ? settings?.daily_target_minutes ?? 60
                        : null,
                    )
                  }
                />
                <label htmlFor="weekend-toggle" className="text-[var(--color-text-primary)]">
                  Different minimum on weekends
                </label>
              </div>
              {settings?.weekend_target_minutes != null && (
                <label className="mt-1 block text-sm">
                  <span className="text-[var(--color-text-secondary)]">
                    Weekend: {settings.weekend_target_minutes} minutes
                  </span>
                  <input
                    type="range"
                    min={0}
                    max={240}
                    step={5}
                    value={settings.weekend_target_minutes ?? 60}
                    onChange={(e) => setWeekendTarget(Number(e.target.value))}
                    className="w-full"
                  />
                </label>
              )}
            </div>

            {/* Categories */}
            <div>
              <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                Categories
              </h3>
              <ul className="space-y-1">
                {LEISURE_CATEGORIES.map((cat) => {
                  const enabled = settings?.enabled_categories?.includes(cat) ?? true;
                  const display = LEISURE_CATEGORY_DISPLAY[cat];
                  return (
                    <li
                      key={cat}
                      className="flex items-center justify-between text-sm"
                    >
                      <span className="text-[var(--color-text-primary)]">
                        {display.emoji} {display.label}{' '}
                        {!enabled && (
                          <span className="ml-1 text-xs text-[var(--color-text-secondary)]">
                            (disabled)
                          </span>
                        )}
                      </span>
                      <button
                        type="button"
                        onClick={() => setCategoryEnabled(cat, !enabled)}
                        className="rounded px-2 py-1 text-xs hover:bg-[var(--color-bg-tertiary)]"
                      >
                        {enabled ? 'Disable' : 'Enable'}
                      </button>
                    </li>
                  );
                })}
                {customCategories.map((c) => (
                  <li key={c.id} className="flex items-center justify-between text-sm">
                    <span className="text-[var(--color-text-primary)]">
                      {c.emoji} {c.label}{' '}
                      <span className="ml-1 text-xs text-[var(--color-text-secondary)]">
                        (custom · device-local)
                      </span>
                    </span>
                    <button
                      type="button"
                      onClick={() => removeCustomCategory(c.id)}
                      className="rounded px-2 py-1 text-xs text-red-400 hover:bg-[var(--color-bg-tertiary)]"
                    >
                      Remove
                    </button>
                  </li>
                ))}
              </ul>
              <button
                type="button"
                onClick={() => setAddCustomOpen(true)}
                className="mt-2 inline-flex items-center gap-1 text-xs text-[var(--color-accent)] hover:underline"
              >
                <Plus className="h-3 w-3" /> Add custom category
              </button>
            </div>

            {/* Activity pool */}
            {activities.length > 0 && (
              <div>
                <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
                  Activity Pool
                </h3>
                <ul className="space-y-1">
                  {activities.map((a) => (
                    <li
                      key={a.id}
                      className="flex items-center gap-2 text-sm"
                    >
                      <div className="min-w-0 flex-1">
                        <div
                          className={
                            a.enabled
                              ? 'text-[var(--color-text-primary)]'
                              : 'text-[var(--color-text-secondary)] line-through'
                          }
                        >
                          {a.name}
                        </div>
                        <div className="text-xs text-[var(--color-text-secondary)]">
                          {refForId(a.category)?.label ?? a.category}
                          {a.default_duration_minutes != null && (
                            <> · {a.default_duration_minutes} min</>
                          )}
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={() => setCheckingOff(a)}
                        disabled={!a.enabled}
                        className="rounded p-1 text-[var(--color-accent)] hover:bg-[var(--color-bg-tertiary)] disabled:opacity-30"
                        title="Log this"
                      >
                        <Check className="h-4 w-4" />
                      </button>
                      <button
                        type="button"
                        onClick={() => setEditingActivity(a)}
                        className="rounded p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-tertiary)]"
                        title="Edit"
                      >
                        <Pencil className="h-4 w-4" />
                      </button>
                      <button
                        type="button"
                        onClick={() => setActivityEnabled(a, !a.enabled)}
                        className="rounded px-2 py-1 text-xs text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-tertiary)]"
                      >
                        {a.enabled ? 'Disable' : 'Enable'}
                      </button>
                      <button
                        type="button"
                        onClick={() => deleteActivity(a)}
                        className="rounded p-1 text-red-400 hover:bg-[var(--color-bg-tertiary)]"
                        title="Delete"
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </section>

      {addOpen && (
        <ActivityDialog
          mode="add"
          initialCategoryId={addInitialCategoryId ?? visibleRefs[0]?.id ?? LEISURE_CATEGORIES[0]}
          visibleRefs={visibleRefs}
          onClose={() => {
            setAddOpen(false);
            setAddInitialCategoryId(null);
          }}
        />
      )}

      {editingActivity && (
        <ActivityDialog
          mode="edit"
          activity={editingActivity}
          initialCategoryId={editingActivity.category}
          visibleRefs={visibleRefs}
          onClose={() => setEditingActivity(null)}
        />
      )}

      {checkingOff && (
        <CheckOffDialog
          activity={checkingOff}
          onClose={() => setCheckingOff(null)}
        />
      )}

      {pickingCategory && (
        <PickActivityDialog
          categoryRef={pickingCategory}
          activities={activitiesByCategoryId[pickingCategory.id]?.filter((a) => a.enabled) ?? []}
          onPick={(a) => {
            setPickingCategory(null);
            setCheckingOff(a);
          }}
          onLogCategory={() => {
            setQuickLogCategory(pickingCategory);
            setPickingCategory(null);
          }}
          onAddActivity={() => {
            setAddInitialCategoryId(pickingCategory.id);
            setPickingCategory(null);
            setAddOpen(true);
          }}
          onClose={() => setPickingCategory(null)}
        />
      )}

      {quickLogCategory && (
        <LogCategoryDialog
          categoryRef={quickLogCategory}
          onClose={() => setQuickLogCategory(null)}
        />
      )}

      {addCustomOpen && (
        <AddCustomCategoryDialog
          onSave={(label, emoji) => {
            addCustomCategory(label, emoji);
            setAddCustomOpen(false);
          }}
          onClose={() => setAddCustomOpen(false)}
        />
      )}

      <LogPastLeisureDialog open={logPastOpen} onClose={() => setLogPastOpen(false)} />
    </div>
  );
}

// ── Dialog components ────────────────────────────────────────────────────

interface ActivityDialogProps {
  mode: 'add' | 'edit';
  activity?: LeisureActivity;
  initialCategoryId: string;
  visibleRefs: LeisureCategoryRef[];
  onClose: () => void;
}

function ActivityDialog({
  mode,
  activity,
  initialCategoryId,
  visibleRefs,
  onClose,
}: ActivityDialogProps) {
  const addActivity = useLeisureStore((s) => s.addActivity);
  const updateActivity = useLeisureStore((s) => s.updateActivity);
  const [name, setName] = useState(activity?.name ?? '');
  const [categoryId, setCategoryId] = useState(initialCategoryId);
  const [duration, setDuration] = useState(
    activity?.default_duration_minutes != null
      ? String(activity.default_duration_minutes)
      : '',
  );

  const submit = async () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    const dur = duration.trim() === '' ? null : Number(duration);
    if (mode === 'add') {
      await addActivity({ name: trimmed, categoryId, defaultDurationMinutes: dur });
    } else if (activity) {
      await updateActivity(activity, {
        name: trimmed,
        categoryId,
        defaultDurationMinutes: dur,
      });
    }
    onClose();
  };

  return (
    <DialogShell onClose={onClose} title={mode === 'add' ? 'Add Activity' : 'Edit Activity'}>
      <label className="block text-sm">
        <span className="text-[var(--color-text-secondary)]">Name</span>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="mt-1 w-full rounded border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2 text-sm text-[var(--color-text-primary)]"
        />
      </label>
      <label className="block text-sm">
        <span className="text-[var(--color-text-secondary)]">Category</span>
        <select
          value={categoryId}
          onChange={(e) => setCategoryId(e.target.value)}
          className="mt-1 w-full rounded border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2 text-sm text-[var(--color-text-primary)]"
        >
          {visibleRefs
            .filter((r) => r.kind === 'built-in')
            .map((r) => (
              <option key={r.id} value={r.id}>
                {r.emoji} {r.label}
              </option>
            ))}
        </select>
      </label>
      <label className="block text-sm">
        <span className="text-[var(--color-text-secondary)]">
          Default duration (min, optional)
        </span>
        <input
          type="number"
          inputMode="numeric"
          min={1}
          value={duration}
          onChange={(e) => setDuration(e.target.value.replace(/[^0-9]/g, ''))}
          className="mt-1 w-full rounded border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2 text-sm text-[var(--color-text-primary)]"
        />
      </label>
      <div className="mt-3 flex justify-end gap-2">
        <button
          type="button"
          onClick={onClose}
          className="rounded px-3 py-1.5 text-sm text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-tertiary)]"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={submit}
          disabled={!name.trim()}
          className="rounded bg-[var(--color-accent)] px-3 py-1.5 text-sm text-white disabled:opacity-40"
        >
          {mode === 'add' ? 'Add' : 'Save'}
        </button>
      </div>
    </DialogShell>
  );
}

interface CheckOffDialogProps {
  activity: LeisureActivity;
  onClose: () => void;
}

function CheckOffDialog({ activity, onClose }: CheckOffDialogProps) {
  const checkOff = useLeisureStore((s) => s.checkOffActivity);
  const [duration, setDuration] = useState(
    String(activity.default_duration_minutes ?? 30),
  );
  const minutes = Number(duration);
  return (
    <DialogShell onClose={onClose} title={`Log ${activity.name}`}>
      <label className="block text-sm">
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
      <div className="mt-3 flex justify-end gap-2">
        <button
          type="button"
          onClick={onClose}
          className="rounded px-3 py-1.5 text-sm text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-tertiary)]"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={async () => {
            if (minutes < 1) return;
            await checkOff(activity, minutes);
            onClose();
          }}
          disabled={minutes < 1}
          className="rounded bg-[var(--color-accent)] px-3 py-1.5 text-sm text-white disabled:opacity-40"
        >
          Log
        </button>
      </div>
    </DialogShell>
  );
}

interface LogCategoryDialogProps {
  categoryRef: LeisureCategoryRef;
  onClose: () => void;
}

function LogCategoryDialog({ categoryRef: ref, onClose }: LogCategoryDialogProps) {
  const logCategorySession = useLeisureStore((s) => s.logCategorySession);
  const [duration, setDuration] = useState('30');
  const minutes = Number(duration);
  return (
    <DialogShell onClose={onClose} title={`Log ${ref.emoji} ${ref.label}`}>
      <p className="text-sm text-[var(--color-text-secondary)]">
        How long did you spend?
      </p>
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
      <div className="mt-3 flex justify-end gap-2">
        <button
          type="button"
          onClick={onClose}
          className="rounded px-3 py-1.5 text-sm text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-tertiary)]"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={async () => {
            if (minutes < 1) return;
            await logCategorySession(ref.id, minutes);
            onClose();
          }}
          disabled={minutes < 1}
          className="rounded bg-[var(--color-accent)] px-3 py-1.5 text-sm text-white disabled:opacity-40"
        >
          Log
        </button>
      </div>
    </DialogShell>
  );
}

interface PickActivityDialogProps {
  categoryRef: LeisureCategoryRef;
  activities: LeisureActivity[];
  onPick: (activity: LeisureActivity) => void;
  onLogCategory: () => void;
  onAddActivity: () => void;
  onClose: () => void;
}

function PickActivityDialog({
  categoryRef: ref,
  activities,
  onPick,
  onLogCategory,
  onAddActivity,
  onClose,
}: PickActivityDialogProps) {
  return (
    <DialogShell onClose={onClose} title={`${ref.emoji} ${ref.label}`}>
      {activities.length === 0 ? (
        <p className="text-sm text-[var(--color-text-secondary)]">
          No activities in this category yet.
        </p>
      ) : (
        <ul className="divide-y divide-[var(--color-border)]">
          {activities.map((a) => (
            <li key={a.id}>
              <button
                type="button"
                onClick={() => onPick(a)}
                className="flex w-full items-center justify-between py-2 text-left text-sm"
              >
                <div>
                  <div className="text-[var(--color-text-primary)]">{a.name}</div>
                  {a.default_duration_minutes != null && (
                    <div className="text-xs text-[var(--color-text-secondary)]">
                      {a.default_duration_minutes} min default
                    </div>
                  )}
                </div>
                <Check className="h-4 w-4 text-[var(--color-accent)]" />
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="mt-3 flex flex-col gap-2">
        <button
          type="button"
          onClick={onLogCategory}
          className="rounded border border-[var(--color-border)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-bg-tertiary)]"
        >
          Log without a specific activity
        </button>
        <button
          type="button"
          onClick={onAddActivity}
          className="rounded border border-[var(--color-border)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-bg-tertiary)]"
        >
          Add activity to {ref.label}
        </button>
      </div>
    </DialogShell>
  );
}

interface AddCustomCategoryDialogProps {
  onSave: (label: string, emoji: string) => void;
  onClose: () => void;
}

function AddCustomCategoryDialog({ onSave, onClose }: AddCustomCategoryDialogProps) {
  const [label, setLabel] = useState('');
  const [emoji, setEmoji] = useState('✨');
  return (
    <DialogShell onClose={onClose} title="Add Custom Category">
      <p className="text-xs text-[var(--color-text-secondary)]">
        Custom categories stay on this device. Activities tagged with one
        won't sync to other clients.
      </p>
      <label className="mt-2 block text-sm">
        <span className="text-[var(--color-text-secondary)]">Emoji</span>
        <input
          value={emoji}
          onChange={(e) => setEmoji(e.target.value)}
          maxLength={4}
          className="mt-1 w-full rounded border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2 text-sm text-[var(--color-text-primary)]"
        />
      </label>
      <label className="block text-sm">
        <span className="text-[var(--color-text-secondary)]">Name</span>
        <input
          value={label}
          onChange={(e) => setLabel(e.target.value)}
          className="mt-1 w-full rounded border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-2 text-sm text-[var(--color-text-primary)]"
        />
      </label>
      <div className="mt-3 flex justify-end gap-2">
        <button
          type="button"
          onClick={onClose}
          className="rounded px-3 py-1.5 text-sm text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-tertiary)]"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={() => onSave(label, emoji)}
          disabled={!label.trim() || !emoji.trim()}
          className="rounded bg-[var(--color-accent)] px-3 py-1.5 text-sm text-white disabled:opacity-40"
        >
          Add
        </button>
      </div>
    </DialogShell>
  );
}

// ── Shared dialog shell ─────────────────────────────────────────────────

interface DialogShellProps {
  title: string;
  children: React.ReactNode;
  onClose: () => void;
}

function DialogShell({ title, children, onClose }: DialogShellProps) {
  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="w-full max-w-md space-y-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-primary)] p-4">
        <div className="flex items-center justify-between">
          <h3 className="text-base font-semibold text-[var(--color-text-primary)]">
            {title}
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="rounded p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-tertiary)]"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

export default LeisurePoolScreen;
