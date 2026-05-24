import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';

// In-memory localStorage shim so the settings store doesn't blow up on init.
vi.hoisted(() => {
  const existing = (globalThis as { localStorage?: Storage }).localStorage;
  if (existing && typeof existing.clear === 'function') return;
  const backing = new Map<string, string>();
  const shim: Storage = {
    get length() {
      return backing.size;
    },
    clear: () => backing.clear(),
    getItem: (key: string) => (backing.has(key) ? backing.get(key)! : null),
    key: (index: number) => Array.from(backing.keys())[index] ?? null,
    removeItem: (key: string) => {
      backing.delete(key);
    },
    setItem: (key: string, value: string) => {
      backing.set(key, String(value));
    },
  };
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: shim,
  });
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: shim,
    });
  }
});

// Mock stores
vi.mock('@/stores/settingsStore', () => ({
  useSettingsStore: vi.fn((selector) => selector({ timeFormat: '12h' })),
}));

vi.mock('@/stores/taskTimingsStore', () => ({
  useTaskTimingsStore: vi.fn((selector) => {
    // Return mock state for timings and logTiming
    const mockState = {
      timings: [],
      logTiming: vi.fn(),
    };
    return selector(mockState);
  }),
}));

vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: vi.fn(() => 'user-123'),
}));

vi.mock('@/api/firestore/taskTimings', () => ({
  updateTaskTiming: vi.fn(),
  deleteTaskTiming: vi.fn(),
}));

import { ScheduleTab, type ScheduleTabProps } from '../ScheduleTab';

const mockProps: ScheduleTabProps = {
  isCreate: false,
  taskId: 'task-123',
  dueDate: '',
  onDueDateChange: vi.fn(),
  dueTime: '',
  onDueTimeChange: vi.fn(),
  plannedDate: '',
  onPlannedDateChange: vi.fn(),
  reminderOffset: '',
  onReminderOffsetChange: vi.fn(),
  recurrenceType: '',
  onRecurrenceTypeChange: vi.fn(),
  recurrenceInterval: 1,
  onRecurrenceIntervalChange: vi.fn(),
  recurrenceDaysOfWeek: [],
  onRecurrenceDaysOfWeekChange: vi.fn(),
  recurrenceAfterCompletion: false,
  onRecurrenceAfterCompletionChange: vi.fn(),
  recurrenceEndMode: 'never',
  onRecurrenceEndModeChange: vi.fn(),
  recurrenceEndAfter: 10,
  onRecurrenceEndAfterChange: vi.fn(),
  recurrenceEndDate: '',
  onRecurrenceEndDateChange: vi.fn(),
  duration: '',
  onDurationChange: vi.fn(),
};

describe('ScheduleTab Unit Test', () => {
  afterEach(() => {
    cleanup();
  });

  it('renders without crashing', () => {
    const { container } = render(<ScheduleTab {...mockProps} />);
    expect(container).toBeDefined();
  });
});
