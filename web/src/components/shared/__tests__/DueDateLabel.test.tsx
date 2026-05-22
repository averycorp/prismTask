import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DueDateLabel } from '../DueDateLabel';

vi.mock('@/utils/dates', () => ({
  formatDate: vi.fn((date) => `Formatted: ${date}`),
  isOverdue: vi.fn(),
}));

// Partially mock date-fns to control today/tomorrow checks reliably
vi.mock('date-fns', async (importOriginal) => {
  const actual = await importOriginal<typeof import('date-fns')>();
  return {
    ...actual,
    isToday: vi.fn(),
    isTomorrow: vi.fn(),
  };
});

import { isOverdue } from '@/utils/dates';
import { isToday, isTomorrow } from 'date-fns';

describe('DueDateLabel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing if date is null', () => {
    const { container } = render(<DueDateLabel date={null} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders correctly for a normal future date', () => {
    (isOverdue as ReturnType<typeof vi.fn>).mockReturnValue(false);
    (isToday as ReturnType<typeof vi.fn>).mockReturnValue(false);
    (isTomorrow as ReturnType<typeof vi.fn>).mockReturnValue(false);

    render(<DueDateLabel date="2023-12-25" />);

    const wrapper = screen.getByText('Formatted: 2023-12-25');
    expect(wrapper).toBeInTheDocument();
    expect(wrapper).toHaveClass('text-[var(--color-text-secondary)]');
  });

  it('applies overdue styling', () => {
    (isOverdue as ReturnType<typeof vi.fn>).mockReturnValue(true);
    (isToday as ReturnType<typeof vi.fn>).mockReturnValue(false);
    (isTomorrow as ReturnType<typeof vi.fn>).mockReturnValue(false);

    render(<DueDateLabel date="2023-10-01" />);

    const wrapper = screen.getByText('Formatted: 2023-10-01');
    expect(wrapper).toHaveClass('text-red-500');
  });

  it('applies today styling', () => {
    (isOverdue as ReturnType<typeof vi.fn>).mockReturnValue(false);
    (isToday as ReturnType<typeof vi.fn>).mockReturnValue(true);
    (isTomorrow as ReturnType<typeof vi.fn>).mockReturnValue(false);

    render(<DueDateLabel date="2023-10-15" />);

    const wrapper = screen.getByText('Formatted: 2023-10-15');
    expect(wrapper).toHaveClass('text-[var(--color-accent)]');
  });

  it('applies tomorrow styling', () => {
    (isOverdue as ReturnType<typeof vi.fn>).mockReturnValue(false);
    (isToday as ReturnType<typeof vi.fn>).mockReturnValue(false);
    (isTomorrow as ReturnType<typeof vi.fn>).mockReturnValue(true);

    render(<DueDateLabel date="2023-10-16" />);

    const wrapper = screen.getByText('Formatted: 2023-10-16');
    expect(wrapper).toHaveClass('text-amber-500');
  });

  it('hides icon when showIcon is false', () => {
    (isOverdue as ReturnType<typeof vi.fn>).mockReturnValue(false);
    (isToday as ReturnType<typeof vi.fn>).mockReturnValue(false);
    (isTomorrow as ReturnType<typeof vi.fn>).mockReturnValue(false);

    const { container } = render(<DueDateLabel date="2023-12-25" showIcon={false} />);

    expect(container.querySelector('svg')).toBeNull();
  });

  it('applies custom className', () => {
    (isOverdue as ReturnType<typeof vi.fn>).mockReturnValue(false);
    (isToday as ReturnType<typeof vi.fn>).mockReturnValue(false);
    (isTomorrow as ReturnType<typeof vi.fn>).mockReturnValue(false);

    render(<DueDateLabel date="2023-12-25" className="custom-due-date" />);

    const wrapper = screen.getByText('Formatted: 2023-12-25');
    expect(wrapper).toHaveClass('custom-due-date');
  });
});
