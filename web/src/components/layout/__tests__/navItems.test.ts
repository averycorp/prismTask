import { describe, expect, it } from 'vitest';
import {
  PRIMARY_TABS,
  OVERFLOW_SECTIONS,
  isDetailRoute,
  isPrimaryTabActive,
} from '../navItems';

describe('PRIMARY_TABS', () => {
  it('has exactly 5 entries (Today / Tasks / Projects / Habits / Settings)', () => {
    expect(PRIMARY_TABS).toHaveLength(5);
    expect(PRIMARY_TABS.map((t) => t.label)).toEqual([
      'Today',
      'Tasks',
      'Projects',
      'Habits',
      'Settings',
    ]);
  });

  it('uses Title Capitalization in labels', () => {
    for (const tab of PRIMARY_TABS) {
      const firstLetter = tab.label[0];
      expect(firstLetter).toBe(firstLetter.toUpperCase());
    }
  });

  it('assigns shortcut keys 1..5 in order', () => {
    expect(PRIMARY_TABS.map((t) => t.shortcutKey)).toEqual([
      '1',
      '2',
      '3',
      '4',
      '5',
    ]);
  });
});

describe('isPrimaryTabActive', () => {
  const [today, tasks, projects, habits, settings] = PRIMARY_TABS;

  it('lights up Today only on the exact root path', () => {
    expect(isPrimaryTabActive(today, '/')).toBe(true);
    expect(isPrimaryTabActive(today, '/tasks')).toBe(false);
    expect(isPrimaryTabActive(today, '/today')).toBe(false);
  });

  it('lights up Tasks for /tasks and sub-routes', () => {
    expect(isPrimaryTabActive(tasks, '/tasks')).toBe(true);
    expect(isPrimaryTabActive(tasks, '/tasks/123')).toBe(true);
    expect(isPrimaryTabActive(tasks, '/projects')).toBe(false);
    // Must not bleed into routes with similar prefixes.
    expect(isPrimaryTabActive(tasks, '/tasks-other')).toBe(false);
  });

  it('lights up Projects for /projects and sub-routes', () => {
    expect(isPrimaryTabActive(projects, '/projects')).toBe(true);
    expect(isPrimaryTabActive(projects, '/projects/42')).toBe(true);
    expect(isPrimaryTabActive(projects, '/projects/42/roadmap')).toBe(true);
    expect(isPrimaryTabActive(projects, '/habits')).toBe(false);
  });

  it('lights up Habits for /habits and sub-routes', () => {
    expect(isPrimaryTabActive(habits, '/habits')).toBe(true);
    expect(isPrimaryTabActive(habits, '/habits/9/analytics')).toBe(true);
    expect(isPrimaryTabActive(habits, '/')).toBe(false);
  });

  it('lights up Settings for /settings and sub-routes', () => {
    expect(isPrimaryTabActive(settings, '/settings')).toBe(true);
    expect(isPrimaryTabActive(settings, '/settings/anything')).toBe(true);
    expect(isPrimaryTabActive(settings, '/')).toBe(false);
  });
});

describe('isDetailRoute', () => {
  it('returns false for list / index routes', () => {
    expect(isDetailRoute('/')).toBe(false);
    expect(isDetailRoute('/tasks')).toBe(false);
    expect(isDetailRoute('/projects')).toBe(false);
    expect(isDetailRoute('/habits')).toBe(false);
    expect(isDetailRoute('/settings')).toBe(false);
    expect(isDetailRoute('/calendar')).toBe(false);
  });

  it('returns true for task / project / habit detail routes', () => {
    expect(isDetailRoute('/tasks/123')).toBe(true);
    expect(isDetailRoute('/projects/abc-456')).toBe(true);
    expect(isDetailRoute('/projects/42/roadmap')).toBe(true);
    expect(isDetailRoute('/habits/9/analytics')).toBe(true);
    expect(isDetailRoute('/habits/9/logs')).toBe(true);
  });

  it('exempts known list sub-routes (calendar views, history pages)', () => {
    expect(isDetailRoute('/calendar/week')).toBe(false);
    expect(isDetailRoute('/calendar/month')).toBe(false);
    expect(isDetailRoute('/calendar/timeline')).toBe(false);
    expect(isDetailRoute('/medication/refills')).toBe(false);
    expect(isDetailRoute('/medication/history')).toBe(false);
    expect(isDetailRoute('/checkin/history')).toBe(false);
    expect(isDetailRoute('/balance/weekly-report')).toBe(false);
    expect(isDetailRoute('/batch/preview')).toBe(false);
  });

  it('normalises trailing slashes', () => {
    expect(isDetailRoute('/tasks/')).toBe(false);
    expect(isDetailRoute('/tasks/1/')).toBe(true);
    // Root path must not be stripped.
    expect(isDetailRoute('/')).toBe(false);
  });

  it('treats unrelated routes as non-details', () => {
    expect(isDetailRoute('/mood')).toBe(false);
    expect(isDetailRoute('/leisure')).toBe(false);
    expect(isDetailRoute('/admin/logs')).toBe(false);
  });
});

describe('OVERFLOW_SECTIONS', () => {
  it('has at least one item in each visible section', () => {
    for (const section of OVERFLOW_SECTIONS) {
      expect(section.items.length).toBeGreaterThan(0);
    }
  });

  it('does not duplicate routes between primary tabs and overflow', () => {
    const primaryRoutes = new Set(PRIMARY_TABS.map((t) => t.to));
    for (const section of OVERFLOW_SECTIONS) {
      for (const item of section.items) {
        expect(primaryRoutes.has(item.to)).toBe(false);
      }
    }
  });

  it('flags admin-only routes so signed-in non-admins do not see them', () => {
    const adminItems = OVERFLOW_SECTIONS.flatMap((s) =>
      s.items.filter((i) => i.adminOnly),
    );
    expect(adminItems.length).toBeGreaterThan(0);
    expect(adminItems.every((i) => i.to.startsWith('/admin/'))).toBe(true);
  });
});
