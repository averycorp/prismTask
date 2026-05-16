import { useCallback, useEffect, useRef, useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { Menu, X } from 'lucide-react';
import { useAuthStore } from '@/stores/authStore';
import {
  PRIMARY_TABS,
  OVERFLOW_SECTIONS,
  isPrimaryTabActive,
  type PrimaryTab,
  type OverflowItem,
} from './navItems';

/**
 * Bottom-nav bar for mobile. Renders the 5 primary tabs from
 * `PRIMARY_TABS` plus a separate hamburger button that opens the overflow
 * drawer. Matches Android's bottom-nav shape: filled icon + glow on the
 * active tab, plus a "More" overflow off to one side for secondary
 * destinations.
 */
export function MobileNav() {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const drawerRef = useRef<HTMLDivElement>(null);
  const location = useLocation();
  const isAdmin = useAuthStore((s) => s.user?.is_admin);

  const closeDrawer = useCallback(() => setDrawerOpen(false), []);

  // Close on route change so the drawer doesn't linger after navigation.
  useEffect(() => {
    setDrawerOpen(false);
  }, [location.pathname]);

  // Close on outside click.
  useEffect(() => {
    if (!drawerOpen) return;
    const handler = (e: MouseEvent) => {
      if (drawerRef.current && !drawerRef.current.contains(e.target as Node)) {
        closeDrawer();
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [drawerOpen, closeDrawer]);

  // Close on Escape.
  useEffect(() => {
    if (!drawerOpen) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeDrawer();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [drawerOpen, closeDrawer]);

  return (
    <>
      {/* Overflow drawer overlay */}
      {drawerOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/40"
          aria-hidden="true"
          onClick={closeDrawer}
        />
      )}

      {/* Overflow drawer panel (slides in from bottom) */}
      {drawerOpen && (
        <div
          ref={drawerRef}
          role="dialog"
          aria-modal="true"
          aria-label="More navigation options"
          data-testid="overflow-drawer"
          className="fixed bottom-16 left-2 right-2 z-50 max-h-[70vh] overflow-y-auto rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-2 shadow-xl"
        >
          <div className="mb-1 flex items-center justify-between px-3 py-1">
            <span className="text-xs font-semibold uppercase tracking-wider text-[var(--color-text-secondary)]">
              More
            </span>
            <button
              onClick={closeDrawer}
              className="rounded-md p-1 text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
              aria-label="Close menu"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
          {OVERFLOW_SECTIONS.map((section, idx) => {
            const visibleItems = section.items.filter(
              (item) => !item.adminOnly || isAdmin,
            );
            if (visibleItems.length === 0) return null;
            return (
              <div
                key={section.id}
                className={idx > 0 ? 'mt-2 border-t border-[var(--color-border)] pt-2' : ''}
              >
                <div className="px-3 py-1 text-[11px] font-semibold uppercase tracking-wider text-[var(--color-text-secondary)]">
                  {section.label}
                </div>
                {visibleItems.map((item) => (
                  <DrawerLink key={item.to} item={item} onNavigate={closeDrawer} />
                ))}
              </div>
            );
          })}
        </div>
      )}

      {/* Bottom-nav bar — 5 primary tabs + hamburger overflow trigger */}
      <nav
        className="fixed bottom-0 left-0 right-0 z-30 border-t border-[var(--color-border)] bg-[var(--color-bg-primary)] lg:hidden"
        aria-label="Primary navigation"
        data-testid="mobile-bottom-nav"
      >
        <ul className="flex items-stretch justify-around">
          {PRIMARY_TABS.map((tab) => (
            <li key={tab.to} className="flex-1">
              <PrimaryTabLink tab={tab} pathname={location.pathname} />
            </li>
          ))}
          <li className="flex-1">
            <button
              onClick={() => setDrawerOpen((v) => !v)}
              className={`flex min-h-[56px] w-full flex-col items-center justify-center gap-0.5 py-2 text-xs font-medium transition-colors ${
                drawerOpen
                  ? 'text-[var(--color-accent)]'
                  : 'text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
              }`}
              aria-label="More options"
              aria-expanded={drawerOpen}
              aria-haspopup="dialog"
              data-testid="overflow-trigger"
            >
              <Menu className="h-5 w-5" aria-hidden="true" />
              <span>More</span>
            </button>
          </li>
        </ul>
      </nav>
    </>
  );
}

function PrimaryTabLink({ tab, pathname }: { tab: PrimaryTab; pathname: string }) {
  const active = isPrimaryTabActive(tab, pathname);
  const Icon = tab.icon;
  return (
    <NavLink
      to={tab.to}
      end={tab.to === '/'}
      data-testid={`nav-tab-${tab.label.toLowerCase()}`}
      aria-current={active ? 'page' : undefined}
      className={`flex min-h-[56px] flex-col items-center justify-center gap-0.5 py-2 text-xs font-medium transition-colors ${
        active
          ? 'text-[var(--color-accent)]'
          : 'text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
      }`}
    >
      <Icon
        className="h-5 w-5"
        aria-hidden="true"
        // Heavier strokeWidth on the active tab approximates the
        // filled-vs-outlined distinction the Android NavigationBar
        // gets for free from Material icons.
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
      <span>{tab.label}</span>
    </NavLink>
  );
}

function DrawerLink({
  item,
  onNavigate,
}: {
  item: OverflowItem;
  onNavigate: () => void;
}) {
  const Icon = item.icon;
  return (
    <NavLink
      to={item.to}
      role="menuitem"
      onClick={onNavigate}
      data-testid={`overflow-link-${item.label.toLowerCase().replace(/\s+/g, '-')}`}
      className={({ isActive }) =>
        `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors ${
          isActive
            ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
            : 'text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)]'
        }`
      }
    >
      <Icon className="h-5 w-5" aria-hidden="true" />
      {item.label}
    </NavLink>
  );
}
