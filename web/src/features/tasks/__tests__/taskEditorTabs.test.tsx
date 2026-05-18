import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

// Stub the firestore module before importing ScheduleTab so the CRUD
// helpers don't touch a real Firebase project under jsdom.
vi.mock('@/api/firestore/taskTimings', async () => {
  const actual = await vi.importActual<
    typeof import('@/api/firestore/taskTimings')
  >('@/api/firestore/taskTimings');
  return {
    ...actual,
    subscribeToTaskTimings: vi.fn(),
    addTaskTiming: vi.fn(),
    updateTaskTiming: vi.fn(),
    deleteTaskTiming: vi.fn(),
  };
});
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));
vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: () => 'uid-test',
}));

import { DetailsTab } from '@/features/tasks/tabs/DetailsTab';
import { ScheduleTab } from '@/features/tasks/tabs/ScheduleTab';
import { useTaskTimingsStore } from '@/stores/taskTimingsStore';
import type { TaskTiming } from '@/api/firestore/taskTimings';

/**
 * Unit tests for the refactored 3-tab task editor (parity unit 9 of 23).
 * Mirrors the structural assertions of the Android `AddEditTaskSheet`
 * tab tests — each tab renders its required section labels, propagates
 * default values to inputs, and exposes the correct Auto / change
 * affordances.
 */

describe('DetailsTab', () => {
  const defaultProps = {
    isCreate: true,
    title: '',
    onTitleChange: vi.fn(),
    description: '',
    onDescriptionChange: vi.fn(),
    priority: 3 as const,
    onPriorityChange: vi.fn(),
    status: 'todo' as const,
    onStatusChange: vi.fn(),
    subtasks: [],
    newSubtaskTitle: '',
    onNewSubtaskTitleChange: vi.fn(),
    onAddSubtask: vi.fn(),
    onToggleSubtask: vi.fn(),
    onDeleteSubtask: vi.fn(),
  };

  it('renders the Title, Description, Priority, and Status labels', () => {
    render(<DetailsTab {...defaultProps} />);
    expect(screen.getByText('Title')).toBeInTheDocument();
    expect(screen.getByText('Description')).toBeInTheDocument();
    expect(screen.getByText('Priority')).toBeInTheDocument();
    expect(screen.getByText('Status')).toBeInTheDocument();
  });

  it('shows all four priority pills (Urgent / High / Medium / Low)', () => {
    render(<DetailsTab {...defaultProps} />);
    expect(screen.getByText('Urgent')).toBeInTheDocument();
    expect(screen.getByText('High')).toBeInTheDocument();
    expect(screen.getByText('Medium')).toBeInTheDocument();
    expect(screen.getByText('Low')).toBeInTheDocument();
  });

  it('propagates the current title into the input', () => {
    render(<DetailsTab {...defaultProps} title="hello world" />);
    const input = screen.getByLabelText('Title') as HTMLInputElement;
    expect(input.value).toBe('hello world');
  });

  it('does not render the Subtasks section in create mode', () => {
    render(<DetailsTab {...defaultProps} isCreate />);
    expect(screen.queryByText('Subtasks')).toBeNull();
  });

  it('calls onTitleChange when the title input changes', () => {
    const handler = vi.fn();
    render(<DetailsTab {...defaultProps} onTitleChange={handler} />);
    const input = screen.getByLabelText('Title');
    fireEvent.change(input, { target: { value: 'New' } });
    expect(handler).toHaveBeenCalledWith('New');
  });
});

describe('ScheduleTab', () => {
  const defaultProps = {
    isCreate: false,
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
    recurrenceEndMode: 'never' as const,
    onRecurrenceEndModeChange: vi.fn(),
    recurrenceEndAfter: 10,
    onRecurrenceEndAfterChange: vi.fn(),
    recurrenceEndDate: '',
    onRecurrenceEndDateChange: vi.fn(),
    duration: '',
    onDurationChange: vi.fn(),
  };

  it('renders the Due Date, Reminders, Recurrence, and Duration sections', () => {
    render(<ScheduleTab {...defaultProps} />);
    expect(screen.getByText('Due Date')).toBeInTheDocument();
    expect(screen.getByText('Reminders')).toBeInTheDocument();
    expect(screen.getByText('Recurrence')).toBeInTheDocument();
    expect(
      screen.getByText('Estimated Duration (Minutes)'),
    ).toBeInTheDocument();
  });

  it('renders the four quick-date chips (Today / Tomorrow / Next Week / None)', () => {
    render(<ScheduleTab {...defaultProps} />);
    // role="group" with chip buttons inside.
    expect(
      screen.getByRole('button', { name: 'Today' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Tomorrow' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Next Week' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'None' }),
    ).toBeInTheDocument();
  });

  it('hides the Due Time field until a date is set', () => {
    render(<ScheduleTab {...defaultProps} dueDate="" />);
    expect(screen.queryByText('Due Time')).toBeNull();
  });

  it('shows the Due Time field once a date is set', () => {
    render(<ScheduleTab {...defaultProps} dueDate="2026-05-15" />);
    expect(screen.getByText('Due Time')).toBeInTheDocument();
  });

  it('exposes "Add Time" as an opener for the AnalogClockPicker dialog', () => {
    render(<ScheduleTab {...defaultProps} dueDate="2026-05-15" />);
    expect(
      screen.getByRole('button', { name: /Add Time/i }),
    ).toBeInTheDocument();
  });

  it('exposes "Add Reminder" as an opener', () => {
    render(<ScheduleTab {...defaultProps} />);
    expect(
      screen.getByRole('button', { name: /Add Reminder/i }),
    ).toBeInTheDocument();
  });

  it('exposes "Set Recurrence…" as an opener when none is set', () => {
    render(<ScheduleTab {...defaultProps} />);
    expect(
      screen.getByRole('button', { name: /Set Recurrence/i }),
    ).toBeInTheDocument();
  });

  it('does not render a slider for time of day (clock-only invariant)', () => {
    // Memory: time inputs always render a 3-hand clock, never sliders.
    render(<ScheduleTab {...defaultProps} dueDate="2026-05-15" />);
    expect(screen.queryByRole('slider')).toBeNull();
  });
});

