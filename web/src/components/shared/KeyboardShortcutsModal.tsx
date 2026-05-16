import { Modal } from '@/components/ui/Modal';

interface KeyboardShortcutsModalProps {
  isOpen: boolean;
  onClose: () => void;
}

interface ShortcutEntry {
  keys: string[];
  description: string;
}

interface ShortcutGroup {
  title: string;
  shortcuts: ShortcutEntry[];
}

const SHORTCUT_GROUPS: ShortcutGroup[] = [
  {
    title: 'Global',
    shortcuts: [
      { keys: ['/'], description: 'Focus quick-add bar' },
      { keys: ['Ctrl', 'K'], description: 'Open search' },
      { keys: ['Ctrl', 'F'], description: 'Open search (Android-parity alias)' },
      { keys: ['Ctrl', 'N'], description: 'New task' },
      { keys: ['Ctrl', '1'], description: 'Go to Today' },
      { keys: ['Ctrl', '2'], description: 'Go to Tasks' },
      { keys: ['Ctrl', '3'], description: 'Go to Projects' },
      { keys: ['Ctrl', '4'], description: 'Go to Habits' },
      { keys: ['Ctrl', '5'], description: 'Go to Settings' },
      { keys: ['?'], description: 'Show keyboard shortcuts' },
      { keys: ['Escape'], description: 'Go back / close dialog' },
    ],
  },
  {
    title: 'Task List',
    shortcuts: [
      { keys: ['J'], description: 'Next task' },
      { keys: ['K'], description: 'Previous task' },
      { keys: ['X'], description: 'Toggle task selection' },
      { keys: ['Enter'], description: 'Open task details' },
      { keys: ['E'], description: 'Edit task' },
      { keys: ['D'], description: 'Toggle task done' },
      { keys: ['Delete'], description: 'Delete task' },
    ],
  },
  {
    title: 'Calendar',
    shortcuts: [
      { keys: ['Left'], description: 'Previous day / week' },
      { keys: ['Right'], description: 'Next day / week' },
      { keys: ['T'], description: 'Go to today' },
    ],
  },
  {
    title: 'Quick Add',
    shortcuts: [
      { keys: ['/name'], description: 'Use template (autocomplete)' },
      { keys: ['#tag'], description: 'Add tag' },
      { keys: ['@project'], description: 'Set project' },
      { keys: ['!high'], description: 'Set priority' },
      { keys: ['tomorrow'], description: 'Set due date' },
    ],
  },
];

function Kbd({ children }: { children: string }) {
  return (
    <kbd className="inline-flex min-w-[1.5rem] items-center justify-center rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-1.5 py-0.5 text-xs font-medium text-[var(--color-text-primary)]">
      {children}
    </kbd>
  );
}

export function KeyboardShortcutsModal({ isOpen, onClose }: KeyboardShortcutsModalProps) {
  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Keyboard Shortcuts" size="md">
      <div className="flex flex-col gap-6">
        {SHORTCUT_GROUPS.map((group) => (
          <div key={group.title}>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-[var(--color-text-secondary)]">
              {group.title}
            </h3>
            <div className="flex flex-col">
              {group.shortcuts.map((shortcut) => (
                <div
                  key={shortcut.description}
                  className="flex items-center justify-between border-b border-[var(--color-border)]/50 py-2 last:border-0"
                >
                  <span className="text-sm text-[var(--color-text-primary)]">
                    {shortcut.description}
                  </span>
                  <div className="flex items-center gap-1">
                    {shortcut.keys.map((key, i) => (
                      <span key={i} className="flex items-center gap-1">
                        {i > 0 && (
                          <span className="text-xs text-[var(--color-text-secondary)]">+</span>
                        )}
                        <Kbd>{key}</Kbd>
                      </span>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </Modal>
  );
}
