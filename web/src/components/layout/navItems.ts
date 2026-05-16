/**
 * Shared navigation item definitions for the web app shell. The Android
 * app uses a 5-tab `NavigationBar` (configurable order, default Today /
 * Tasks / Habits / Leisure / Meds / Timer / Settings — only 5 of which
 * is shown at once). The web baseline takes 5 fixed primary tabs that
 * mirror the Android bottom nav's *most common* destinations:
 *
 *   Today / Tasks / Projects / Habits / Settings
 *
 * Secondary destinations live in an overflow drawer (hamburger on mobile,
 * persistent rail on desktop ≥1024px). Routes registered in
 * `routes/index.tsx` but not on this list are deep-linkable but not
 * surfaced as nav chrome — e.g. `/tasks/:id` is a detail route inside
 * Tasks, not its own tab.
 *
 * Lucide icons are all stroke-style, so the filled-vs-outlined Android
 * distinction is approximated with a heavier `strokeWidth` plus a
 * primary-colour glow on the active tab.
 */

import {
  Sun,
  CheckSquare,
  FolderKanban,
  Activity,
  Settings,
  Calendar,
  CalendarDays,
  CalendarClock,
  LayoutGrid,
  Timer,
  Sparkles,
  TrendingUp,
  BarChart3,
  ClipboardPaste,
  Pill,
  Smile,
  Target,
  FileText,
  Archive,
  MessageSquare,
  Sofa,
  GraduationCap,
  Heart,
  ShieldCheck,
  type LucideIcon,
} from 'lucide-react';

/**
 * A primary tab — rendered in the bottom-nav bar on mobile and in the
 * desktop left rail above the overflow section.
 */
export interface PrimaryTab {
  to: string;
  label: string;
  icon: LucideIcon;
  /** Keyboard shortcut digit (1..5). */
  shortcutKey: '1' | '2' | '3' | '4' | '5';
}

export interface OverflowItem {
  to: string;
  label: string;
  icon: LucideIcon;
  /** Only render this item when the signed-in user has `is_admin`. */
  adminOnly?: boolean;
}

export interface OverflowSection {
  id: string;
  label: string;
  items: readonly OverflowItem[];
}

/**
 * Primary tabs — five fixed slots, matching the Android bottom nav's
 * common-default destinations. Kept fixed (rather than reading from a
 * user-configurable preference) for v1 so deep-linked nav chrome is
 * predictable across devices.
 */
export const PRIMARY_TABS: readonly PrimaryTab[] = [
  { to: '/', label: 'Today', icon: Sun, shortcutKey: '1' },
  { to: '/tasks', label: 'Tasks', icon: CheckSquare, shortcutKey: '2' },
  { to: '/projects', label: 'Projects', icon: FolderKanban, shortcutKey: '3' },
  { to: '/habits', label: 'Habits', icon: Activity, shortcutKey: '4' },
  { to: '/settings', label: 'Settings', icon: Settings, shortcutKey: '5' },
];

/**
 * Overflow drawer destinations — everything that isn't a primary tab but
 * still has a top-level route. Grouped to make the drawer scannable.
 * Routes that exist as modals only (Search) are *not* listed here; the
 * Search button lives in the Header and is reachable via Ctrl+F / Ctrl+K.
 */
export const OVERFLOW_SECTIONS: readonly OverflowSection[] = [
  {
    id: 'plan',
    label: 'Plan',
    items: [
      { to: '/calendar', label: 'Calendar', icon: Calendar },
      { to: '/briefing', label: 'Briefing', icon: Sparkles },
      { to: '/eisenhower', label: 'Eisenhower', icon: LayoutGrid },
      { to: '/planner', label: 'Planner', icon: CalendarDays },
      { to: '/timeblock', label: 'Time Block', icon: CalendarClock },
      { to: '/pomodoro', label: 'Pomodoro', icon: Timer },
      { to: '/weekly-review', label: 'Weekly Review', icon: TrendingUp },
      { to: '/analytics', label: 'Analytics', icon: BarChart3 },
    ],
  },
  {
    id: 'wellness',
    label: 'Wellness',
    items: [
      { to: '/mood', label: 'Mood', icon: Smile },
      { to: '/medication', label: 'Medication', icon: Pill },
      { to: '/focus', label: 'Focus', icon: Target },
      { to: '/self-care', label: 'Self-Care', icon: Heart },
      { to: '/leisure', label: 'Leisure', icon: Sofa },
      { to: '/schoolwork', label: 'Schoolwork', icon: GraduationCap },
    ],
  },
  {
    id: 'workspace',
    label: 'Workspace',
    items: [
      { to: '/chat', label: 'Chat', icon: MessageSquare },
      { to: '/templates', label: 'Templates', icon: FileText },
      { to: '/extract', label: 'Extract', icon: ClipboardPaste },
      { to: '/archive', label: 'Archive', icon: Archive },
      { to: '/admin/logs', label: 'Admin Logs', icon: ShieldCheck, adminOnly: true },
    ],
  },
];

/**
 * Path prefixes that, when followed by an additional path segment, are
 * "detail" routes and should hide the primary nav chrome on mobile so
 * the detail screen owns the full viewport (mirrors Android, where
 * detail screens push past the bottom-nav `Scaffold`).
 *
 * Match rule: `/tasks` is *not* a detail (it's the list); `/tasks/:id`
 * IS a detail. We hide nav iff the path is `${prefix}/<something>` —
 * not the prefix itself, and not a known list-screen suffix like
 * `/calendar/week`.
 */
const DETAIL_ROUTE_PREFIXES: readonly string[] = [
  '/tasks',
  '/projects',
  '/habits',
  '/medication',
  '/checkin',
  '/balance',
  '/batch',
];

/**
 * List-style sub-routes that should NOT be treated as details even
 * though they sit under a primary-tab prefix. e.g. `/calendar/week` is
 * a list view on top of Calendar, not a single-record detail page.
 */
const LIST_SUBROUTE_EXEMPTIONS: ReadonlySet<string> = new Set([
  '/calendar/week',
  '/calendar/month',
  '/calendar/timeline',
  '/medication/refills',
  '/medication/history',
  '/checkin/history',
  '/balance/weekly-report',
  '/batch/preview',
]);

/**
 * True when `pathname` is a detail route under a primary tab. Used by
 * `AppShell` to hide the mobile bottom nav so the detail screen owns
 * the viewport.
 *
 * - `/tasks/123` → true
 * - `/tasks` → false (list)
 * - `/calendar/week` → false (list view, in exemptions)
 * - `/habits/42/analytics` → true (under `/habits` and not exempt)
 */
export function isDetailRoute(pathname: string): boolean {
  // Normalise trailing slash so `/tasks/` matches `/tasks`.
  const path = pathname.endsWith('/') && pathname !== '/' ? pathname.slice(0, -1) : pathname;

  if (LIST_SUBROUTE_EXEMPTIONS.has(path)) return false;

  return DETAIL_ROUTE_PREFIXES.some((prefix) => {
    if (path === prefix) return false; // list itself
    return path.startsWith(`${prefix}/`);
  });
}

/**
 * True when `pathname` should highlight the given primary tab.
 *
 * - The Today tab (`to === '/'`) only matches the exact root path so
 *   sub-routes don't keep it lit.
 * - Other tabs match their `to` exactly or any path under it.
 */
export function isPrimaryTabActive(tab: PrimaryTab, pathname: string): boolean {
  if (tab.to === '/') return pathname === '/';
  return pathname === tab.to || pathname.startsWith(`${tab.to}/`);
}
