import { describe, expect, it, vi } from 'vitest';
import { ApiClient, clearAuthToken, setAuthToken } from '../src/features/wiki/api';
import { router } from '../src/router';

describe('offline workspace fixtures', () => {
  it('handles an empty collection and transport failure without mock business data', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 200, success: true, data: [] }), { status: 200 }))
      .mockResolvedValueOnce(new Response('', { status: 503 }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(new ApiClient().json('/knowledge-bases')).resolves.toEqual([]);
    await expect(new ApiClient().json('/knowledge-bases')).rejects.toMatchObject({ code: 'http_503' });
  });

  it('resolves legacy numeric Wiki URLs through the current route', async () => {
    setAuthToken('fixture-token');
    await router.push('/12/34');
    expect(router.currentRoute.value.fullPath).toBe('/knowledge-bases/12/34');
    clearAuthToken();
  });
});
