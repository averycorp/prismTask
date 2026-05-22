import { useState, useCallback } from 'react';
import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Header } from './Header';
import { MobileNav } from './MobileNav';
import { SearchModal } from '@/components/shared/SearchModal';
import { KeyboardShortcutsModal } from '@/components/shared/KeyboardShortcutsModal';
import { useUIStore } from '@/stores/uiStore';
import { useIsMobile } from '@/hooks/useMediaQuery';
import { useKeyboardShortcuts } from '@/hooks/useKeyboardShortcuts';
import { useTaskStore } from '@/stores/taskStore';
import { lazy, Suspense } from 'react';

const TaskEditor = lazy(() => import('@/features/tasks/TaskEditor'));

export function AppShell() {
  const sidebarCollapsed = useUIStore((s) => s.sidebarCollapsed);
  const isMobile = useIsMobile();
  const setSelectedTask = useTaskStore((s) => s.setSelectedTask);

  const [searchOpen, setSearchOpen] = useState(false);
  const [newTaskOpen, setNewTaskOpen] = useState(false);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);

  const handleSearch = useCallback(() => {
    setSearchOpen(true);
  }, []);

  const handleNewTask = useCallback(() => {
    setSelectedTask(null);
    setNewTaskOpen(true);
  }, [setSelectedTask]);

  const handleShowShortcuts = useCallback(() => {
    setShortcutsOpen(true);
  }, []);

  useKeyboardShortcuts({
    onSearch: handleSearch,
    onNewTask: handleNewTask,
    onShowShortcuts: handleShowShortcuts,
  });

  return (
    <div className="flex h-screen bg-[var(--color-bg-primary)]">
      {/* Desktop/Tablet Sidebar */}
      {!isMobile && <Sidebar />}

      {/* Main Content Area */}
      <div
        className={`flex flex-1 flex-col transition-all duration-200 ${
          isMobile ? 'ml-0' : sidebarCollapsed ? 'ml-16' : 'ml-60'
        }`}
      >
        <Header />
        <main
          id="main-content"
          className="flex-1 overflow-y-auto p-4 pb-20 lg:pb-4"
          role="main"
        >
          <Outlet />
        </main>
      </div>

      {/* Mobile Bottom Nav */}
      {isMobile && <MobileNav />}

      {/* Global Search Modal (from Ctrl+K shortcut) */}
      <SearchModal isOpen={searchOpen} onClose={() => setSearchOpen(false)} />

      {/* Global Keyboard Shortcuts Modal (from ? shortcut) */}
      <KeyboardShortcutsModal
        isOpen={shortcutsOpen}
        onClose={() => setShortcutsOpen(false)}
      />

      {/* Global New Task (from `n` shortcut) */}
      {newTaskOpen && (
        <Suspense fallback={null}>
          <TaskEditor
            mode="create"
            onClose={() => setNewTaskOpen(false)}
            onUpdate={() => {
              useTaskStore.getState().fetchToday();
              useTaskStore.getState().fetchOverdue();
            }}
          />
        </Suspense>
      )}

      {/* Skip to main content link (screen reader) */}
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[70] focus:rounded-lg focus:bg-[var(--color-accent)] focus:px-4 focus:py-2 focus:text-white focus:shadow-lg"
      >
        Skip To Main Content
      </a>
    </div>
  );
}
