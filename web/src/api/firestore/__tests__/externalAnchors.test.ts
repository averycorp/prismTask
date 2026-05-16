import { describe, it, expect, vi, beforeEach } from 'vitest';

const {
  addDocMock,
  setDocMock,
  getDocMock,
  docMock,
  collectionMock,
  serverTimestampMock,
} = vi.hoisted(() => ({
  addDocMock: vi.fn(),
  setDocMock: vi.fn(),
  getDocMock: vi.fn(),
  docMock: vi.fn(),
  collectionMock: vi.fn(),
  serverTimestampMock: vi.fn(() => '__SERVER_TIMESTAMP__'),
}));

vi.mock('firebase/firestore', () => ({
  addDoc: addDocMock,
  setDoc: setDocMock,
  getDoc: getDocMock,
  doc: docMock,
  collection: collectionMock,
  getDocs: vi.fn(),
  deleteDoc: vi.fn(),
  query: vi.fn(),
  where: vi.fn(),
  orderBy: vi.fn(),
  onSnapshot: vi.fn(),
  runTransaction: vi.fn(),
  updateDoc: vi.fn(),
  serverTimestamp: serverTimestampMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: {} }));

import { createAnchor, updateAnchor } from '@/api/firestore/externalAnchors';
import {
  decodeExternalAnchor,
  encodeExternalAnchor,
  type ExternalAnchor,
} from '@/types/externalAnchor';

beforeEach(() => {
  addDocMock.mockReset();
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  getDocMock.mockReset();
  docMock.mockReset();
  collectionMock.mockReset();
  docMock.mockReturnValue({});
  collectionMock.mockReturnValue({});
  addDocMock.mockResolvedValue({ id: 'new-anchor-id' });
});

describe('createAnchor encodes each variant correctly', () => {
  it('round-trips a CalendarDeadline anchor', async () => {
    const anchor: ExternalAnchor = { type: 'calendar_deadline', epochMs: 1_700_000_000_000 };
    const created = await createAnchor('uid-1', 'project-abc', {
      label: 'Demo',
      anchor,
    });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.projectCloudId).toBe('project-abc');
    expect(payload.label).toBe('Demo');
    expect(payload.phaseCloudId).toBeNull();
    expect(JSON.parse(payload.anchorJson as string)).toEqual(anchor);
    expect(created.anchor).toEqual(anchor);
  });

  it('round-trips a NumericThreshold anchor with op symbol', async () => {
    const anchor: ExternalAnchor = {
      type: 'numeric_threshold',
      metric: 'credits',
      op: '<=',
      value: 12,
    };
    const created = await createAnchor('uid-1', 'p1', { label: 'Credits', anchor });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(JSON.parse(payload.anchorJson as string)).toEqual(anchor);
    expect(created.anchor).toEqual(anchor);
  });

  it('round-trips a BooleanGate anchor', async () => {
    const anchor: ExternalAnchor = {
      type: 'boolean_gate',
      gateKey: 'phase-f-kickoff',
      expectedState: true,
    };
    const created = await createAnchor('uid-1', 'p1', { label: 'Gate', anchor });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(JSON.parse(payload.anchorJson as string)).toEqual(anchor);
    expect(created.anchor).toEqual(anchor);
  });

  it('writes phaseCloudId when the caller scopes the anchor to a phase', async () => {
    await createAnchor('uid-1', 'p1', {
      label: 'Phase deadline',
      phase_id: 'phase-99',
      anchor: { type: 'calendar_deadline', epochMs: 1 },
    });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.phaseCloudId).toBe('phase-99');
  });
});

describe('updateAnchor merge semantics', () => {
  const anchor: ExternalAnchor = {
    type: 'numeric_threshold',
    metric: 'velocity',
    op: '>',
    value: 5,
  };

  beforeEach(() => {
    getDocMock.mockResolvedValue({
      id: 'a1',
      data: () => ({
        projectCloudId: 'project-abc',
        label: 'Original',
        anchorJson: encodeExternalAnchor(anchor),
        createdAt: 1,
        updatedAt: 2,
      }),
    });
  });

  it('only writes the label when only the label was passed', async () => {
    await updateAnchor('uid-1', 'a1', { label: 'Renamed' });
    const payload = setDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.label).toBe('Renamed');
    expect('anchorJson' in payload).toBe(false);
    expect('phaseCloudId' in payload).toBe(false);
  });

  it('re-encodes the anchor when the caller swaps the variant', async () => {
    const newAnchor: ExternalAnchor = { type: 'calendar_deadline', epochMs: 9 };
    await updateAnchor('uid-1', 'a1', { anchor: newAnchor });
    const payload = setDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(JSON.parse(payload.anchorJson as string)).toEqual(newAnchor);
  });
});

describe('decodeExternalAnchor malformed-row safety', () => {
  it('returns null for invalid JSON', () => {
    expect(decodeExternalAnchor('{ not valid json')).toBeNull();
  });

  it('returns null for unknown discriminator', () => {
    expect(decodeExternalAnchor(JSON.stringify({ type: 'wat', x: 1 }))).toBeNull();
  });

  it('returns null when a required field is missing', () => {
    expect(
      decodeExternalAnchor(JSON.stringify({ type: 'calendar_deadline' })),
    ).toBeNull();
    expect(
      decodeExternalAnchor(
        JSON.stringify({ type: 'numeric_threshold', metric: 'x' }),
      ),
    ).toBeNull();
  });

  it('returns null for null / empty input', () => {
    expect(decodeExternalAnchor(null)).toBeNull();
    expect(decodeExternalAnchor(undefined)).toBeNull();
    expect(decodeExternalAnchor('')).toBeNull();
  });
});
