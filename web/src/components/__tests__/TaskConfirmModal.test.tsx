import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TaskConfirmModal, type TaskConfirmDraft } from '@/components/shared/TaskConfirmModal';

const baseDraft: TaskConfirmDraft = {
  title: 'Buy milk',
  due_date: '2026-05-20',
  due_time: '09:30',
  priority: 3,
  project_suggestion: 'Errands',
  tags: ['shopping', 'household'],
  recurrence_hint: null,
};

describe('TaskConfirmModal', () => {
  it('saves the unmodified draft when the user taps Save', async () => {
    const onSave = vi.fn();
    const onCancel = vi.fn();
    render(<TaskConfirmModal initial={baseDraft} onSave={onSave} onCancel={onCancel} />);

    await userEvent.click(screen.getByRole('button', { name: /save task/i }));

    expect(onSave).toHaveBeenCalledTimes(1);
    expect(onSave.mock.calls[0][0]).toMatchObject({
      title: 'Buy milk',
      due_date: '2026-05-20',
      due_time: '09:30',
      priority: 3,
      project_suggestion: 'Errands',
      tags: ['shopping', 'household'],
    });
  });

  it('strips leading # and blank entries from the tags field', async () => {
    const onSave = vi.fn();
    render(
      <TaskConfirmModal
        initial={{ ...baseDraft, tags: [] }}
        onSave={onSave}
        onCancel={vi.fn()}
      />,
    );

    const tagsInput = screen.getByLabelText(/tags/i);
    await userEvent.clear(tagsInput);
    await userEvent.type(tagsInput, '#urgent, , chores, ,#deep-work');
    await userEvent.click(screen.getByRole('button', { name: /save task/i }));

    expect(onSave.mock.calls[0][0].tags).toEqual(['urgent', 'chores', 'deep-work']);
  });

  it('coerces priority "None" (0) to null so the store treats it as unset', async () => {
    const onSave = vi.fn();
    render(
      <TaskConfirmModal initial={baseDraft} onSave={onSave} onCancel={vi.fn()} />,
    );

    await userEvent.selectOptions(screen.getByLabelText(/priority/i), '0');
    await userEvent.click(screen.getByRole('button', { name: /save task/i }));

    expect(onSave.mock.calls[0][0].priority).toBeNull();
  });

  it('fires onCancel when the user taps Cancel', async () => {
    const onCancel = vi.fn();
    render(
      <TaskConfirmModal initial={baseDraft} onSave={vi.fn()} onCancel={onCancel} />,
    );
    await userEvent.click(screen.getByRole('button', { name: /cancel/i }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('disables the Save button when title is blank', async () => {
    render(
      <TaskConfirmModal
        initial={{ ...baseDraft, title: '' }}
        onSave={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByRole('button', { name: /save task/i })).toBeDisabled();
  });

  it('renders a <script> title as literal text without executing it (B-04 XSS)', () => {
    const onSave = vi.fn();
    const payload = 'QA TEST <script>alert(1)</script>';
    const { container } = render(
      <TaskConfirmModal
        initial={{ ...baseDraft, title: payload }}
        onSave={onSave}
        onCancel={vi.fn()}
      />,
    );
    const titleInput = screen.getByLabelText(/^title$/i) as HTMLInputElement;
    // The raw payload is preserved as the input's literal value...
    expect(titleInput.value).toBe(payload);
    // ...and never parsed into an actual <script> element in the DOM.
    expect(container.querySelector('script')).toBeNull();
  });

  it('shows a character counter and caps the title at the max length (B-03)', async () => {
    const onSave = vi.fn();
    render(
      <TaskConfirmModal
        initial={{ ...baseDraft, title: 'Buy milk' }}
        onSave={onSave}
        onCancel={vi.fn()}
      />,
    );
    // Counter is visible up-front so any length limit is never silent.
    expect(screen.getByText('8/100')).toBeInTheDocument();
    const titleInput = screen.getByLabelText(/^title$/i) as HTMLInputElement;
    expect(titleInput.maxLength).toBe(100);
  });
});
