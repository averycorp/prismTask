import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { PriorityBadge } from '../PriorityBadge';

// Mock PRIORITY_CONFIG to ensure tests are stable and isolated
vi.mock('@/utils/priority', () => ({
  PRIORITY_CONFIG: {
    1: { label: 'Urgent', color: '#ff0000', bgColor: '#ffeeee' },
    2: { label: 'High', color: '#ff8800', bgColor: '#ffeedd' },
    3: { label: 'Normal', color: '#0088ff', bgColor: '#ddeeff' },
    4: { label: 'Low', color: '#888888', bgColor: '#eeeeee' },
  }
}));

describe('PriorityBadge', () => {
  it('renders nothing for invalid priority', () => {
    const { container } = render(<PriorityBadge priority={5 as unknown as 1} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders icon and label for full badge', () => {
    render(<PriorityBadge priority={1} />);
    const badge = screen.getByText('Urgent');
    expect(badge).toBeInTheDocument();
    expect(badge.tagName).toBe('SPAN');
    expect(badge).toHaveStyle('color: #ff0000');
    expect(badge).toHaveStyle('background-color: #ffeeee');
  });

  it('renders only icon when iconOnly is true', () => {
    render(<PriorityBadge priority={2} iconOnly />);
    // Check that label text is not rendered
    expect(screen.queryByText('High')).not.toBeInTheDocument();

    // Check wrapper properties
    const wrapper = screen.getByTitle('High');
    expect(wrapper).toBeInTheDocument();
    expect(wrapper.tagName).toBe('SPAN');

    // Check inner SVG color
    const svg = wrapper.querySelector('svg');
    expect(svg).toHaveStyle('color: #ff8800');
  });

  it('applies custom className', () => {
    render(<PriorityBadge priority={3} className="custom-class" />);
    const badge = screen.getByText('Normal');
    expect(badge).toHaveClass('custom-class');

    render(<PriorityBadge priority={4} iconOnly className="custom-icon-class" />);
    const iconWrapper = screen.getByTitle('Low');
    expect(iconWrapper).toHaveClass('custom-icon-class');
  });
});
