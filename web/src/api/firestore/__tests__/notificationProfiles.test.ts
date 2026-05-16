import { describe, it, expect, vi, beforeEach } from 'vitest';

const {
  setDocMock,
  getDocMock,
  getDocsMock,
  onSnapshotMock,
  docMock,
  collectionMock,
} = vi.hoisted(() => ({
  setDocMock: vi.fn(),
  getDocMock: vi.fn(),
  getDocsMock: vi.fn(),
  onSnapshotMock: vi.fn(),
  docMock: vi.fn(),
  collectionMock: vi.fn(),
}));

vi.mock('@/lib/firebase', () => ({ firestore: {} }));
vi.mock('firebase/firestore', () => ({
  collection: collectionMock,
  doc: docMock,
  getDoc: getDocMock,
  getDocs: getDocsMock,
  onSnapshot: onSnapshotMock,
  setDoc: setDocMock,
}));

import {
  DEFAULT_ESCALATION_CHAIN,
  DEFAULT_QUIET_HOURS,
  decodeEscalationChain,
  decodeQuietHours,
  encodeEscalationChain,
  encodeQuietHours,
  isOvernightWindow,
  updateProfile,
} from '@/api/firestore/notificationProfiles';

beforeEach(() => {
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  getDocMock.mockReset();
  getDocsMock.mockReset();
  onSnapshotMock.mockReset();
  docMock.mockReset();
  collectionMock.mockReset();
  docMock.mockReturnValue({ path: 'users/uid-1/notification_profiles/prof-1' });
  collectionMock.mockReturnValue({ path: 'users/uid-1/notification_profiles' });
});

describe('notificationProfiles helpers (parity unit 20)', () => {
  describe('quiet-hours round-trip', () => {
    it('encodes default window to the Android wire format (HH:mm + day names)', () => {
      const json = encodeQuietHours({
        enabled: true,
        startHour: 22,
        startMinute: 30,
        endHour: 7,
        endMinute: 0,
        days: ['MONDAY', 'TUESDAY'],
        priorityOverrideTiers: ['critical'],
      });
      const parsed = JSON.parse(json);
      expect(parsed).toMatchObject({
        enabled: true,
        start: '22:30',
        end: '07:00',
        days: ['MONDAY', 'TUESDAY'],
        priorityOverrideTiers: ['critical'],
      });
    });

    it('decodes back to the same window (round-trip stable)', () => {
      const original = {
        enabled: true,
        startHour: 21,
        startMinute: 45,
        endHour: 6,
        endMinute: 15,
        days: ['FRIDAY', 'SATURDAY'],
        priorityOverrideTiers: ['high', 'critical'] as const,
      };
      const json = encodeQuietHours({ ...original });
      const decoded = decodeQuietHours(json);
      expect(decoded.enabled).toBe(true);
      expect(decoded.startHour).toBe(21);
      expect(decoded.startMinute).toBe(45);
      expect(decoded.endHour).toBe(6);
      expect(decoded.endMinute).toBe(15);
      expect(decoded.days).toEqual(['FRIDAY', 'SATURDAY']);
      expect(decoded.priorityOverrideTiers).toEqual(['high', 'critical']);
    });

    it('decodes null JSON to the disabled default', () => {
      const window = decodeQuietHours(null);
      expect(window).toEqual(DEFAULT_QUIET_HOURS);
    });

    it('decodes garbage JSON to the disabled default (no throw)', () => {
      const window = decodeQuietHours('{not valid json');
      expect(window).toEqual(DEFAULT_QUIET_HOURS);
    });

    it('clamps malformed HH:mm strings into the legal range', () => {
      const window = decodeQuietHours(
        JSON.stringify({ enabled: true, start: '99:99', end: '99:99', days: [], priorityOverrideTiers: [] }),
      );
      expect(window.startHour).toBe(23);
      expect(window.startMinute).toBe(59);
      expect(window.endHour).toBe(23);
      expect(window.endMinute).toBe(59);
    });

    it('isOvernightWindow flags windows whose end-time precedes start', () => {
      expect(
        isOvernightWindow({
          enabled: true,
          startHour: 22,
          startMinute: 0,
          endHour: 7,
          endMinute: 0,
          days: [],
          priorityOverrideTiers: [],
        }),
      ).toBe(true);
      expect(
        isOvernightWindow({
          enabled: true,
          startHour: 9,
          startMinute: 0,
          endHour: 17,
          endMinute: 0,
          days: [],
          priorityOverrideTiers: [],
        }),
      ).toBe(false);
      expect(
        isOvernightWindow({
          enabled: true,
          startHour: 9,
          startMinute: 30,
          endHour: 9,
          endMinute: 15,
          days: [],
          priorityOverrideTiers: [],
        }),
      ).toBe(true);
    });
  });

  describe('escalation chain round-trip', () => {
    it('encodes + decodes chain stably', () => {
      const chain = {
        enabled: true,
        stopOnInteraction: false,
        maxAttempts: 3,
        steps: [
          { action: 'gentle' as const, delayMs: 0, triggerTiers: [] },
          {
            action: 'standard' as const,
            delayMs: 120_000,
            triggerTiers: ['medium' as const, 'high' as const],
          },
        ],
      };
      const decoded = decodeEscalationChain(encodeEscalationChain(chain));
      expect(decoded).toEqual(chain);
    });

    it('decodes null to the disabled default', () => {
      expect(decodeEscalationChain(null)).toEqual(DEFAULT_ESCALATION_CHAIN);
    });

    it('drops malformed steps without throwing', () => {
      const json = JSON.stringify({
        enabled: true,
        steps: [
          { action: 'gentle', delayMs: 0, triggerTiers: [] },
          { action: 'not_a_real_action', delayMs: 0, triggerTiers: [] },
          null,
          'banana',
        ],
      });
      const chain = decodeEscalationChain(json);
      expect(chain.steps).toHaveLength(1);
      expect(chain.steps[0].action).toBe('gentle');
    });
  });

  describe('updateProfile', () => {
    it('writes merge=true with updatedAt stamped automatically', async () => {
      await updateProfile('uid-1', 'prof-1', { soundId: 'chime_gentle' });
      const [, payload, opts] = setDocMock.mock.calls[0];
      expect(payload).toMatchObject({
        soundId: 'chime_gentle',
        updatedAt: expect.any(Number),
      });
      expect(opts).toEqual({ merge: true });
    });

    it('targets users/{uid}/notification_profiles/{cloudId}', async () => {
      await updateProfile('uid-1', 'prof-1', { silent: true });
      expect(collectionMock).toHaveBeenCalledWith(
        {},
        'users',
        'uid-1',
        'notification_profiles',
      );
      expect(docMock).toHaveBeenCalled();
    });
  });
});
