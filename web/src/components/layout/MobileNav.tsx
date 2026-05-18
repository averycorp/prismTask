import { useState, useRef, useEffect, useCallback } from 'react';
import { NavLink } from 'react-router-dom';
import {
  Sun,
  CheckSquare,
  FolderKanban,
  Activity,
  MoreHorizontal,
  Calendar,
  LayoutGrid,
  Timer,
  CalendarClock,
  CalendarDays,
  Sparkles,
  MessageSquare,
  TrendingUp,
  BarChart3,
  ClipboardPaste,
  Pill,
  Smile,
  Target,
  FileText,
  Archive,
  Settings,
  X,
  Hash,
  Search,
  type LucideIcon,
} from 'lucide-react';

type NavItem = { to: string; icon: LucideIcon; label: string };
type NavSection = { id: string; label: string; items: readonly NavItem[] };

// Match Android's bottom nav: 4 work areas + a More overflow that fans out
// into the same grouped sections the desktop sidebar uses.
const PRIMARY_NAV: readonly NavItem[] = [
  { to: '/', icon: Sun, label: 'Today' },
  { to: '/tasks', icon: CheckSquare, label: 'Tasks' },
  { to: '/projects', icon: FolderKanban, label: 'Projects' },
  { to: '/habits', icon: Activity, label: 'Habits' },
];

const MORE_SECTIONS: readonly NavSection[] = [
  {
    id: 'plan',
    label: 'Plan',
    items: [
      { to: '/calendar', icon: Calendar, label: 'Calendar' },
      { to: '/briefing', icon: Sparkles, label: 'Briefing' },
      { to: '/chat', icon: MessageSquare, label: 'AI Executive Assistant' },
      { to: '/eisenhower', icon: LayoutGrid, label: 'Eisenhower' },
      { to: '/planner', icon: CalendarDays, label: 'Planner' },
      { to: '/timeblock', icon: CalendarClock, label: 'Time Block' },
      { to: '/pomodoro', icon: Timer, label: 'Pomodoro' },
      { to: '/weekly-review', icon: TrendingUp, label: 'Weekly Review' },
      { to: '/analytics', icon: BarChart3, label: 'Analytics' },
    ],
  },
  {
    id: 'wellness',
    label: 'Wellness',
    items: [
      { to: '/mood', icon: Smile, label: 'Mood' },
      { to: '/medication', icon: Pill, label: 'Medication' },
      { to: '/focus', icon: Target, label: 'Focus' },
    ],
  },
  {
    id: 'workspace',
    label: 'Workspace',
    items: [
      { to: '/search', icon: Search, label: 'Search' },
      { to: '/tags', icon: Hash, label: 'Tags' },
      { to: '/templates', icon: FileText, label: 'Templates' },
      { to: '/extract', icon: ClipboardPaste, label: 'Extract' },
      { to: '/archive', icon: Archive, label: 'Archive' },
      { to: '/settings', icon: Settings, label: 'Settings' },
    ],
  },
];

const ALL_MORE_PATHS = MORE_SECTIONS.flatMap((s) => s.items.map((i) => i.to));

export function MobileNav() {
  const [moreOpen, setMoreOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  const closeMenu = useCallback(() => setMoreOpen(false), []);

  // Close menu on outside click
  useEffect(() => {
    if (!moreOpen) return;
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        closeMenu();
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [moreOpen, closeMenu]);

  const isMoreActive = ALL_MORE_PATHS.some((to) =>
    window.location.pathname.startsWith(to),
  );

  return (
    <>
      {/* More menu overlay */}
      {moreOpen && (
        <div className="fixed inset-0 z-40 bg-black/30" aria-hidden="true" onClick={closeMenu} />
      )}

      {/* More menu panel — grouped sections mirror the desktop sidebar */}
      {moreOpen && (
        <div
          ref={menuRef}
          role="menu"
          aria-label="More navigation options"
          className="fixed bottom-16 left-2 right-2 z-50 max-h-[70vh] overflow-y-auto rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-2 shadow-xl animate-slide-up"
        >
          <div className="mb-1 flex items-center justify-between px-3 py-1">
            <span className="text-xs font-semibold uppercase tracking-wider text-[var(--color-text-secondary)]">
              More
            </span>
            <button
              onClick={closeMenu}
              className="rounded-md p-1 text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
              aria-label="Close menu"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
          {MORE_SECTIONS.map((section, idx) => (
            <div key={section.id} className={idx > 0 ? 'mt-2 border-t border-[var(--color-border)] pt-2' : ''}>
              <div className="px-3 py-1 text-[11px] font-semibold uppercase tracking-wider text-[var(--color-text-secondary)]">
                {section.label}
              </div>
              {section.items.map(({ to, icon: Icon, label }) => (
                <NavLink
                  key={to}
                  to={to}
                  role="menuitem"
                  onClick={closeMenu}
                  className={({ isActive }) =>
                    `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors ${
                      isActive
                        ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                        : 'text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)]'
                    }`
                  }
                >
                  <Icon className="h-5 w-5" aria-hidden="true" />
                  {label}
                </NavLink>
              ))}
            </div>
          ))}
        </div>
      )}

      {/* Bottom nav bar */}
      <nav
        className="fixed bottom-0 left-0 right-0 z-30 border-t border-[var(--color-border)] bg-[var(--color-bg-primary)] lg:hidden"
        aria-label="Main navigation"
      >
        <ul className="flex items-center justify-around">
          {PRIMARY_NAV.map(({ to, icon: Icon, label }) => (
            <li key={to} className="flex-1">
              <NavLink
                to={to}
                end={to === '/'}
                className={({ isActive }) =>
                  `flex min-h-[48px] flex-col items-center justify-center gap-0.5 py-2 text-xs transition-colors ${
                    isActive
                      ? 'text-[var(--color-accent)]'
                      : 'text-[var(--color-text-secondary)]'
                  }`
                }
              >
                <Icon className="h-5 w-5" aria-hidden="true" />
                <span>{label}</span>
              </NavLink>
            </li>
          ))}
          {/* More button */}
          <li className="flex-1">
            <button
              onClick={() => setMoreOpen(!moreOpen)}
              className={`flex min-h-[48px] w-full flex-col items-center justify-center gap-0.5 py-2 text-xs transition-colors ${
                isMoreActive || moreOpen
                  ? 'text-[var(--color-accent)]'
                  : 'text-[var(--color-text-secondary)]'
              }`}
              aria-label="More options"
              aria-expanded={moreOpen}
              aria-haspopup="true"
            >
              <MoreHorizontal className="h-5 w-5" aria-hidden="true" />
              <span>More</span>
            </button>
          </li>
        </ul>
      </nav>
    </>
  );
}
