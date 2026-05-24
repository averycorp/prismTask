import { useState, useCallback, useRef, useEffect } from 'react';
import { LogOut, Palette, Search } from 'lucide-react';
import { toast } from 'sonner';
import { useAuthStore } from '@/stores/authStore';
import { useThemeStore } from '@/stores/themeStore';
import { useTaskStore } from '@/stores/taskStore';
import { useProjectStore } from '@/stores/projectStore';
import { nonArchivedProjects } from '@/utils/projectFilters';
import { SearchModal } from '@/components/shared/SearchModal';
import { Avatar } from '@/components/ui/Avatar';
import { THEME_ORDER, THEMES } from '@/theme/themes';
import { NLPInput } from '@/components/shared/NLPInput';

export function Header() {
  const [showUserMenu, setShowUserMenu] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const themeKey = useThemeStore((s) => s.themeKey);
  const setThemeKey = useThemeStore((s) => s.setThemeKey);
  const [themeMenuOpen, setThemeMenuOpen] = useState(false);
  const themeMenuRef = useRef<HTMLDivElement>(null);
  const createTask = useTaskStore((s) => s.createTask);
  const projects = useProjectStore((s) => s.projects);

  // Close user menu on outside click
  useEffect(() => {
    if (!showUserMenu) return;
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setShowUserMenu(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [showUserMenu]);

  const handleTaskCreate = useCallback(
    async (data: {
      title: string;
      due_date?: string;
      priority?: number;
      project_suggestion?: string;
    }) => {
      // Quick-add tasks default to NO project (Inbox). We only assign a
      // project when the parse surfaced an explicit suggestion that matches
      // an existing non-archived project — never silently route an untyped
      // task into an arbitrary (e.g. first-in-list) project. Users opt into
      // categorization explicitly via the Auto-Categorize button.
      const candidates = nonArchivedProjects(projects);
      let targetProjectId = '';
      if (data.project_suggestion) {
        const match = candidates.find((p) =>
          p.title.toLowerCase().includes(data.project_suggestion!.toLowerCase()),
        );
        if (match) targetProjectId = match.id;
      }

      try {
        await createTask(targetProjectId, {
          title: data.title,
          due_date: data.due_date,
          priority: (data.priority as 1 | 2 | 3 | 4) || 3,
        });
        toast.success('Task created!');
        // Refresh today tasks
        useTaskStore.getState().fetchToday();
        useTaskStore.getState().fetchOverdue();
        useTaskStore.getState().fetchUpcoming(7);
      } catch {
        toast.error('Failed to create task');
      }
    },
    [createTask, projects],
  );

  // Close theme menu on outside click.
  useEffect(() => {
    if (!themeMenuOpen) return;
    const handler = (e: MouseEvent) => {
      if (themeMenuRef.current && !themeMenuRef.current.contains(e.target as Node)) {
        setThemeMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [themeMenuOpen]);

  return (
    <>
      <header
        className="flex h-14 items-center gap-4 border-b border-[var(--color-border)] bg-[var(--color-bg-primary)] px-4"
        role="banner"
      >
        {/* NLP Quick Add Bar — rendered eagerly (not lazy) so the always-on
            top-bar input is present in the DOM on every route, even while a
            heavy route chunk is still downloading (bug B-09). */}
        <NLPInput onTaskCreate={handleTaskCreate} />

        {/* Search Button */}
        <button
          onClick={() => setSearchOpen(true)}
          className="flex shrink-0 items-center gap-1.5 rounded-lg border border-[var(--color-border)] px-3 py-1.5 text-sm text-[var(--color-text-secondary)] transition-colors hover:border-[var(--color-accent)] hover:text-[var(--color-text-primary)]"
          aria-label="Search tasks (Ctrl+K)"
        >
          <Search className="h-4 w-4" aria-hidden="true" />
          <span className="hidden sm:inline">Search</span>
          <kbd className="ml-1 hidden rounded bg-[var(--color-bg-secondary)] px-1.5 py-0.5 text-xs font-mono sm:inline" aria-hidden="true">
            ⌘K
          </kbd>
        </button>

        {/* Theme picker — one menu over the four named themes. */}
        <div className="relative shrink-0" ref={themeMenuRef}>
          <button
            onClick={() => setThemeMenuOpen((v) => !v)}
            className="rounded-md border border-[var(--color-border)] p-1.5 text-[var(--color-text-secondary)] transition-colors hover:text-[var(--color-text-primary)]"
            aria-label={`Theme: ${THEMES[themeKey].label}`}
            aria-haspopup="listbox"
            aria-expanded={themeMenuOpen}
          >
            <Palette className="h-4 w-4" aria-hidden="true" />
          </button>
          {themeMenuOpen && (
            <div
              className="absolute right-0 top-full z-50 mt-2 w-56 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] p-1 shadow-lg"
              role="listbox"
              aria-label="Theme"
            >
              {THEME_ORDER.map((key) => {
                const tokens = THEMES[key];
                const selected = themeKey === key;
                return (
                  <button
                    key={key}
                    onClick={() => {
                      setThemeKey(key);
                      setThemeMenuOpen(false);
                    }}
                    role="option"
                    aria-selected={selected}
                    className={`flex w-full items-center gap-2.5 rounded-md px-2 py-1.5 text-left text-sm transition-colors ${
                      selected
                        ? 'bg-[var(--color-accent)]/10 text-[var(--color-text-primary)]'
                        : 'text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]'
                    }`}
                  >
                    <span
                      className="h-3 w-3 shrink-0 rounded-full"
                      style={{ backgroundColor: tokens.primary }}
                      aria-hidden="true"
                    />
                    <span className="flex-1">
                      <span className="block font-medium">{tokens.label}</span>
                      <span className="block text-xs text-[var(--color-text-secondary)]">
                        {tokens.tagline}
                      </span>
                    </span>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* User Menu */}
        <div className="relative shrink-0" ref={menuRef}>
          <button
            onClick={() => setShowUserMenu(!showUserMenu)}
            aria-label="User menu"
            aria-expanded={showUserMenu}
            aria-haspopup="true"
          >
            <Avatar name={user?.name} src={user?.avatar_url} size="md" />
          </button>

          {showUserMenu && (
            <div
              className="absolute right-0 top-full z-50 mt-2 w-48 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] p-1 shadow-lg"
              role="menu"
              aria-label="User options"
            >
              <div className="border-b border-[var(--color-border)] px-3 py-2">
                <p className="text-sm font-medium text-[var(--color-text-primary)]">
                  {user?.name || 'User'}
                </p>
                <p className="text-xs text-[var(--color-text-secondary)]">
                  {user?.email || ''}
                </p>
              </div>
              <button
                onClick={() => {
                  setShowUserMenu(false);
                  logout();
                }}
                className="mt-1 flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-red-500 hover:bg-red-50 dark:hover:bg-red-500/10"
                role="menuitem"
              >
                <LogOut className="h-4 w-4" aria-hidden="true" />
                Sign Out
              </button>
            </div>
          )}
        </div>
      </header>

      {/* Search Modal */}
      <SearchModal isOpen={searchOpen} onClose={() => setSearchOpen(false)} />
    </>
  );
}