describe('ScheduleTab — Logged Time section', () => {
  const baseProps = {
    isCreate: false,
    taskId: 'task-cloud-1' as string | null,
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
    recurrenceEndMode: 'never' as const,
    onRecurrenceEndModeChange: vi.fn(),
    recurrenceEndAfter: 10,
    onRecurrenceEndAfterChange: vi.fn(),
    recurrenceEndDate: '',
    onRecurrenceEndDateChange: vi.fn(),
    duration: '',
    onDurationChange: vi.fn(),
  };

  const sampleTiming: TaskTiming = {
    id: 'timing-1',
    task_id: 'task-cloud-1',
    started_at: 1700000000000,
    ended_at: 1700001800000,
    duration_minutes: 30,
    source: 'manual',
    notes: 'Drafting outline',
    created_at: 1700000000000,
  };

  const otherTaskTiming: TaskTiming = {
    id: 'timing-2',
    task_id: 'other-task',
    started_at: 1700100000000,
    ended_at: null,
    duration_minutes: 15,
    source: 'pomodoro',
    notes: null,
    created_at: 1700100000000,
  };

  beforeEach(() => {
    useTaskTimingsStore.setState({ timings: [] });
  });

  it('renders the Logged Time section in edit mode', () => {
    render(<ScheduleTab {...baseProps} />);
    expect(screen.getByText('Logged Time')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /Log Time/i }),
    ).toBeInTheDocument();
  });

  it('hides the Logged Time section in create mode (no persisted taskId yet)', () => {
    render(<ScheduleTab {...baseProps} isCreate taskId={null} />);
    expect(screen.queryByText('Logged Time')).toBeNull();
  });

  it('shows the empty-state copy when the task has no timings', () => {
    render(<ScheduleTab {...baseProps} />);
    expect(
      screen.getByText(/No time logged yet/i),
    ).toBeInTheDocument();
  });

  it('renders one row per timing for the current task, filtering out other tasks', () => {
    useTaskTimingsStore.setState({
      timings: [sampleTiming, otherTaskTiming],
    });
    render(<ScheduleTab {...baseProps} />);
    const rows = screen.getAllByTestId('logged-time-row');
    expect(rows).toHaveLength(1);
    // Notes surface in the row.
    expect(rows[0].textContent).toContain('Drafting outline');
    // Duration label uses the singular/plural formatter. The Total
    // aggregate also renders "30 Minutes" so we scope the assertion
    // to the row itself.
    expect(rows[0].textContent).toContain('30 Minutes');
    // Source pill renders the friendly label.
    expect(rows[0].textContent).toContain('Manual');
  });

  it('sorts timings by start_at descending', () => {
    const newer: TaskTiming = {
      ...sampleTiming,
      id: 'timing-newer',
      started_at: 1700500000000,
      ended_at: 1700503600000,
      duration_minutes: 60,
      notes: 'Newer entry',
    };
    useTaskTimingsStore.setState({ timings: [sampleTiming, newer] });
    render(<ScheduleTab {...baseProps} />);
    const rows = screen.getAllByTestId('logged-time-row');
    // The newer entry sorts first, so its notes appear before the
    // sampleTiming notes in DOM order.
    expect(rows[0].textContent).toContain('Newer entry');
    expect(rows[1].textContent).toContain('Drafting outline');
  });

  it('opens the create dialog when "Log Time" is clicked', () => {
    render(<ScheduleTab {...baseProps} />);
    fireEvent.click(screen.getByRole('button', { name: 'Log Time' }));
    // The Modal renders the dialog with a Start input. The
    // "Duration (Minutes)" label collides with the existing
    // "Estimated Duration (Minutes)" section label, so we scope to
    // the input id to disambiguate.
    expect(document.getElementById('logged-time-start')).not.toBeNull();
    expect(document.getElementById('logged-time-duration')).not.toBeNull();
  });
});
