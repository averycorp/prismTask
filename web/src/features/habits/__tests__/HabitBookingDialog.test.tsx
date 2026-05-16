import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Habit } from '@/types/habit';

const { logActivityMock } = vi.hoisted(() => ({
  logActivityMock: vi.fn().mockResolvedValue({
    id: 'log-1',
    habit_id: 'habit-1',
    date: 0,
    notes: null,
    created_at: 0,
  }),
}));

vi.mock('@/stores/habitLogStore', () => ({
  useHabitLogStore: (
    selector: (s: { logActivity: typeof logActivityMock }) => unknown,
  ) => selector({ logActivity: logActivityMock }),
}));

vi.mock('@/stores/settingsStore', () => ({
  useSettingsStore: (selector: (s: { timeFormat: '12h' | '24h' }) => unknown) =>
    selector({ timeFormat: '24h' }),
}));

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

import { HabitBookingDialog } from '@/features/habits/HabitBookingDialog';

function makeHabit(overrides: Partial<Habit> = {}): Habit {
  return {
    id: 'habit-1',
    user_id: 'user-1',
    name: 'Dentist Visit',
    description: null,
    icon: '🦷',
    color: '#0ea5e9',
    category: null,
    frequency: 'monthly',
    target_count: 1,
    active_days_json: null,
    is_active: true,
    is_bookable: true,
    created_at: '2026-05-01T00:00:00.000Z',
    updated_at: '2026-05-01T00:00:00.000Z',
    ...overrides,
  };
}

describe('HabitBookingDialog', () => {
  beforeEach(() => {
    logActivityMock.mockClear();
  });

  it('renders with title "Book Habit — <habit name>"', () => {
    render(<HabitBookingDialog habit={makeHabit()} onClose={() => {}} />);
    expect(
      screen.getByText(/Book Habit — Dentist Visit/i),
    ).toBeInTheDocument();
  });

  it('renders both the date input and the AnalogClockPicker', () => {
    render(<HabitBookingDialog habit={makeHabit()} onClose={() => {}} />);
    expect(screen.getByLabelText(/^Date$/i)).toBeInTheDocument();
    expect(screen.getByTestId('analog-clock-picker')).toBeInTheDocument();
  });

  it('Cancel closes without writing a log', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<HabitBookingDialog habit={makeHabit()} onClose={onClose} />);

    await user.click(screen.getByRole('button', { name: /Cancel/i }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(logActivityMock).not.toHaveBeenCalled();
  });

  it('Save composes date + clock time and writes a habit_logs entry', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<HabitBookingDialog habit={makeHabit()} onClose={onClose} />);

    // Override the date to a fixed ISO so we can verify the composed
    // timestamp picks it up.
    const dateInput = screen.getByLabelText(/^Date$/i) as HTMLInputElement;
    await user.clear(dateInput);
    await user.type(dateInput, '2026-06-15');

    await user.type(screen.getByLabelText(/Notes/i), 'Cleaning');
    await user.click(screen.getByRole('button', { name: /^Save$/ }));

    await waitFor(() => {
      expect(logActivityMock).toHaveBeenCalledTimes(1);
    });
    const arg = logActivityMock.mock.calls[0][0] as {
      habit_id: string;
      notes: string | null;
      date: number;
    };
    expect(arg.habit_id).toBe('habit-1');
    expect(arg.notes).toBe('Cleaning');
    // 2026-06-15 in local time → year/month/day must match what we
    // composed, regardless of the time of day.
    const composed = new Date(arg.date);
    expect(composed.getFullYear()).toBe(2026);
    expect(composed.getMonth()).toBe(5);
    expect(composed.getDate()).toBe(15);

    await waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });
});
