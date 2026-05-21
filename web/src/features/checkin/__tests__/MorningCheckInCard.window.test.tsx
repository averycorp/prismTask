/**
 * MorningCheckInCard window-cutoff test.
 *
 * The card mirrors `MorningCheckInBanner` in respecting the Advanced
 * Tuning `morningCheckInWindowHours` preference — once SoD + windowHours
 * has elapsed, the prompt hides until tomorrow. Previously the card
 * stayed visible all day on web while the banner correctly hid, leaving
 * an inconsistent prompt.
 */
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { MorningCheckInCard } from '@/features/checkin/MorningCheckInCard';
import { useSettingsStore } from '@/stores/settingsStore';
import { useAdvancedTuningStore } from '@/stores/advancedTuningStore';
import { DEFAULT_ADVANCED_TUNING_PREFERENCES } from '@/api/firestore/advancedTuningPreferences';

vi.mock('@/api/firestore/checkInLogs', () => ({
  getCheckIn: vi.fn().mockResolvedValue(null),
  getRecentCheckIns: vi.fn().mockResolvedValue([]),
}));

vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: () => 'test-uid',
}));

function setStores(windowHours: number) {
  useSettingsStore.setState({ showMorningCheckIn: true, startOfDayHour: 6 });
  useAdvancedTuningStore.setState({
    prefs: {
      ...DEFAULT_ADVANCED_TUNING_PREFERENCES,
      morningCheckInWindowHours: windowHours,
    },
  });
}

describe('MorningCheckInCard — availability window', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders inside the configured window after SoD', async () => {
    setStores(4); // 06:00 → 10:00 window
    vi.setSystemTime(new Date(2026, 4, 15, 7, 30)); // 07:30 — inside

    render(
      <MemoryRouter>
        <MorningCheckInCard />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByText('Morning check-in')).toBeInTheDocument();
    });
  });

  it('hides past the configured window when no log exists yet', async () => {
    setStores(4); // 06:00 → 10:00 window
    vi.setSystemTime(new Date(2026, 4, 15, 11, 30)); // 11:30 — past window

    const { container } = render(
      <MemoryRouter>
        <MorningCheckInCard />
      </MemoryRouter>,
    );

    // No async data load needed — the card short-circuits before
    // mounting the inner UI. Asserting on the rendered DOM is enough.
    expect(container.firstChild).toBeNull();
  });
});
