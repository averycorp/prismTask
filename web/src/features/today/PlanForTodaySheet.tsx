import { useEffect, useMemo, useRef, useState } from 'react';
import { Search, X, ChevronDown, ChevronUp, Pin, AlertCircle } from 'lucide-react';
import { format, parseISO, addDays } from 'date-fns';
import { toast } from 'sonner';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import type { Task } from '@/types/task';
import {
  bucketUpcoming,
  priorityRank,
  dueRank,
} from '@/features/today/planForTodayBuckets';

/**
 * "Plan for Today" sheet — web port of Android's
 * `app/src/main/java/com/averycorp/prismtask/ui/screens/today/components/PlanForTodaySheet.kt`.
 *
 * Lets the user pull undone tasks (overdue + upcoming) into the Today
 * plan in a single batch. Tapping a row toggles its plan-state (plan if
 * not on today, unplan if currently on today). Long-press / shift-click
 * enters multi-select mode for bulk-plan operations. "Plan All Overdue"
 * is a one-tap shortcut.
 *
 * Scope vs Android source:
 *  - DROPPED in this first cut: inline QuickAddBar, template quick-chip
 *    row, multi-create batch command. Android source is 691 LOC; we
 *    target the highest-value ~300 LOC of pull-and-plan flow first.
 *    Templates / multi-create are tracked as Batch 2.1 follow-ups.
 *  - Sort + search + per-bucket collapse + multi-select are all ported.
 *  - Web uses ISO date strings (`YYYY-MM-DD`) instead of epoch-ms; the
 *    Today bucket is the user's logical SoD-aligned today.
 *
 * Closes Parity Batch 2 § C.1b.
 */

type SortMode = 'PRIORITY' | 'DUE_DATE' | 'PROJECT';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  /** ISO `YYYY-MM-DD` of the user's logical "today" (SoD-aware). */
  todayIso: string;
}

