import { describe, it, expect, vi, beforeEach } from 'vitest';

const { postMock } = vi.hoisted(() => ({ postMock: vi.fn() }));
vi.mock('@/api/client', () => ({ default: { post: postMock } }));

import { aiApi } from '@/api/ai';

describe('aiApi.extractFromText', () => {
  beforeEach(() => postMock.mockReset());

  it('POSTs the request body to /ai/parse-text and unwraps .data', async () => {
    postMock.mockResolvedValueOnce({
      data: {
        tasks: [
          {
            title: 'Review PR',
            suggested_priority: 3,
            suggested_due_date: '2026-04-30',
            suggested_project: 'Work',
            confidence: 0.85,
          },
        ],
      },
    });
    const res = await aiApi.extractFromText({
      text: 'Don’t forget to review the PR before Friday.',
      source: 'web',
    });
    expect(postMock).toHaveBeenCalledWith('/ai/parse-text', {
      text: 'Don’t forget to review the PR before Friday.',
      source: 'web',
    });
    expect(res.tasks).toHaveLength(1);
    expect(res.tasks[0].title).toBe('Review PR');
  });

  it('accepts requests without a source field', async () => {
    postMock.mockResolvedValueOnce({ data: { tasks: [] } });
    await aiApi.extractFromText({ text: 'hi' });
    expect(postMock).toHaveBeenCalledWith('/ai/parse-text', {
      text: 'hi',
    });
  });
});
