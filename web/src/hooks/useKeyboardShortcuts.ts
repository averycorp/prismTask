import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

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

export function useKeyboardShortcuts({ onSearch, onNewTask, onShowShortcuts }: ShortcutActions) {
  const navigate = useNavigate();

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      // Cmd/Ctrl+K — quick search modal (always active)
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        onSearch();
        return;
      }

      // Cmd/Ctrl+F — full Search screen (unit 23 spec). Browser
      // default (in-page find) is overridden in favour of the
      // cross-collection search route. Falls through to the browser
      // when an input is focused so users can still find-in-page
      // while typing into a field.
      if ((e.metaKey || e.ctrlKey) && e.key === 'f' && !isInputFocused()) {
        e.preventDefault();
        navigate('/search');
        return;
      }

      // Only handle other shortcuts when not in an input
      if (isInputFocused()) return;

      switch (e.key) {
        // `/` — focus NLP bar (already handled in NLPInput)
        case 'n':
          e.preventDefault();
          onNewTask();
          break;
        case '?':
          e.preventDefault();
          onShowShortcuts?.();
          break;
        case '1':
          e.preventDefault();
          navigate('/');
          break;
        case '2':
          e.preventDefault();
          navigate('/tasks');
          break;
        case '3':
          e.preventDefault();
          navigate('/projects');
          break;
        case '4':
          e.preventDefault();
          navigate('/habits');
          break;
        case '5':
          e.preventDefault();
          navigate('/calendar/week');
          break;
        case 'Escape':
          // Close any open modals — handled by individual components
          break;
      }
    };

    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [navigate, onSearch, onNewTask, onShowShortcuts]);
}
