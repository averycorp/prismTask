import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Checkbox } from '@/components/ui/Checkbox';

describe('Checkbox accessible name (B-10)', () => {
  it('uses ariaLabel as the accessible name', () => {
    render(<Checkbox checked={false} onChange={vi.fn()} ariaLabel="Mark Buy milk complete" />);
    const cb = screen.getByRole('checkbox', { name: 'Mark Buy milk complete' });
    expect(cb).toBeInTheDocument();
  });

  it('falls back to the visible label for the accessible name', () => {
    render(<Checkbox checked={false} onChange={vi.fn()} label="Subscribe" />);
    expect(screen.getByRole('checkbox', { name: 'Subscribe' })).toBeInTheDocument();
  });

  it('every rendered role=checkbox has a non-empty accessible name', () => {
    render(<Checkbox checked onChange={vi.fn()} ariaLabel="Done" />);
    for (const cb of screen.getAllByRole('checkbox')) {
      const name = cb.getAttribute('aria-label') ?? cb.textContent ?? '';
      expect(name.trim().length).toBeGreaterThan(0);
    }
  });
});
