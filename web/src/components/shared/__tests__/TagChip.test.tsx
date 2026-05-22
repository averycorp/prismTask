import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { TagChip } from '../TagChip';
import type { Tag } from '@/types/tag';

describe('TagChip', () => {
  const mockTag: Tag = {
    id: 'tag-1',
    user_id: 'user-1',
    name: 'Work',
    color: '#ff0000',
    sort_order: 0,
    archived: false,
    created_at: ''
  };

  it('renders tag name and color indicator', () => {
    const { container } = render(<TagChip tag={mockTag} />);

    // Check name
    expect(screen.getByText('Work')).toBeInTheDocument();

    // Check color indicator
    const colorIndicator = container.querySelector('.h-2.w-2');
    expect(colorIndicator).toBeInTheDocument();
    expect(colorIndicator).toHaveStyle('background-color: rgb(255, 0, 0)'); // #ff0000 converted
  });

  it('uses fallback color if tag has no color', () => {
    const tagWithoutColor = { ...mockTag, color: undefined } as unknown as Tag;
    const { container } = render(<TagChip tag={tagWithoutColor} />);

    const colorIndicator = container.querySelector('.h-2.w-2');
    expect(colorIndicator).toHaveStyle('background-color: var(--color-accent)');
  });

  it('does not render remove button by default', () => {
    render(<TagChip tag={mockTag} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('renders remove button and calls onRemove when clicked', () => {
    const handleRemove = vi.fn();
    render(<TagChip tag={mockTag} onRemove={handleRemove} />);

    const removeButton = screen.getByRole('button', { name: /Remove Work/i });
    expect(removeButton).toBeInTheDocument();

    fireEvent.click(removeButton);
    expect(handleRemove).toHaveBeenCalledTimes(1);
  });

  it('applies custom className', () => {
    const { container } = render(<TagChip tag={mockTag} className="custom-tag-class" />);
    // The first element is the span wrapper
    expect(container.firstChild).toHaveClass('custom-tag-class');
  });
});
