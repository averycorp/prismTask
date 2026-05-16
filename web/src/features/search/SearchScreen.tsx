import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  ArrowLeft,
  CheckSquare,
  FileText,
  FolderKanban,
  Hash,
  Repeat,
  Search,
  StickyNote,
  X,
  type LucideIcon,
} from 'lucide-react';

import { EmptyState } from '@/components/ui/EmptyState';
import { Input } from '@/components/ui/Input';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import { useHabitStore } from '@/stores/habitStore';
import { useTagStore } from '@/stores/tagStore';
import {
  SEARCH_TYPE_LABELS,
  groupResults,
  searchAll,
  type SearchResultType,
  type SearchTypeFilter,
} from './searchFilters';

const FILTER_ORDER: readonly { value: SearchTypeFilter; label: string }[] = [
  { value: 'all', label: 'All Results' },
  { value: 'task', label: 'Tasks' },
  { value: 'project', label: 'Projects' },
  { value: 'habit', label: 'Habits' },
  { value: 'note', label: 'Notes' },
  { value: 'tag', label: 'Tags' },
];

const TYPE_ICONS: Record<SearchResultType, LucideIcon> = {
  task: CheckSquare,
  project: FolderKanban,
  habit: Repeat,
  note: StickyNote,
  tag: Hash,
};

const DEBOUNCE_MS = 300;

