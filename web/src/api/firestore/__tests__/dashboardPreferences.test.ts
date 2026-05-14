import { describe, it, expect, vi, beforeEach } from 'vitest';

const { setDocMock, getDocMock, onSnapshotMock, docMock } = vi.hoisted(() => ({
  setDocMock: vi.fn(),
  getDocMock: vi.fn(),
  onSnapshotMock: vi.fn(),
  docMock: vi.fn(),
}));

vi.mock('@/lib/firebase', () => ({ firestore: {} }));
vi.mock('firebase/firestore', () => ({
  doc: docMock,
  getDoc: getDocMock,
  onSnapshot: onSnapshotMock,
  setDoc: setDocMock,
}));

import {
  DEFAULT_COLLAPSED_SECTIONS,
  DEFAULT_HIDDEN_SECTIONS,
  DEFAULT_PROGRESS_STYLE,
  DEFAULT_SECTION_ORDER,
  getDashboardPreferences,
  setCollapsedSections,
  setHiddenSections,
  setProgressStyle,
  setSectionOrder,
  subscribeToDashboardPreferences,
} from '@/api/firestore/dashboardPreferences';

beforeEach(() => {
  setDocMock.mockReset();
  setDocMock.mockResolvedValue(undefined);
  getDocMock.mockReset();
  onSnapshotMock.mockReset();
  docMock.mockReset();
  docMock.mockReturnValue({ path: 'users/uid-1/prefs/dashboard_prefs' });
});

describe('dashboardPreferences Firestore mirror (parity A.5b)', () => {
  it('targets users/{uid}/prefs/dashboard_prefs (generic PreferenceSync path)', async () => {
    await setSectionOrder('uid-1', ['progress', 'today_tasks']);
    expect(docMock).toHaveBeenCalledWith(
      {},
      'users',
      'uid-1',
      'prefs',
      'dashboard_prefs',
    );
  });

  it('setSectionOrder writes section_order as CSV (Android string key, order-significant)', async () => {
    await setSectionOrder('uid-1', ['progress', 'overdue', 'completed']);
    const [, payload, opts] = setDocMock.mock.calls[0];
    expect(payload).toMatchObject({
      section_order: 'progress,overdue,completed',
      __pref_types: { section_order: 'string' },
      __pref_updated_at: expect.any(Number),
    });
    expect(opts).toEqual({ merge: true });
  });

  it('setHiddenSections writes a deduplicated array (Android stringSet key)', async () => {
    await setHiddenSections('uid-1', ['planned', 'planned', 'completed']);
    const [, payload] = setDocMock.mock.calls[0];
    expect(payload).toMatchObject({
      hidden_sections: expect.any(Array),
      __pref_types: { hidden_sections: 'stringSet' },
    });
    expect((payload.hidden_sections as string[]).sort()).toEqual([
      'completed',
      'planned',
    ]);
  });

  it('setProgressStyle writes a typed string', async () => {
    await setProgressStyle('uid-1', 'bar');
    const [, payload] = setDocMock.mock.calls[0];
    expect(payload).toMatchObject({
      progress_style: 'bar',
      __pref_types: { progress_style: 'string' },
    });
  });

  it('setCollapsedSections writes a deduplicated array', async () => {
    await setCollapsedSections('uid-1', ['planned']);
    const [, payload] = setDocMock.mock.calls[0];
    expect(payload).toMatchObject({
      collapsed_sections: ['planned'],
      __pref_types: { collapsed_sections: 'stringSet' },
    });
  });

  it('getDashboardPreferences returns Android defaults when doc missing', async () => {
    getDocMock.mockResolvedValue({ exists: () => false });
    const got = await getDashboardPreferences('uid-1');
    expect(got.sectionOrder).toEqual(DEFAULT_SECTION_ORDER);
    expect(got.hiddenSections).toEqual(DEFAULT_HIDDEN_SECTIONS);
    expect(got.progressStyle).toBe(DEFAULT_PROGRESS_STYLE);
    expect(got.collapsedSections).toEqual(DEFAULT_COLLAPSED_SECTIONS);
  });

  it('getDashboardPreferences parses Android-written values', async () => {
    getDocMock.mockResolvedValue({
      exists: () => true,
      data: () => ({
        section_order: 'today_tasks,progress',
        hidden_sections: ['plan_more'],
        progress_style: 'bar',
        collapsed_sections: ['planned'],
      }),
    });
    const got = await getDashboardPreferences('uid-1');
    expect(got.sectionOrder).toEqual(['today_tasks', 'progress']);
    expect(got.hiddenSections).toEqual(['plan_more']);
    expect(got.progressStyle).toBe('bar');
    expect(got.collapsedSections).toEqual(['planned']);
  });

  it('subscribeToDashboardPreferences decodes the snapshot into the typed shape', () => {
    const cb = vi.fn();
    onSnapshotMock.mockImplementation((_ref, listener) => {
      listener({
        exists: () => true,
        data: () => ({
          section_order: 'progress',
          collapsed_sections: ['completed'],
        }),
      });
      return () => {};
    });
    subscribeToDashboardPreferences('uid-1', cb);
    expect(cb).toHaveBeenCalledTimes(1);
    const arg = cb.mock.calls[0][0];
    expect(arg.sectionOrder).toEqual(['progress']);
    expect(arg.collapsedSections).toEqual(['completed']);
    // The defaulting still kicks in for the fields not present on the doc
    expect(arg.progressStyle).toBe(DEFAULT_PROGRESS_STYLE);
  });
});
