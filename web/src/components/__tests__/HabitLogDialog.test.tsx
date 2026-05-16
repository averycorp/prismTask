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

// Stub the habit-log store so the dialog can call `logActivity` without
// touching Firestore. The store is consumed via a Zustand selector
// (`s.logActivity`); the simplest stable shape is a callable that
// receives the selector and returns the mock.
vi.mock('@/stores/habitLogStore', () => ({
  useHabitLogStore: (selector: (s: { logActivity: typeof logActivityMock }) => unknown) =>
    selector({ logActivity: logActivityMock }),
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

import { HabitLogDialog } from '@/components/HabitLogDialog';

function makeHabit(overrides: Partial<Habit> = {}): Habit {
  return {
    id: 'habit-1',
    user_id: 'user-1',
    name: 'Stretch',
    description: null,
    icon: '🧘',
    color: '#84cc16',
    category: null,
    frequency: 'daily',
    target_count: 1,
    active_days_json: null,
    is_active: true,
    is_bookable: false,
    created_at: '2026-05-01T00:00:00.000Z',
    updated_at: '2026-05-01T00:00:00.000Z',
    ...overrides,
  };
}

describe('HabitLogDialog', () => {
  beforeEach(() => {
    logActivityMock.mockClear();
  });

  it('renders with title "Log Entry — <habit name>"', () => {
    render(<HabitLogDialog habit={makeHabit()} onClose={() => {}} />);
    expect(
      screen.getByText(/Log Entry — Stretch/i),
    ).toBeInTheDocument();
  });

  it('renders the AnalogClockPicker', () => {
    render(<HabitLogDialog habit={makeHabit()} onClose={() => {}} />);
    expect(screen.getByTestId('analog-clock-picker')).toBeInTheDocument();
  });

  it('Cancel calls onClose without writing a log', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<HabitLogDialog habit={makeHabit()} onClose={onClose} />);

    await user.click(screen.getByRole('button', { name: /Cancel/i }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(logActivityMock).not.toHaveBeenCalled();
  });

  it('Log writes a habit_logs entry via the store and closes', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const onLogged = vi.fn();
    render(
      <HabitLogDialog
        habit={makeHabit()}
        onClose={onClose}
        onLogged={onLogged}
      />,
    );

    await user.type(
      screen.getByLabelText(/Notes/i),
      'Felt great',
    );
    await user.click(screen.getByRole('button', { name: /^Log$/ }));

    await waitFor(() => {
      expect(logActivityMock).toHaveBeenCalledTimes(1);
    });

    const arg = logActivityMock.mock.calls[0][0] as {
      habit_id: string;
      notes: string | null;
      date: number;
    };
    expect(arg.habit_id).toBe('habit-1');
    expect(arg.notes).toBe('Felt great');
    expect(Number.isFinite(arg.date)).toBe(true);

    await waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1);
    });
    expect(onLogged).toHaveBeenCalledTimes(1);
  });

  it('normalises empty notes to null on save', async () => {
    const user = userEvent.setup();
    render(<HabitLogDialog habit={makeHabit()} onClose={() => {}} />);

    await user.click(screen.getByRole('button', { name: /^Log$/ }));

    await waitFor(() => {
      expect(logActivityMock).toHaveBeenCalledTimes(1);
    });
    const arg = logActivityMock.mock.calls[0][0] as { notes: string | null };
    expect(arg.notes).toBeNull();
  });
});