export function SearchScreen() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  // Read seeded query/filter from the URL so deep-links (e.g. shared
  // search links, the keyboard shortcut prefilling) survive route
  // mounts. Persist changes back to the URL on debounce so back/forward
  // restores the user's last query.
  const initialQuery = searchParams.get('q') ?? '';
  const initialFilter =
    (searchParams.get('type') as SearchTypeFilter | null) ?? 'all';

  // Zustand v5 stable selectors — pulling each slice independently to
  // avoid the fresh-object selector pitfall flagged in memory.
  const tasks = useTaskStore((s) => s.tasks);
  const todayTasks = useTaskStore((s) => s.todayTasks);
  const overdueTasks = useTaskStore((s) => s.overdueTasks);
  const upcomingTasks = useTaskStore((s) => s.upcomingTasks);
  const projects = useProjectStore((s) => s.projects);
  const habits = useHabitStore((s) => s.habits);
  const tagList = useTagStore((s) => s.tags);

  const [query, setQuery] = useState(initialQuery);
  const [debouncedQuery, setDebouncedQuery] = useState(initialQuery);
  const [filter, setFilter] = useState<SearchTypeFilter>(initialFilter);
  const inputRef = useRef<HTMLInputElement>(null);

  // Auto-focus on mount so the screen is keyboard-friendly.
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Debounce + URL sync.
  useEffect(() => {
    const t = setTimeout(() => {
      setDebouncedQuery(query);
      const next = new URLSearchParams(searchParams);
      if (query.trim()) next.set('q', query.trim());
      else next.delete('q');
      if (filter !== 'all') next.set('type', filter);
      else next.delete('type');
      setSearchParams(next, { replace: true });
    }, DEBOUNCE_MS);
    return () => clearTimeout(t);
    // `searchParams` and `setSearchParams` are stable across renders for
    // a given URL; depending on them would re-run the debounce when the
    // user clicks back to the route. Tracking just (query, filter)
    // keeps the timer cancellation precise.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, filter]);

  // The task store keeps multiple slices (today / overdue / upcoming
  // / by-project) populated by different screens. Merge them by ID so
  // a search hit isn't missed just because the user hasn't visited the
  // project list yet this session.
  const allTasks = useMemo(() => {
    const byId = new Map<string, (typeof tasks)[number]>();
    for (const arr of [tasks, todayTasks, overdueTasks, upcomingTasks]) {
      for (const t of arr) byId.set(t.id, t);
    }
    return Array.from(byId.values());
  }, [tasks, todayTasks, overdueTasks, upcomingTasks]);

  const results = useMemo(
    () =>
      searchAll(
        debouncedQuery,
        { tasks: allTasks, projects, habits, tags: tagList },
        filter,
      ),
    [debouncedQuery, allTasks, projects, habits, tagList, filter],
  );

  const grouped = useMemo(() => groupResults(results), [results]);
  const isEmpty = debouncedQuery.trim() !== '' && results.length === 0;
  const showPlaceholder = debouncedQuery.trim() === '';

  return (
    <div className="mx-auto w-full max-w-3xl">
      {/* Header */}
      <div className="mb-4 flex items-center gap-2">
        <button
          type="button"
          onClick={() => navigate(-1)}
          className="rounded-md p-1.5 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
          aria-label="Back"
        >
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          Search
        </h1>
      </div>

      {/* Search input */}
      <div className="relative mb-3">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--color-text-secondary)]" />
        <Input
          ref={inputRef}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search Tasks, Projects, Habits, Notes, Tags"
          className="pl-9 pr-9"
          aria-label="Search query"
          data-testid="search-input"
        />
        {query && (
          <button
            type="button"
            onClick={() => setQuery('')}
            className="absolute right-2 top-1/2 -translate-y-1/2 rounded-md p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
            aria-label="Clear search"
          >
            <X className="h-4 w-4" />
          </button>
        )}
      </div>

      {/* Filter chips */}
      <div className="mb-4 flex flex-wrap gap-2" role="tablist" aria-label="Result type filter">
        {FILTER_ORDER.map((opt) => {
          const active = filter === opt.value;
          return (
            <button
              key={opt.value}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => setFilter(opt.value)}
              className={`rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
                active
                  ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                  : 'border-[var(--color-border)] bg-[var(--color-bg-card)] text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
              }`}
            >
              {opt.label}
            </button>
          );
        })}
      </div>

      {/* Results */}
      {showPlaceholder && (
        <EmptyState
          icon={<Search className="h-8 w-8" />}
          title="Search"
          description="Search across Tasks, Projects, Habits, Notes, and Tags."
        />
      )}
      {isEmpty && (
        <EmptyState
          icon={<FileText className="h-8 w-8" />}
          title="No Results"
          description={`No matches for "${debouncedQuery}". Try different keywords.`}
        />
      )}
      {!showPlaceholder && !isEmpty && (
        <div className="flex flex-col gap-6" data-testid="search-results">
          {grouped.map((section) => (
            <section key={section.type}>
              <h2 className="mb-2 text-xs font-bold uppercase tracking-wider text-[var(--color-accent)]">
                {SEARCH_TYPE_LABELS[section.type]} ({section.results.length})
              </h2>
              <ul className="flex flex-col gap-2">
                {section.results.map((row) => {
                  const Icon = TYPE_ICONS[row.type];
                  return (
                    <li key={`${row.type}-${row.id}`}>
                      <button
                        type="button"
                        onClick={() => navigate(row.href)}
                        data-testid={`search-result-${row.type}-${row.id}`}
                        className="flex w-full items-center gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2.5 text-left hover:border-[var(--color-accent)]"
                      >
                        <span
                          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full"
                          style={{
                            backgroundColor: row.color
                              ? `${row.color}33`
                              : 'var(--color-bg-secondary)',
                            color: row.color ?? 'var(--color-text-secondary)',
                          }}
                        >
                          <Icon className="h-4 w-4" />
                        </span>
                        <div className="flex-1 min-w-0">
                          <p className="truncate text-sm font-medium text-[var(--color-text-primary)]">
                            {row.title}
                          </p>
                          {row.snippet && (
                            <p className="truncate text-xs text-[var(--color-text-secondary)]">
                              {row.snippet}
                            </p>
                          )}
                        </div>
                        <span className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-[var(--color-text-secondary)]">
                          {SEARCH_TYPE_LABELS[row.type].slice(0, -1)}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            </section>
          ))}
        </div>
      )}
    </div>
  );
}
