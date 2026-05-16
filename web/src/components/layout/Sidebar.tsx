import { useCallback, useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { ChevronDown, PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { useUIStore } from '@/stores/uiStore';
import { useAuthStore } from '@/stores/authStore';
import { PrismLogo } from '@/components/shared/PrismLogo';
import {
  PRIMARY_TABS,
  OVERFLOW_SECTIONS,
  isPrimaryTabActive,
  type PrimaryTab,
  type OverflowItem,
  type OverflowSection,
} from './navItems';

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

function sectionContainsActiveRoute(
  items: readonly OverflowItem[],
  pathname: string,
): boolean {
  return items.some(({ to }) => pathname === to || pathname.startsWith(`${to}/`));
}

/**
 * Desktop / tablet sidebar: 5 primary tabs at the top (mirrors the
 * mobile bottom nav), a persistent overflow section below for
 * secondary destinations, and the sidebar-collapse toggle at the
 * bottom. On collapse (width 16), section headers collapse out and
 * overflow items render inline so destinations stay one-click.
 *
 * The overflow section is permanently visible (no hamburger) on
 * screens ≥1024px — that's the "overflow drawer / sidebar (persistent
 * on desktop)" pattern from the parity spec.
 */
export function Sidebar() {
  const collapsed = useUIStore((s) => s.sidebarCollapsed);
  const toggleCollapsed = useUIStore((s) => s.toggleSidebarCollapsed);
  const isAdmin = useAuthStore((s) => s.user?.is_admin);
  const location = useLocation();
  const [expanded, setExpanded] = useState<Record<string, boolean>>(
    loadExpandedSections,
  );

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
      data-testid="desktop-sidebar"
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
      <nav
        className="flex-1 overflow-y-auto px-2 py-3"
        aria-label="App sections"
      >
        {/* Primary tabs — Today / Tasks / Projects / Habits / Settings */}
        <ul className="flex flex-col gap-1" role="list" aria-label="Primary tabs">
          {PRIMARY_TABS.map((tab) => (
            <li key={tab.to}>
              <PrimaryTabLink
                tab={tab}
                pathname={location.pathname}
                collapsed={collapsed}
              />
            </li>
          ))}
        </ul>

        {/* Overflow sections — grouped secondary destinations, persistent
            on desktop (no hamburger here, since the spec calls for a
            persistent rail at ≥1024px). */}
        {OVERFLOW_SECTIONS.map((section) => (
          <OverflowSectionGroup
            key={section.id}
            section={section}
            collapsed={collapsed}
            expanded={expanded[section.id] ?? false}
            toggleSection={toggleSection}
            isAdmin={!!isAdmin}
            pathname={location.pathname}
          />
        ))}
      </nav>

      {/* Collapse toggle */}
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

function PrimaryTabLink({
  tab,
  pathname,
  collapsed,
}: {
  tab: PrimaryTab;
  pathname: string;
  collapsed: boolean;
}) {
  const active = isPrimaryTabActive(tab, pathname);
  const Icon = tab.icon;
  return (
    <NavLink
      to={tab.to}
      end={tab.to === '/'}
      title={collapsed ? tab.label : undefined}
      aria-label={collapsed ? tab.label : undefined}
      data-testid={`sidebar-tab-${tab.label.toLowerCase()}`}
      aria-current={active ? 'page' : undefined}
      className={`flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
        active
          ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
          : 'text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-primary)] hover:text-[var(--color-text-primary)]'
      }`}
    >
      <Icon
        className="h-5 w-5 shrink-0"
        aria-hidden="true"
        strokeWidth={active ? 2.5 : 1.75}
        style={
          active
            ? {
                filter:
                  'drop-shadow(0 0 4px var(--color-accent)) drop-shadow(0 0 2px var(--color-accent))',
              }
            : undefined
        }
      />
      {!collapsed && <span>{tab.label}</span>}
    </NavLink>
  );
}

function OverflowSectionGroup({
  section,
  collapsed,
  expanded,
  toggleSection,
  isAdmin,
  pathname,
}: {
  section: OverflowSection;
  collapsed: boolean;
  expanded: boolean;
  toggleSection: (id: string) => void;
  isAdmin: boolean;
  pathname: string;
}) {
  const visibleItems = section.items.filter(
    (item) => !item.adminOnly || isAdmin,
  );
  if (visibleItems.length === 0) return null;

  const sectionActive = sectionContainsActiveRoute(visibleItems, pathname);
  const isOpen = expanded || sectionActive;

  // When the sidebar itself is collapsed (icon-only), section items
  // render inline so destinations stay reachable in one click.
  if (collapsed) {
    return (
      <ul
        className="mt-2 flex flex-col gap-1 border-t border-[var(--color-border)] pt-2"
        role="list"
        aria-label={section.label}
      >
        {visibleItems.map((item) => (
          <li key={item.to}>
            <OverflowLink item={item} collapsed pathname={pathname} />
          </li>
        ))}
      </ul>
    );
  }

  return (
    <div className="mt-3">
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
          {visibleItems.map((item) => (
            <li key={item.to}>
              <OverflowLink item={item} collapsed={false} pathname={pathname} indent />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function OverflowLink({
  item,
  collapsed,
  pathname,
  indent = false,
}: {
  item: OverflowItem;
  collapsed: boolean;
  pathname: string;
  indent?: boolean;
}) {
  const Icon = item.icon;
  const active = pathname === item.to || pathname.startsWith(`${item.to}/`);
  return (
    <NavLink
      to={item.to}
      title={collapsed ? item.label : undefined}
      aria-label={collapsed ? item.label : undefined}
      data-testid={`sidebar-overflow-${item.label.toLowerCase().replace(/\s+/g, '-')}`}
      aria-current={active ? 'page' : undefined}
      className={`flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
        active
          ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
          : 'text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-primary)] hover:text-[var(--color-text-primary)]'
      } ${indent && !collapsed ? 'pl-6' : ''}`}
    >
      <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
      {!collapsed && <span>{item.label}</span>}
    </NavLink>
  );
}
