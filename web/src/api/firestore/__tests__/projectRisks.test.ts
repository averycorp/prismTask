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

import { createRisk, updateRisk } from '@/api/firestore/projectRisks';
import { parseRiskLevel } from '@/types/projectRisk';

beforeEach(() => {
  addDocMock.mockReset();
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  getDocMock.mockReset();
  docMock.mockReset();
  collectionMock.mockReset();
  docMock.mockReturnValue({});
  collectionMock.mockReturnValue({});
  addDocMock.mockResolvedValue({ id: 'new-risk-id' });
});

describe('createRisk', () => {
  it('writes projectCloudId + defaults level to MEDIUM', async () => {
    await createRisk('uid-1', 'project-abc', { title: 'Schedule slip' });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.projectCloudId).toBe('project-abc');
    expect(payload.level).toBe('MEDIUM');
    expect(payload.title).toBe('Schedule slip');
  });

  it('honors a caller-provided severity level', async () => {
    await createRisk('uid-1', 'p1', { title: 'Outage', level: 'HIGH' });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.level).toBe('HIGH');
  });

  it('writes mitigation as null when omitted', async () => {
    await createRisk('uid-1', 'p1', { title: 'Risk' });
    const payload = addDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.mitigation).toBeNull();
  });
});

describe('updateRisk merge semantics', () => {
  beforeEach(() => {
    getDocMock.mockResolvedValue({
      id: 'r1',
      data: () => ({
        title: 'Risk A',
        level: 'HIGH',
        projectCloudId: 'project-abc',
      }),
    });
  });

  it('only writes title when only title was passed', async () => {
    await updateRisk('uid-1', 'r1', { title: 'Renamed' });
    const payload = setDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.title).toBe('Renamed');
    expect('level' in payload).toBe(false);
    expect('mitigation' in payload).toBe(false);
  });

  it('writes resolvedAt when the caller resolves the risk', async () => {
    await updateRisk('uid-1', 'r1', { resolved_at: 1_700_000_000_000 });
    const payload = setDocMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.resolvedAt).toBe(1_700_000_000_000);
  });
});

describe('parseRiskLevel', () => {
  it('falls back to MEDIUM for unknown / null storage values', () => {
    expect(parseRiskLevel(null)).toBe('MEDIUM');
    expect(parseRiskLevel(undefined)).toBe('MEDIUM');
    expect(parseRiskLevel('NUCLEAR')).toBe('MEDIUM');
    expect(parseRiskLevel(42)).toBe('MEDIUM');
  });

  it('passes through known enum values', () => {
    expect(parseRiskLevel('LOW')).toBe('LOW');
    expect(parseRiskLevel('MEDIUM')).toBe('MEDIUM');
    expect(parseRiskLevel('HIGH')).toBe('HIGH');
  });
});
