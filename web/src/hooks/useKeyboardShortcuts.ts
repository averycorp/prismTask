import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { PRIMARY_TABS } from '@/components/layout/navItems';

interface ShortcutActions {
  onSearch: () => void;
  onNewTask: () => void;
  onShowShortcuts?: () => void;
}

function isInputFocused(): boolean {
  const active = document.activeElement;
  if (!active) return false;
  const tag = active.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
  if (active.getAttribute('contenteditable')) return true;
  return false;
}

/**
 * Global keyboard shortcuts. Mirrors Android `PrismTaskNavGraph` shortcut
 * grammar so muscle memory transfers between the two platforms:
 *
 *   Ctrl+1..5  Jump to primary tab N (1=Today, 5=Settings)
 *   Ctrl+N     Quick add a task
 *   Ctrl+F     Open Search modal (Ctrl+K is also accepted)
 *   ?          Show shortcuts modal (no modifier — only when no input is focused)
 *   Esc        Go back one step (`history.back()`)
 */
export function useKeyboardShortcuts({
  onSearch,
  onNewTask,
  onShowShortcuts,
}: ShortcutActions) {
  const navigate = useNavigate();

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const cmd = e.metaKey || e.ctrlKey;

      // Cmd/Ctrl+K — search (always active, common convention)
      if (cmd && !e.shiftKey && !e.altKey && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        onSearch();
        return;
      }

      // Cmd/Ctrl+F — Android-parity alias for search (only swallow when not in
      // an input, since the browser's native find-in-page should still work
      // when typing into a search box).
      if (cmd && !e.shiftKey && !e.altKey && e.key.toLowerCase() === 'f' && !isInputFocused()) {
        e.preventDefault();
        onSearch();
        return;
      }

      // Cmd/Ctrl+N — new task (only when not typing).
      if (cmd && !e.shiftKey && !e.altKey && e.key.toLowerCase() === 'n' && !isInputFocused()) {
        e.preventDefault();
        onNewTask();
        return;
      }

      // Cmd/Ctrl+1..5 — jump to primary tab.
      if (cmd && !e.shiftKey && !e.altKey && /^[1-5]$/.test(e.key)) {
        const tab = PRIMARY_TABS.find((t) => t.shortcutKey === e.key);
        if (tab) {
          e.preventDefault();
          navigate(tab.to);
        }
        return;
      }

      // Escape — go back one step. Skip when typing so Esc still
      // dismisses native autocomplete dropdowns and closes inline
      // editors. Modal owners handle their own Esc separately
      // (`MobileNav` closes the drawer first; modals close themselves).
      if (e.key === 'Escape' && !isInputFocused()) {
        // Only navigate back if there's real history to pop. Avoids
        // bouncing the user out of the app on the root route.
        if (window.history.length > 1) {
          window.history.back();
        }
        return;
      }

      // Bare-key shortcuts — only when no input is focused.
      if (isInputFocused()) return;

      if (e.key === '?') {
        e.preventDefault();
        onShowShortcuts?.();
      }
    };

    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [navigate, onSearch, onNewTask, onShowShortcuts]);
}
