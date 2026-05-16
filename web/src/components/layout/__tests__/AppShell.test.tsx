import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

// Match the AppShell media-query check: useIsMobile is true when on a
// narrow viewport. Default the spy to "mobile" so the bottom nav is in
// the tree, then per-test override for desktop.
const mediaQueryState = { isMobile: true };
vi.mock('@/hooks/useMediaQuery', () => ({
  useIsMobile: () => mediaQueryState.isMobile,
  useIsTablet: () => false,
  useIsDesktop: () => !mediaQueryState.isMobile,
  useMediaQuery: () => false,
}));

// Avoid pulling the (heavy) keyboard-shortcut wiring into this test —
// the hook only adds document listeners and has its own unit coverage.
vi.mock('@/hooks/useKeyboardShortcuts', () => ({
  useKeyboardShortcuts: () => undefined,
}));

// Stub Firestore sync, Header chrome, and global modals so the render
// tree is small and deterministic.
vi.mock('@/hooks/useFirestoreSync', () => ({
  useFirestoreSync: () => undefined,
}));
vi.mock('@/components/layout/Header', () => ({
  Header: () => <header data-testid="stub-header" />,
}));
vi.mock('@/components/shared/SearchModal', () => ({
  SearchModal: () => null,
}));
vi.mock('@/components/shared/KeyboardShortcutsModal', () => ({
  KeyboardShortcutsModal: () => null,
}));
vi.mock('@/features/tasks/TaskEditor', () => ({
  default: () => null,
}));

// Stub the heavy nav children — we're testing the AppShell's
// detail-route hide logic, not nav rendering itself.
vi.mock('@/components/layout/Sidebar', () => ({
  Sidebar: () => <aside data-testid="stub-sidebar" />,
}));
vi.mock('@/components/layout/MobileNav', () => ({
  MobileNav: () => <nav data-testid="stub-mobile-nav" />,
}));

// uiStore + taskStore are pure Zustand selectors here.
vi.mock('@/stores/uiStore', () => ({
  useUIStore: <T,>(selector: (s: { sidebarCollapsed: boolean }) => T) =>
    selector({ sidebarCollapsed: false }),
}));
const taskStoreState = {
  setSelectedTask: vi.fn(),
  fetchToday: vi.fn(),
  fetchOverdue: vi.fn(),
};
vi.mock('@/stores/taskStore', () => ({
  useTaskStore: Object.assign(
    <T,>(selector: (s: typeof taskStoreState) => T) => selector(taskStoreState),
    { getState: () => taskStoreState },
  ),
}));

import { AppShell } from '@/components/layout/AppShell';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/" element={<div data-testid="route-today" />} />
          <Route path="/tasks" element={<div data-testid="route-tasks" />} />
          <Route path="/tasks/:id" element={<div data-testid="route-task-detail" />} />
          <Route
            path="/projects/:id"
            element={<div data-testid="route-project-detail" />}
          />
          <Route
            path="/calendar/week"
            element={<div data-testid="route-calendar-week" />}
          />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('AppShell — mobile bottom nav visibility', () => {
  beforeEach(() => {
    mediaQueryState.isMobile = true;
  });

  it('renders the mobile bottom nav on a list route', () => {
    renderAt('/tasks');
    expect(screen.getByTestId('stub-mobile-nav')).toBeInTheDocument();
    expect(screen.getByTestId('route-tasks')).toBeInTheDocument();
  });

  it('hides the mobile bottom nav on a detail route (/tasks/:id)', () => {
    renderAt('/tasks/abc-123');
    expect(screen.queryByTestId('stub-mobile-nav')).toBeNull();
    expect(screen.getByTestId('route-task-detail')).toBeInTheDocument();
  });

  it('hides the mobile bottom nav on /projects/:id', () => {
    renderAt('/projects/42');
    expect(screen.queryByTestId('stub-mobile-nav')).toBeNull();
    expect(screen.getByTestId('route-project-detail')).toBeInTheDocument();
  });

  it('keeps the bottom nav on list-style sub-routes (calendar/week)', () => {
    renderAt('/calendar/week');
    expect(screen.getByTestId('stub-mobile-nav')).toBeInTheDocument();
  });

  it('keeps the bottom nav on the Today index route', () => {
    renderAt('/');
    expect(screen.getByTestId('stub-mobile-nav')).toBeInTheDocument();
  });

  it('never renders the bottom nav on desktop, even on list routes', () => {
    mediaQueryState.isMobile = false;
    renderAt('/tasks');
    expect(screen.queryByTestId('stub-mobile-nav')).toBeNull();
    expect(screen.getByTestId('stub-sidebar')).toBeInTheDocument();
  });

  it('renders the desktop sidebar on detail routes too (no hide on desktop)', () => {
    mediaQueryState.isMobile = false;
    renderAt('/tasks/abc-123');
    expect(screen.getByTestId('stub-sidebar')).toBeInTheDocument();
    expect(screen.queryByTestId('stub-mobile-nav')).toBeNull();
  });
});
