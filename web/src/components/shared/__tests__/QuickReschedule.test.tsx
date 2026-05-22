import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { QuickReschedule } from '../QuickReschedule';

describe('QuickReschedule', () => {
  const mockOnClose = vi.fn();
  const mockOnSelect = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2023, 9, 15)); // October 15, 2023 (Sunday)
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does not render when isOpen is false', () => {
    const { container } = render(
      <QuickReschedule isOpen={false} onClose={mockOnClose} onSelect={mockOnSelect} />
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders correctly when isOpen is true', () => {
    render(<QuickReschedule isOpen={true} onClose={mockOnClose} onSelect={mockOnSelect} />);

    expect(screen.getByText('Reschedule')).toBeInTheDocument();
    expect(screen.getByText('Today')).toBeInTheDocument();
    expect(screen.getByText('Tomorrow')).toBeInTheDocument();
    expect(screen.getByText('Next Monday')).toBeInTheDocument();
    expect(screen.getByText('Next Week')).toBeInTheDocument();
    expect(screen.getByText('Pick Date...')).toBeInTheDocument();
  });

  it('calls onSelect with correct date and onClose when preset is clicked', () => {
    render(<QuickReschedule isOpen={true} onClose={mockOnClose} onSelect={mockOnSelect} />);

    fireEvent.click(screen.getByText('Today'));

    expect(mockOnSelect).toHaveBeenCalledWith('2023-10-15');
    expect(mockOnClose).toHaveBeenCalledTimes(1);
  });

  it('switches to custom date picker and selects custom date', () => {
    render(<QuickReschedule isOpen={true} onClose={mockOnClose} onSelect={mockOnSelect} />);

    // Switch to date picker
    fireEvent.click(screen.getByText('Pick Date...'));

    // Pick Date button should disappear, input should appear
    expect(screen.queryByText('Pick Date...')).not.toBeInTheDocument();

    // Set custom date
    const dateInput = screen.getByDisplayValue('');
    fireEvent.change(dateInput, { target: { value: '2023-11-01' } });

    // Click "Set Date" button that appears
    fireEvent.click(screen.getByText('Set Date'));

    expect(mockOnSelect).toHaveBeenCalledWith('2023-11-01');
    expect(mockOnClose).toHaveBeenCalledTimes(1);
  });

  it('submits custom date on Enter key', () => {
    render(<QuickReschedule isOpen={true} onClose={mockOnClose} onSelect={mockOnSelect} />);

    fireEvent.click(screen.getByText('Pick Date...'));

    const dateInput = screen.getByDisplayValue('');
    fireEvent.change(dateInput, { target: { value: '2023-12-25' } });
    fireEvent.keyDown(dateInput, { key: 'Enter', code: 'Enter' });

    expect(mockOnSelect).toHaveBeenCalledWith('2023-12-25');
    expect(mockOnClose).toHaveBeenCalledTimes(1);
  });

  it('closes when clicking outside', () => {
    render(
      <div data-testid="outside">
        Outside Element
        <QuickReschedule isOpen={true} onClose={mockOnClose} onSelect={mockOnSelect} />
      </div>
    );

    fireEvent.mouseDown(screen.getByTestId('outside'));

    expect(mockOnClose).toHaveBeenCalledTimes(1);
  });

  it('applies fixed positioning if anchorPoint is provided', () => {
    const anchorPoint = { x: 100, y: 200 };
    render(
      <QuickReschedule isOpen={true} onClose={mockOnClose} onSelect={mockOnSelect} anchorPoint={anchorPoint} />
    );

    // The container element should have these styles
    const container = screen.getByText('Reschedule').parentElement;
    expect(container).toHaveStyle('position: fixed');
    expect(container).toHaveStyle('left: 100px');
    expect(container).toHaveStyle('top: 200px');
  });

  it('resets internal state when closed and reopened', () => {
    const { rerender } = render(<QuickReschedule isOpen={true} onClose={mockOnClose} onSelect={mockOnSelect} />);

    fireEvent.click(screen.getByText('Pick Date...'));
    expect(screen.queryByDisplayValue('')).toBeInTheDocument();

    // Close
    rerender(<QuickReschedule isOpen={false} onClose={mockOnClose} onSelect={mockOnSelect} />);

    // Reopen
    rerender(<QuickReschedule isOpen={true} onClose={mockOnClose} onSelect={mockOnSelect} />);

    // Should show the preset buttons again
    expect(screen.getByText('Pick Date...')).toBeInTheDocument();
  });
});
