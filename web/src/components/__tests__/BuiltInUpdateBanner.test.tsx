import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import {
  BuiltInUpdateBanner,
  computePendingUpdates,
} from '@/components/BuiltInUpdateBanner';

vi.mock('@/stores/habitStore', () => ({
  useHabitStore: (selector: (s: unknown) => unknown) => selector({ habits: [] }),
}));

describe('computePendingUpdates', () => {
  it('returns empty when latestVersions is empty', () => {
    expect(
      computePendingUpdates(
        [{ id: 'h1', name: 'Meditate', is_built_in: true, template_key: 'meditation', source_version: 1 }],
        {},
      ),
    ).toEqual([]);
  });

  it('returns nothing for non-built-in habits', () => {
    expect(
      computePendingUpdates(
        [
          {
            id: 'h1',
            name: 'Custom',
            is_built_in: false,
            template_key: 'meditation',
            source_version: 1,
          },
        ],
        { meditation: 2 },
      ),
    ).toEqual([]);
  });

  it('returns an update when source_version < latest', () => {
    const updates = computePendingUpdates(
      [
        {
          id: 'h1',
          name: 'Meditate',
          is_built_in: true,
          template_key: 'meditation',
          source_version: 1,
        },
      ],
      { meditation: 3 },
    );
    expect(updates).toHaveLength(1);
    expect(updates[0]).toMatchObject({
      templateKey: 'meditation',
      fromVersion: 1,
      toVersion: 3,
    });
  });

  it('returns nothing when habit is detached from template', () => {
    expect(
      computePendingUpdates(
        [
          {
            id: 'h1',
            name: 'Meditate',
            is_built_in: true,
            template_key: 'meditation',
            source_version: 1,
            is_detached_from_template: true,
          },
        ],
        { meditation: 2 },
      ),
    ).toEqual([]);
  });

  it('skips habits already at or above latest', () => {
    expect(
      computePendingUpdates(
        [
          {
            id: 'h1',
            name: 'Meditate',
            is_built_in: true,
            template_key: 'meditation',
            source_version: 3,
          },
        ],
        { meditation: 3 },
      ),
    ).toEqual([]);
  });
});

describe('BuiltInUpdateBanner — render', () => {
  it('soft-hides when latestVersions is undefined', () => {
    const { container } = render(
      <BuiltInUpdateBanner
        habitsOverride={[
          {
            id: 'h1',
            name: 'Meditate',
            is_built_in: true,
            template_key: 'meditation',
            source_version: 1,
          },
        ]}
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('soft-hides when there are no pending updates', () => {
    const { container } = render(
      <BuiltInUpdateBanner
        latestVersions={{ meditation: 1 }}
        habitsOverride={[
          {
            id: 'h1',
            name: 'Meditate',
            is_built_in: true,
            template_key: 'meditation',
            source_version: 1,
          },
        ]}
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders the banner with title-cased copy when a pending update exists', () => {
    render(
      <BuiltInUpdateBanner
        latestVersions={{ meditation: 3 }}
        habitsOverride={[
          {
            id: 'h1',
            name: 'Morning Meditation',
            is_built_in: true,
            template_key: 'meditation',
            source_version: 1,
          },
        ]}
      />,
    );
    expect(screen.getByText(/Update Available: Morning Meditation/)).toBeInTheDocument();
    expect(screen.getByText(/Built-in Habit/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Apply Update/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Dismiss Update/ })).toBeInTheDocument();
  });

  it('hides after Dismiss is clicked', () => {
    const { container } = render(
      <BuiltInUpdateBanner
        latestVersions={{ meditation: 3 }}
        habitsOverride={[
          {
            id: 'h1',
            name: 'Morning Meditation',
            is_built_in: true,
            template_key: 'meditation',
            source_version: 1,
          },
        ]}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /Dismiss Update/ }));
    expect(container.firstChild).toBeNull();
  });

  it('fires onApply with the pending update', () => {
    const onApply = vi.fn();
    render(
      <BuiltInUpdateBanner
        latestVersions={{ meditation: 3 }}
        habitsOverride={[
          {
            id: 'h1',
            name: 'Morning Meditation',
            is_built_in: true,
            template_key: 'meditation',
            source_version: 1,
          },
        ]}
        onApply={onApply}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /Apply Update/ }));
    expect(onApply).toHaveBeenCalledTimes(1);
    expect(onApply.mock.calls[0][0]).toMatchObject({
      templateKey: 'meditation',
      habitName: 'Morning Meditation',
    });
  });
});
