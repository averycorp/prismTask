import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { Search, X, FileText, Loader2, ArrowRight } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { DueDateLabel } from './DueDateLabel';
import { StatusBadge } from './StatusBadge';
import { PriorityBadge } from './PriorityBadge';
import { useTaskStore } from '@/stores/taskStore';
import { getFirebaseUid } from '@/stores/firebaseUid';
import * as firestoreTasks from '@/api/firestore/tasks';
import {
  filterTasksByQuery,
  searchRoutes,
  type NavRoute,
} from '@/features/search/searchFilters';
import type { Task } from '@/types/task';

interface SearchModalProps {
  isOpen: boolean;
  onClose: () => void;
}

type Row =
  | { kind: 'route'; route: NavRoute }
  | { kind: 'task'; task: Task };

export function SearchModal({ isOpen, onClose }: SearchModalProps) {
  const navigate = useNavigate();
  const { setSelectedTask } = useTaskStore();
  const [query, setQuery] = useState('');
  const [allTasks, setAllTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(false);
  const [highlightIndex, setHighlightIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  // Focus input on open + load the live task list so search has parity
  // with what the app displays (the Firestore store is the source of
  // truth; the FastAPI `/search` endpoint is a separate Postgres store).
  useEffect(() => {
    if (!isOpen) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- modal-open reset: clear buffered query/highlight when modal toggles open
    setQuery('');
    setHighlightIndex(0);
    setTimeout(() => inputRef.current?.focus(), 50);
    let cancelled = false;
    setLoading(true);
    (async () => {
      try {
        const tasks = await firestoreTasks.getAllTasks(getFirebaseUid());
        if (!cancelled) setAllTasks(tasks);
      } catch {
        if (!cancelled) setAllTasks([]);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [isOpen]);

  // Escape to close
  useEffect(() => {
    if (!isOpen) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [isOpen, onClose]);

  // Lock body scroll
  useEffect(() => {
    if (isOpen) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => {
      document.body.style.overflow = '';
    };
  }, [isOpen]);

  const routeRows = useMemo<Row[]>(
    () => searchRoutes(query).map((route) => ({ kind: 'route', route }) as const),
    [query],
  );
  const taskRows = useMemo<Row[]>(
    () =>
      filterTasksByQuery(allTasks, query)
        .slice(0, 50)
        .map((task) => ({ kind: 'task', task }) as const),
    [allTasks, query],
  );
  const rows = useMemo<Row[]>(
    () => [...routeRows, ...taskRows],
    [routeRows, taskRows],
  );

  // Keep the highlighted row in range as results change.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- clamp highlight when result set shrinks
    setHighlightIndex((i) => (rows.length === 0 ? 0 : Math.min(i, rows.length - 1)));
  }, [rows.length]);

  const handleSelect = useCallback(
    (row: Row) => {
      if (row.kind === 'task') {
        setSelectedTask(row.task);
        onClose();
        navigate(`/tasks/${row.task.id}`);
      } else {
        onClose();
        navigate(row.route.to);
      }
    },
    [navigate, onClose, setSelectedTask],
  );

  const handleKeyDown = (e: React.KeyboardEvent) => {
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setHighlightIndex((i) => Math.min(i + 1, rows.length - 1));
        break;
      case 'ArrowUp':
        e.preventDefault();
        setHighlightIndex((i) => Math.max(i - 1, 0));
        break;
      case 'Enter':
        e.preventDefault();
        if (rows[highlightIndex]) {
          handleSelect(rows[highlightIndex]);
        }
        break;
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center pt-[15vh]">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50"
        onClick={onClose}
      />

      {/* Modal */}
      <div className="relative z-10 w-full max-w-xl rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] shadow-2xl">
        {/* Search input */}
        <div className="flex items-center gap-3 border-b border-[var(--color-border)] px-4 py-3">
          <Search className="h-5 w-5 shrink-0 text-[var(--color-text-secondary)]" />
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Search tasks and pages..."
            className="flex-1 border-none bg-transparent text-sm text-[var(--color-text-primary)] outline-none placeholder-[var(--color-text-secondary)]"
          />
          {loading && <Loader2 className="h-4 w-4 animate-spin text-[var(--color-accent)]" />}
          <button
            onClick={onClose}
            className="shrink-0 rounded-md p-1 text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Results */}
        <div className="max-h-80 overflow-y-auto p-2">
          {query && rows.length === 0 && !loading && (
            <div className="px-4 py-8 text-center text-sm text-[var(--color-text-secondary)]">
              No results found for "{query}"
            </div>
          )}
          {rows.map((row, index) => {
            const key = row.kind === 'task' ? `task-${row.task.id}` : `route-${row.route.to}`;
            return (
              <button
                key={key}
                onClick={() => handleSelect(row)}
                onMouseEnter={() => setHighlightIndex(index)}
                className={`flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left transition-colors ${
                  highlightIndex === index ? 'bg-[var(--color-bg-secondary)]' : ''
                }`}
              >
                {row.kind === 'task' ? (
                  <>
                    <FileText className="h-4 w-4 shrink-0 text-[var(--color-text-secondary)]" />
                    <div className="flex-1 min-w-0">
                      <p className="truncate text-sm font-medium text-[var(--color-text-primary)]">
                        {row.task.title}
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
                      <StatusBadge status={row.task.status} />
                      <PriorityBadge priority={row.task.priority} iconOnly />
                      <DueDateLabel date={row.task.due_date} />
                    </div>
                  </>
                ) : (
                  <>
                    <ArrowRight className="h-4 w-4 shrink-0 text-[var(--color-text-secondary)]" />
                    <div className="flex-1 min-w-0">
                      <p className="truncate text-sm font-medium text-[var(--color-text-primary)]">
                        {row.route.label}
                      </p>
                    </div>
                    <span className="shrink-0 rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-[var(--color-text-secondary)]">
                      Page
                    </span>
                  </>
                )}
              </button>
            );
          })}
        </div>

        {/* Footer hint */}
        <div className="flex items-center gap-4 border-t border-[var(--color-border)] px-4 py-2 text-xs text-[var(--color-text-secondary)]">
          <span>
            <kbd className="rounded bg-[var(--color-bg-secondary)] px-1.5 py-0.5 font-mono">
              ↑↓
            </kbd>{' '}
            Navigate
          </span>
          <span>
            <kbd className="rounded bg-[var(--color-bg-secondary)] px-1.5 py-0.5 font-mono">
              ↵
            </kbd>{' '}
            Open
          </span>
          <span>
            <kbd className="rounded bg-[var(--color-bg-secondary)] px-1.5 py-0.5 font-mono">
              Esc
            </kbd>{' '}
            Close
          </span>
        </div>
      </div>
    </div>
  );
}
