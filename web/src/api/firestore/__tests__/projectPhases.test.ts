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

import { createPhase, updatePhase } from '@/api/firestore/projectPhases';

beforeEach(() => {
  addDocMock.mockReset();
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  getDocMock.mockReset();
  docMock.mockReset();
  collectionMock.mockReset();
  docMock.mockReturnValue({});
  collectionMock.mockReturnValue({});
  addDocMock.mockResolvedValue({ id: 'new-phase-id' });
});

describe('createPhase payload shape', () => {
  it('writes projectCloudId discriminator + title verbatim', async () => {
    await createPhase('uid-1', 'project-abc', { title: 'Phase F' });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.projectCloudId).toBe('project-abc');
    expect(payload.title).toBe('Phase F');
  });

  it('rounds optional fields through to Android camelCase keys', async () => {
    await createPhase('uid-1', 'p1', {
      title: 'Phase X',
      description: 'desc',
      color_key: 'tertiary',
      start_date: 1_700_000_000_000,
      end_date: 1_710_000_000_000,
      version_anchor: 'v1.9.0',
      version_note: 'release notes',
      order_index: 3,
    });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.colorKey).toBe('tertiary');
    expect(payload.startDate).toBe(1_700_000_000_000);
    expect(payload.endDate).toBe(1_710_000_000_000);
    expect(payload.versionAnchor).toBe('v1.9.0');
    expect(payload.versionNote).toBe('release notes');
    expect(payload.orderIndex).toBe(3);
  });

  it('returns a ProjectPhase with the new doc id and snake_case fields', async () => {
    const phase = await createPhase('uid-1', 'project-abc', {
      title: 'Phase F',
      version_anchor: 'v1.9.0',
    });
    expect(phase.id).toBe('new-phase-id');
    expect(phase.project_id).toBe('project-abc');
    expect(phase.version_anchor).toBe('v1.9.0');
  });
});

describe('updatePhase payload shape (merge semantics)', () => {
  beforeEach(() => {
    getDocMock.mockResolvedValue({
      id: 'p1',
      data: () => ({
        title: 'Phase F',
        projectCloudId: 'project-abc',
        createdAt: 1,
        updatedAt: 2,
      }),
    });
  });

  it('only writes fields the caller passed (omit-on-undefined merge)', async () => {
    await updatePhase('uid-1', 'p1', { title: 'Renamed' });
    const payload = setDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.title).toBe('Renamed');
    expect('description' in payload).toBe(false);
    expect('versionAnchor' in payload).toBe(false);
    // `updatedAt` is stamped via `serverTimestamp()` by safeMergeDoc —
    // the field is present on the payload, but the value is a
    // Firestore sentinel, not a number, until commit time.
    expect('updatedAt' in payload).toBe(true);
  });

  it('writes null when the caller explicitly clears a field', async () => {
    await updatePhase('uid-1', 'p1', { description: null });
    const payload = setDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.description).toBeNull();
  });
});