export function PlanForTodaySheet({ isOpen, onClose, todayIso }: Props) {
  const { todayTasks, overdueTasks, upcomingTasks, updateTask, fetchToday, fetchOverdue, fetchUpcoming } =
    useTaskStore();
  const { projects } = useProjectStore();

  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<SortMode>('DUE_DATE');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [multiSelect, setMultiSelect] = useState(false);
  const [busy, setBusy] = useState(false);

  // Per-bucket collapse state. Defaults match Android (overdue + tomorrow +
  // thisWeek + nextWeek expanded; later + noDate collapsed).
  const [expanded, setExpanded] = useState<Record<string, boolean>>({
    overdue: true,
    tomorrow: true,
    thisWeek: true,
    nextWeek: true,
    later: false,
    noDate: false,
  });

  // Reset transient state every time the sheet re-opens so previous picks
  // don't carry over.
  useEffect(() => {
    if (isOpen) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- parity batch follow-up; see #1573
      setSearch('');
      setSelectedIds(new Set());
      setMultiSelect(false);
    }
  }, [isOpen]);

  const projectMap = useMemo(
    () => new Map(projects.map((p) => [p.id, p])),
    [projects],
  );

  // Filter pipeline ----------------------------------------------------------
  const filterFn = (t: Task) => {
    if (t.status === 'done' || t.status === 'cancelled') return false;
    if (!search.trim()) return true;
    return t.title.toLowerCase().includes(search.trim().toLowerCase());
  };

  const sortFn = (a: Task, b: Task): number => {
    if (sort === 'PRIORITY') {
      const pa = priorityRank(a.priority);
      const pb = priorityRank(b.priority);
      if (pa !== pb) return pb - pa;
      return dueRank(a) - dueRank(b);
    }
    if (sort === 'DUE_DATE') {
      const da = dueRank(a);
      const db = dueRank(b);
      if (da !== db) return da - db;
      return priorityRank(b.priority) - priorityRank(a.priority);
    }
    // PROJECT
    const na = a.project_id ? (projectMap.get(a.project_id)?.title ?? 'zzz') : 'zzz';
    const nb = b.project_id ? (projectMap.get(b.project_id)?.title ?? 'zzz') : 'zzz';
    const cmp = na.localeCompare(nb);
    if (cmp !== 0) return cmp;
    return priorityRank(b.priority) - priorityRank(a.priority);
  };

  const planned = useMemo(
    () => todayTasks.filter(filterFn).slice().sort(sortFn),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [todayTasks, search, sort, projectMap],
  );
  const overdue = useMemo(
    () => overdueTasks.filter(filterFn).slice().sort(sortFn),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [overdueTasks, search, sort, projectMap],
  );

  // Bucket the upcoming pool (excluding rows already in planned/overdue) into
  // tomorrow / this-week / next-week / later / no-date.
  const buckets = useMemo(() => {
    const seen = new Set<string>([
      ...planned.map((t) => t.id),
      ...overdue.map((t) => t.id),
    ]);
    const filtered = upcomingTasks.filter((t) => filterFn(t) && !seen.has(t.id));
    const raw = bucketUpcoming(filtered, todayIso);
    return {
      tomorrow: raw.tomorrow.slice().sort(sortFn),
      thisWeek: raw.thisWeek.slice().sort(sortFn),
      nextWeek: raw.nextWeek.slice().sort(sortFn),
      later: raw.later.slice().sort(sortFn),
      noDate: raw.noDate.slice().sort(sortFn),
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [upcomingTasks, planned, overdue, todayIso, sort, search, projectMap]);

  const hasAnyUpcoming =
    overdue.length > 0 ||
    buckets.tomorrow.length > 0 ||
    buckets.thisWeek.length > 0 ||
    buckets.nextWeek.length > 0 ||
    buckets.later.length > 0 ||
    buckets.noDate.length > 0;
  const empty = planned.length === 0 && !hasAnyUpcoming;

  // Action helpers -----------------------------------------------------------
  const refresh = async () => {
    await Promise.all([fetchToday(), fetchOverdue(), fetchUpcoming(7)]);
  };

  const planOne = async (id: string) => {
    setBusy(true);
    try {
      await updateTask(id, { due_date: todayIso });
      await refresh();
    } catch {
      toast.error('Failed To Plan Task');
    } finally {
      setBusy(false);
    }
  };

  const unplanOne = async (id: string) => {
    setBusy(true);
    try {
      // Setting due_date back to undefined isn't supported by TaskUpdate
      // (string-only); the closest we can do without breaking schema is
      // pushing the task one day forward so it leaves the today bucket.
      const tomorrow = format(addDays(parseISO(todayIso), 1), 'yyyy-MM-dd');
      await updateTask(id, { due_date: tomorrow });
      await refresh();
    } catch {
      toast.error('Failed To Unplan Task');
    } finally {
      setBusy(false);
    }
  };

  const planMany = async (ids: string[]) => {
    if (ids.length === 0) return;
    setBusy(true);
    try {
      await Promise.all(ids.map((id) => updateTask(id, { due_date: todayIso })));
      toast.success(`Planned ${ids.length} task${ids.length === 1 ? '' : 's'} for Today`);
      await refresh();
      setSelectedIds(new Set());
      setMultiSelect(false);
    } catch {
      toast.error('Failed To Plan Some Tasks');
    } finally {
      setBusy(false);
    }
  };

  const planAllOverdue = async () => {
    if (overdue.length === 0) return;
    await planMany(overdue.map((t) => t.id));
  };

  const onTapTask = (task: Task, isPlanned: boolean) => {
    if (multiSelect) {
      setSelectedIds((prev) => {
        const next = new Set(prev);
        if (next.has(task.id)) next.delete(task.id);
        else next.add(task.id);
        return next;
      });
      return;
    }
    if (isPlanned) void unplanOne(task.id);
    else void planOne(task.id);
  };

  const onLongPressTask = (task: Task) => {
    if (multiSelect) return;
    setMultiSelect(true);
    setSelectedIds(new Set([task.id]));
  };

  const toggleBucket = (key: string) => {
    setExpanded((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  // Render -------------------------------------------------------------------
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Plan For Today"
      size="lg"
      footer={
        multiSelect && selectedIds.size > 0 ? (
          <div className="flex w-full items-center gap-2">
            <span className="flex-1 text-sm text-[var(--color-text-secondary)]">
              {selectedIds.size} selected
            </span>
            <Button
              variant="ghost"
              onClick={() => {
                setMultiSelect(false);
                setSelectedIds(new Set());
              }}
            >
              Cancel
            </Button>
            <Button
              onClick={() => void planMany([...selectedIds])}
              loading={busy}
            >
              <Pin className="h-4 w-4" />
              Plan Selected ({selectedIds.size})
            </Button>
          </div>
        ) : (
          <div className="flex w-full justify-end">
            <Button variant="ghost" onClick={onClose}>
              Done
            </Button>
          </div>
        )
      }
    >
      <div className="flex max-h-[70vh] flex-col gap-3">
        {/* Search */}
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--color-text-secondary)]" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search Tasks..."
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] py-2 pl-9 pr-9 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            aria-label="Search tasks"
          />
          {search && (
            <button
              onClick={() => setSearch('')}
              aria-label="Clear search"
              className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-card)]"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          )}
        </div>

        {/* Sort chips */}
        <div className="flex gap-2">
          {(['PRIORITY', 'DUE_DATE', 'PROJECT'] as const).map((mode) => (
            <button
              key={mode}
              onClick={() => setSort(mode)}
              className={`rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
                sort === mode
                  ? 'border-[var(--color-accent)] bg-[var(--color-accent)] text-white'
                  : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]'
              }`}
            >
              {mode === 'PRIORITY' ? 'Priority' : mode === 'DUE_DATE' ? 'Due Date' : 'Project'}
            </button>
          ))}
        </div>

        {/* Scrollable list */}
        <div className="-mx-1 flex-1 overflow-y-auto px-1">
          {empty && (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <p className="text-sm text-[var(--color-text-secondary)]">
                {search.trim()
                  ? 'No Tasks Match Your Search.'
                  : 'No Upcoming Tasks To Plan. Create One From The Tasks Tab!'}
              </p>
            </div>
          )}

          {!empty && (
            <>
              {planned.length > 0 && (
                <BucketHeader
                  title="Planned For Today"
                  count={planned.length}
                  expanded
                  toggle={undefined}
                />
              )}
              {planned.map((task) => (
                <TaskCard
                  key={`planned_${task.id}`}
                  task={task}
                  projectName={
                    task.project_id ? projectMap.get(task.project_id)?.title : null
                  }
                  isPlanned
                  isOverdue={false}
                  multiSelect={multiSelect}
                  selected={selectedIds.has(task.id)}
                  busy={busy}
                  onTap={() => onTapTask(task, true)}
                  onLongPress={() => onLongPressTask(task)}
                />
              ))}

              {overdue.length > 0 && (
                <BucketHeader
                  title="From Earlier"
                  count={overdue.length}
                  expanded={!!expanded.overdue}
                  toggle={() => toggleBucket('overdue')}
                  trailing={
                    <button
                      onClick={() => void planAllOverdue()}
                      disabled={busy}
                      className="text-xs font-medium text-[var(--color-accent)] hover:underline disabled:opacity-50"
                    >
                      Plan All
                    </button>
                  }
                  warn
                />
              )}
              {expanded.overdue &&
                overdue.map((task) => (
                  <TaskCard
                    key={`overdue_${task.id}`}
                    task={task}
                    projectName={
                      task.project_id ? projectMap.get(task.project_id)?.title : null
                    }
                    isPlanned={false}
                    isOverdue
                    multiSelect={multiSelect}
                    selected={selectedIds.has(task.id)}
                    busy={busy}
                    onTap={() => onTapTask(task, false)}
                    onLongPress={() => onLongPressTask(task)}
                  />
                ))}

              <BucketGroup
                k="tomorrow"
                title="Tomorrow"
                tasks={buckets.tomorrow}
                expanded={!!expanded.tomorrow}
                toggle={() => toggleBucket('tomorrow')}
                projectMap={projectMap}
                multiSelect={multiSelect}
                selectedIds={selectedIds}
                busy={busy}
                onTap={onTapTask}
                onLongPress={onLongPressTask}
              />
              <BucketGroup
                k="thisWeek"
                title="This Week"
                tasks={buckets.thisWeek}
                expanded={!!expanded.thisWeek}
                toggle={() => toggleBucket('thisWeek')}
                projectMap={projectMap}
                multiSelect={multiSelect}
                selectedIds={selectedIds}
                busy={busy}
                onTap={onTapTask}
                onLongPress={onLongPressTask}
              />
              <BucketGroup
                k="nextWeek"
                title="Next Week"
                tasks={buckets.nextWeek}
                expanded={!!expanded.nextWeek}
                toggle={() => toggleBucket('nextWeek')}
                projectMap={projectMap}
                multiSelect={multiSelect}
                selectedIds={selectedIds}
                busy={busy}
                onTap={onTapTask}
                onLongPress={onLongPressTask}
              />
              <BucketGroup
                k="later"
                title="Later"
                tasks={buckets.later}
                expanded={!!expanded.later}
                toggle={() => toggleBucket('later')}
                projectMap={projectMap}
                multiSelect={multiSelect}
                selectedIds={selectedIds}
                busy={busy}
                onTap={onTapTask}
                onLongPress={onLongPressTask}
              />
              <BucketGroup
                k="noDate"
                title="No Date"
                tasks={buckets.noDate}
                expanded={!!expanded.noDate}
                toggle={() => toggleBucket('noDate')}
                projectMap={projectMap}
                multiSelect={multiSelect}
                selectedIds={selectedIds}
                busy={busy}
                onTap={onTapTask}
                onLongPress={onLongPressTask}
              />
            </>
          )}
        </div>
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------

function BucketGroup({
  k,
  title,
  tasks,
  expanded,
  toggle,
  projectMap,
  multiSelect,
  selectedIds,
  busy,
  onTap,
  onLongPress,
}: {
  k: string;
  title: string;
  tasks: Task[];
  expanded: boolean;
  toggle: () => void;
  projectMap: Map<string, { title: string }>;
  multiSelect: boolean;
  selectedIds: Set<string>;
  busy: boolean;
  onTap: (task: Task, isPlanned: boolean) => void;
  onLongPress: (task: Task) => void;
}) {
  if (tasks.length === 0) return null;
  return (
    <>
      <BucketHeader
        title={title}
        count={tasks.length}
        expanded={expanded}
        toggle={toggle}
      />
      {expanded &&
        tasks.map((task) => (
          <TaskCard
            key={`${k}_${task.id}`}
            task={task}
            projectName={
              task.project_id ? projectMap.get(task.project_id)?.title : null
            }
            isPlanned={false}
            isOverdue={false}
            multiSelect={multiSelect}
            selected={selectedIds.has(task.id)}
            busy={busy}
            onTap={() => onTap(task, false)}
            onLongPress={() => onLongPress(task)}
          />
        ))}
    </>
  );
}

function BucketHeader({
  title,
  count,
  expanded,
  toggle,
  trailing,
  warn = false,
}: {
  title: string;
  count: number;
  expanded: boolean;
  toggle: (() => void) | undefined;
  trailing?: React.ReactNode;
  warn?: boolean;
}) {
  const Wrapper = toggle ? 'button' : 'div';
  return (
    <div className="mt-2 flex items-center gap-2 px-1 py-2">
      <Wrapper
        onClick={toggle}
        className={`flex flex-1 items-center gap-2 text-left ${toggle ? 'cursor-pointer' : ''}`}
      >
        {warn && <AlertCircle className="h-3.5 w-3.5 text-amber-600" />}
        <span
          className={`text-xs font-bold uppercase tracking-wide ${
            warn
              ? 'text-amber-700'
              : 'text-[var(--color-accent)]'
          }`}
        >
          {title}
        </span>
        <span className="text-xs text-[var(--color-text-secondary)]">{count}</span>
        {toggle && (
          <span className="ml-auto text-[var(--color-text-secondary)]">
            {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
          </span>
        )}
      </Wrapper>
      {trailing}
    </div>
  );
}

function TaskCard({
  task,
  projectName,
  isPlanned,
  isOverdue,
  multiSelect,
  selected,
  busy,
  onTap,
  onLongPress,
}: {
  task: Task;
  projectName: string | null | undefined;
  isPlanned: boolean;
  isOverdue: boolean;
  multiSelect: boolean;
  selected: boolean;
  busy: boolean;
  onTap: () => void;
  onLongPress: () => void;
}) {
  // Long-press detection — 500ms threshold, matches Android default.
  // useRef so the timer ID survives re-renders (and so the eslint
  // react-hooks/immutability rule stays happy — `let` outside a hook
  // would complain about post-render reassignment).
  const pressTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const handleDown = () => {
    pressTimerRef.current = setTimeout(() => {
      onLongPress();
      pressTimerRef.current = null;
    }, 500);
  };
  const handleUp = () => {
    if (pressTimerRef.current) {
      clearTimeout(pressTimerRef.current);
      pressTimerRef.current = null;
    }
  };

  const bg = selected
    ? 'bg-[var(--color-accent)]/15 border-[var(--color-accent)]'
    : isPlanned
      ? 'bg-[var(--color-accent)]/8 border-[var(--color-border)]'
      : 'bg-[var(--color-bg-card)] border-[var(--color-border)]';

  return (
    <button
      type="button"
      onClick={onTap}
      onMouseDown={handleDown}
      onMouseUp={handleUp}
      onMouseLeave={handleUp}
      onTouchStart={handleDown}
      onTouchEnd={handleUp}
      onTouchCancel={handleUp}
      onContextMenu={(e) => {
        // Right-click acts as long-press on desktop.
        e.preventDefault();
        onLongPress();
      }}
      disabled={busy}
      className={`my-1 flex w-full items-center gap-2 rounded-lg border px-3 py-2 text-left transition-colors hover:bg-[var(--color-bg-secondary)] disabled:cursor-not-allowed disabled:opacity-60 ${bg}`}
    >
      {multiSelect && (
        <span
          className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full border ${
            selected
              ? 'border-[var(--color-accent)] bg-[var(--color-accent)] text-white'
              : 'border-[var(--color-border)]'
          }`}
        >
          {selected && <span className="text-[10px]">✓</span>}
        </span>
      )}
      {task.priority > 0 && (
        <span
          aria-hidden
          className="h-2 w-2 shrink-0 rounded-full"
          style={{ background: priorityColor(task.priority) }}
        />
      )}
      <span className="flex-1 truncate text-sm font-medium text-[var(--color-text-primary)]">
        {task.title}
      </span>
      {task.due_date && (
        <span
          className={`shrink-0 text-xs ${
            isOverdue
              ? 'text-amber-700'
              : 'text-[var(--color-text-secondary)]'
          }`}
        >
          {format(parseISO(task.due_date), 'MMM d')}
        </span>
      )}
      {projectName && (
        <span className="shrink-0 truncate rounded bg-[var(--color-bg-secondary)] px-1.5 py-0.5 text-[10px] text-[var(--color-text-secondary)]">
          {projectName}
        </span>
      )}
      {isPlanned && (
        <span className="shrink-0 rounded bg-[var(--color-accent)]/15 px-1.5 py-0.5 text-[10px] font-medium text-[var(--color-accent)]">
          Planned
        </span>
      )}
    </button>
  );
}


function priorityColor(p: number): string {
  if (p === 1) return '#dc2626'; // urgent — red-600
  if (p === 2) return '#f97316'; // high — orange-500
  if (p === 3) return '#eab308'; // medium — yellow-500
  return '#94a3b8'; // low — slate-400
}
