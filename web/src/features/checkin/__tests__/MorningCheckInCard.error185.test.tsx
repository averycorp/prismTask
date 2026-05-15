/**
 * Regression test for React error #185 (Maximum update depth exceeded).
 *
 * MorningCheckInCard subscribed to Advanced Tuning via an inline selector
 * that called `selectForgivenessConfig(s.prefs)` — that helper builds a
 * fresh object literal every call, so Zustand v5's `useSyncExternalStore`
 * snapshot fail-equality saw "the store changed" on every render and
 * looped until React tripped its render-depth limiter.
 *
 * This test mounts the card and lets React work — if the loop regresses
 * the test will time out or React will throw the depth error.
 */
import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { MorningCheckInCard } from '@/features/checkin/MorningCheckInCard';
import { useSettingsStore } from '@/stores/settingsStore';

vi.mock('@/api/firestore/checkInLogs', () => ({
  getCheckIn: vi.fn().mockResolvedValue(null),
  getRecentCheckIns: vi.fn().mockResolvedValue([]),
}));

vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: () => 'test-uid',
}));

describe('MorningCheckInCard — no infinite render loop (React #185)', () => {
  beforeEach(() => {
    useSettingsStore.setState({ showMorningCheckIn: true, startOfDayHour: 4 });
  });

  it('renders without re-rendering forever', async () => {
    render(
      <MemoryRouter>
        <MorningCheckInCard />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText('Morning check-in')).toBeInTheDocument();
    });
  });
});
