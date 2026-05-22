import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { AiActionChip } from '../AiActionChip';
import type { ChatActionPayload } from '@/types/chat';

vi.mock('@/features/chat/chatActions', () => ({
  actionLabel: vi.fn((a: any) => `Mock Label for ${a.type}`),
  actionSignature: vi.fn((a: any) => a.sig),
}));

describe('AiActionChip', () => {
  const mockAction: ChatActionPayload = { type: 'mark_complete', taskId: '123', sig: 'sig123' } as any;

  it('renders correctly', () => {
    const disabledSignatures = new Set<string>();
    render(<AiActionChip action={mockAction} disabledSignatures={disabledSignatures} onClick={vi.fn()} />);

    const button = screen.getByRole('button', { name: 'Mock Label for mark_complete' });
    expect(button).toBeInTheDocument();
    expect(button).not.toBeDisabled();
  });

  it('calls onClick when clicked', () => {
    const disabledSignatures = new Set<string>();
    const handleClick = vi.fn();
    render(<AiActionChip action={mockAction} disabledSignatures={disabledSignatures} onClick={handleClick} />);

    fireEvent.click(screen.getByRole('button'));
    expect(handleClick).toHaveBeenCalledWith(mockAction);
  });

  it('is disabled when its signature is in disabledSignatures', () => {
    const disabledSignatures = new Set<string>(['sig123']);
    render(<AiActionChip action={mockAction} disabledSignatures={disabledSignatures} onClick={vi.fn()} />);

    const button = screen.getByRole('button');
    expect(button).toBeDisabled();
  });

  it('is not disabled when its signature is not in disabledSignatures', () => {
    const disabledSignatures = new Set<string>(['otherSig']);
    render(<AiActionChip action={mockAction} disabledSignatures={disabledSignatures} onClick={vi.fn()} />);

    const button = screen.getByRole('button');
    expect(button).not.toBeDisabled();
  });
});
