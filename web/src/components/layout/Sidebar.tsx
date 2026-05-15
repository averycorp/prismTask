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
  Heart,
  Target,
  FileText,
  Archive,
  Settings,
  ShieldCheck,
  PanelLeftClose,
  PanelLeftOpen,
} from 'lucide-react';
import { useUIStore } from '@/stores/uiStore';
import { useAuthStore } from '@/stores/authStore';
import { PrismLogo } from '@/components/shared/PrismLogo';

const NAV_ITEMS = [
  { to: '/', icon: Sun, label: 'Today' },
  { to: '/tasks', icon: CheckSquare, label: 'Tasks' },
  { to: '/projects', icon: FolderKanban, label: 'Projects' },
  { to: '/habits', icon: Activity, label: 'Habits' },
  { to: '/calendar', icon: Calendar, label: 'Calendar' },
  // AI features grouped in a "prioritize → plan → schedule → reflect" arc.
  { to: '/chat', icon: MessageSquare, label: 'AI Coach' },
  { to: '/briefing', icon: Sparkles, label: 'Briefing' },
  { to: '/eisenhower', icon: LayoutGrid, label: 'Eisenhower' },
  { to: '/planner', icon: CalendarDays, label: 'Planner' },
  { to: '/pomodoro', icon: Timer, label: 'Pomodoro' },
  { to: '/timeblock', icon: CalendarClock, label: 'Time Block' },
  { to: '/weekly-review', icon: TrendingUp, label: 'Weekly Review' },
  { to: '/analytics', icon: BarChart3, label: 'Analytics' },
  { to: '/extract', icon: ClipboardPaste, label: 'Extract' },
  { to: '/medication', icon: Pill, label: 'Medication' },
  { to: '/mood', icon: Smile, label: 'Mood' },
  { to: '/self-care', icon: Heart, label: 'Self-Care' },
  { to: '/focus', icon: Target, label: 'Focus' },
  { to: '/templates', icon: FileText, label: 'Templates' },
  { to: '/archive', icon: Archive, label: 'Archive' },
  { to: '/settings', icon: Settings, label: 'Settings' },
] as const;

const ADMIN_NAV_ITEMS = [
  { to: '/admin/logs', icon: ShieldCheck, label: 'Admin Logs' },
] as const;

export function Sidebar() {
  const collapsed = useUIStore((s) => s.sidebarCollapsed);
  const toggleCollapsed = useUIStore((s) => s.toggleSidebarCollapsed);
  const isAdmin = useAuthStore((s) => s.user?.is_admin);

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
        <ul className="flex flex-col gap-1" role="list">
          {NAV_ITEMS.map(({ to, icon: Icon, label }) => (
            <li key={to}>
              <NavLink
                to={to}
                end={to === '/'}
                title={collapsed ? label : undefined}
                aria-label={collapsed ? label : undefined}
                className={({ isActive }) =>
                  `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                    isActive
                      ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                      : 'text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-primary)] hover:text-[var(--color-text-primary)]'
                  }`
                }
              >
                <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
                {!collapsed && <span>{label}</span>}
              </NavLink>
            </li>
          ))}
        </ul>

        {/* Admin section — only visible to admins */}
        {isAdmin && (
          <>
            <div className={`my-2 border-t border-[var(--color-border)] ${collapsed ? 'mx-1' : 'mx-2'}`} />
            <ul className="flex flex-col gap-1" role="list">
              {ADMIN_NAV_ITEMS.map(({ to, icon: Icon, label }) => (
                <li key={to}>
                  <NavLink
                    to={to}
                    title={collapsed ? label : undefined}
                    aria-label={collapsed ? label : undefined}
                    className={({ isActive }) =>
                      `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                        isActive
                          ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                          : 'text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-primary)] hover:text-[var(--color-text-primary)]'
                      }`
                    }
                  >
                    <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
                    {!collapsed && <span>{label}</span>}
                  </NavLink>
                </li>
              ))}
            </ul>
          </>
        )}
      </nav>

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
