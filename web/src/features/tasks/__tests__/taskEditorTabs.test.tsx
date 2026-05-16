import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DetailsTab } from '@/features/tasks/tabs/DetailsTab';
import { ScheduleTab } from '@/features/tasks/tabs/ScheduleTab';

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
