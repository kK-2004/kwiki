import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient, clearAuthToken, getAuthToken, setAuthToken } from '../src/features/wiki/api';

describe('API envelope and authentication headers', () => {
  beforeEach(() => {
    clearAuthToken();
    vi.restoreAllMocks();
  });

  it('unwraps TransDTO data and accepts a renewed token', async () => {
    setAuthToken('old');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: 200, success: true, data: [{ id: 1 }] }),
      { status: 200, headers: { 'Content-Type': 'application/json', 'X-Auth-Token': 'new' } },
    )));
    const data = await new ApiClient().json<Array<{ id: number }>>('/items');
    expect(data).toEqual([{ id: 1 }]);
    expect(getAuthToken()).toBe('new');
  });

  it('maps business errors and leaves multipart content type to the browser', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 422, success: false, message: 'invalid_file' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 200, success: true, data: { id: 2 } }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(new ApiClient().json('/bad')).rejects.toMatchObject({ code: 'invalid_file' });
    const form = new FormData(); form.append('file', new Blob(['x']), 'x.md');
    await new ApiClient().upload('/files', form);
    const headers = fetchMock.mock.calls[1][1].headers as Headers;
    expect(headers.get('Content-Type')).toBeNull();
  });
});

describe('upload error responses', () => {
  it('preserves a useful error when the server rejects a large file', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code:413, success:false, message:'文件过大，请选择不超过 20 MB 的文件' }), { status:413 })));
    await expect(new ApiClient().upload('/files', new FormData())).rejects.toMatchObject({ status:413, message:'文件过大，请选择不超过 20 MB 的文件' });
  });
});
