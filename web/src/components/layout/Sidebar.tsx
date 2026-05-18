import { useCallback, useState } from 'react';
import { NavLink } from 'react-router-dom';
import {
  Sun,
  CheckSquare,
  FolderKanban,
  Activity,
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
  ShieldCheck,
  PanelLeftClose,
  PanelLeftOpen,
  ChevronDown,
  Hash,
  Search,
  type LucideIcon,
} from 'lucide-react';
import { useUIStore } from '@/stores/uiStore';
import { useAuthStore } from '@/stores/authStore';
import { PrismLogo } from '@/components/shared/PrismLogo';

type NavItem = { to: string; icon: LucideIcon; label: string };
type NavSection = { id: string; label: string; items: readonly NavItem[] };

// Primary destinations — always visible, mirrors Android's bottom nav
// (Today / Tasks / Projects / Habits). Each is a top-level workspace.
const PRIMARY_ITEMS: readonly NavItem[] = [
  { to: '/', icon: Sun, label: 'Today' },
  { to: '/tasks', icon: CheckSquare, label: 'Tasks' },
  { to: '/projects', icon: FolderKanban, label: 'Projects' },
  { to: '/habits', icon: Activity, label: 'Habits' },
];

// Secondary destinations grouped by purpose. Each section collapses
// independently so the default view stays close to the mobile 5-tab feel.
const SECTIONS: readonly NavSection[] = [
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
    ],
  },
];

const SETTINGS_ITEM: NavItem = { to: '/settings', icon: Settings, label: 'Settings' };
const ADMIN_ITEMS: readonly NavItem[] = [
  { to: '/admin/logs', icon: ShieldCheck, label: 'Admin Logs' },
];

const EXPANDED_STORAGE_KEY = 'prismtask_sidebar_expanded_sections';

function loadExpandedSections(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(EXPANDED_STORAGE_KEY);
    if (raw) return JSON.parse(raw) as Record<string, boolean>;
  } catch {
    // Corrupt value — fall through to defaults.
  }
  return {};
}

function isSectionActive(items: readonly NavItem[]): boolean {
  const path = window.location.pathname;
  return items.some(({ to }) => path === to || path.startsWith(`${to}/`));
}

function navLinkClasses(isActive: boolean): string {
  return `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
    isActive
      ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
      : 'text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-primary)] hover:text-[var(--color-text-primary)]'
  }`;
}

function NavItemLink({
  item,
  collapsed,
  indent = false,
}: {
  item: NavItem;
  collapsed: boolean;
  indent?: boolean;
}) {
  const { to, icon: Icon, label } = item;
  return (
    <NavLink
      to={to}
      end={to === '/'}
      title={collapsed ? label : undefined}
      aria-label={collapsed ? label : undefined}
      className={({ isActive }) =>
        `${navLinkClasses(isActive)} ${indent && !collapsed ? 'pl-6' : ''}`
      }
    >
      <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
      {!collapsed && <span>{label}</span>}
    </NavLink>
  );
}

export function Sidebar() {
  const collapsed = useUIStore((s) => s.sidebarCollapsed);
  const toggleCollapsed = useUIStore((s) => s.toggleSidebarCollapsed);
  const isAdmin = useAuthStore((s) => s.user?.is_admin);
  const [expanded, setExpanded] = useState<Record<string, boolean>>(loadExpandedSections);

  const toggleSection = useCallback((id: string) => {
    setExpanded((prev) => {
      const next = { ...prev, [id]: !prev[id] };
      try {
        localStorage.setItem(EXPANDED_STORAGE_KEY, JSON.stringify(next));
      } catch {
        // Storage unavailable — keep in-memory state only.
      }
      return next;
    });
  }, []);

  return (
    <aside
      className={`fixed left-0 top-0 z-30 flex h-screen flex-col border-r border-[var(--color-border)] bg-[var(--color-bg-secondary)] transition-all duration-200 ${
        collapsed ? 'w-16' : 'w-60'
      }`}
      aria-label="Main navigation"
    >
      {/* Logo */}
      <div className="flex h-14 items-center border-b border-[var(--color-border)] px-3">
        <PrismLogo variant={collapsed ? 'icon' : 'full'} size={collapsed ? 28 : 32} />
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto px-2 py-3" aria-label="App sections">
        {/* Primary nav — matches Android bottom-tab destinations */}
        <ul className="flex flex-col gap-1" role="list">
          {PRIMARY_ITEMS.map((item) => (
            <li key={item.to}>
              <NavItemLink item={item} collapsed={collapsed} />
            </li>
          ))}
        </ul>

        {/* Secondary nav — collapsible labeled sections so the default view
            stays close to the mobile 5-tab feel. When the sidebar itself is
            collapsed (icon-only), section items render inline so destinations
            stay reachable in one click. */}
        {SECTIONS.map((section) => {
          const sectionActive = isSectionActive(section.items);
          const isOpen = expanded[section.id] ?? sectionActive;
          if (collapsed) {
            return (
              <ul
                key={section.id}
                className="mt-2 flex flex-col gap-1 border-t border-[var(--color-border)] pt-2"
                role="list"
                aria-label={section.label}
              >
                {section.items.map((item) => (
                  <li key={item.to}>
                    <NavItemLink item={item} collapsed={collapsed} />
                  </li>
                ))}
              </ul>
            );
          }
          return (
            <div key={section.id} className="mt-3">
              <button
                onClick={() => toggleSection(section.id)}
                className={`flex w-full items-center justify-between rounded-md px-3 py-1.5 text-xs font-semibold uppercase tracking-wider transition-colors ${
                  sectionActive
                    ? 'text-[var(--color-accent)]'
                    : 'text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
                }`}
                aria-expanded={isOpen}
                aria-controls={`nav-section-${section.id}`}
              >
                <span>{section.label}</span>
                <ChevronDown
                  className={`h-4 w-4 transition-transform ${isOpen ? '' : '-rotate-90'}`}
                  aria-hidden="true"
                />
              </button>
              {isOpen && (
                <ul
                  id={`nav-section-${section.id}`}
                  className="mt-1 flex flex-col gap-1"
                  role="list"
                >
                  {section.items.map((item) => (
                    <li key={item.to}>
                      <NavItemLink item={item} collapsed={collapsed} indent />
                    </li>
                  ))}
                </ul>
              )}
            </div>
          );
        })}

        {/* Admin section — only visible to admins */}
        {isAdmin && (
          <>
            <div className={`my-3 border-t border-[var(--color-border)] ${collapsed ? 'mx-1' : 'mx-2'}`} />
            <ul className="flex flex-col gap-1" role="list">
              {ADMIN_ITEMS.map((item) => (
                <li key={item.to}>
                  <NavItemLink item={item} collapsed={collapsed} />
                </li>
              ))}
            </ul>
          </>
        )}
      </nav>

      {/* Settings pinned at bottom — mirrors Android's persistent Settings tab */}
      <div className="border-t border-[var(--color-border)] px-2 py-2">
        <NavItemLink item={SETTINGS_ITEM} collapsed={collapsed} />
      </div>

      {/* Collapse Toggle */}
      <button
        onClick={toggleCollapsed}
        className="flex h-12 items-center justify-center border-t border-[var(--color-border)] text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
        aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
      >
        {collapsed ? (
          <PanelLeftOpen className="h-5 w-5" aria-hidden="true" />
        ) : (
          <PanelLeftClose className="h-5 w-5" aria-hidden="true" />
        )}
      </button>
    </aside>
  );
}
