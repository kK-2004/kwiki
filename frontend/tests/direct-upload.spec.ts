import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearAuthToken, setAuthToken } from '../src/features/wiki/api';
import { uploadDirect, type UploadAttempt } from '../src/features/wiki/directUpload';

const ticket = { state: 'UPLOAD', attachmentUuid: 'upload-1', putUrl: 'https://objects.test/signed-put', headers: { 'Content-Type': 'application/pdf' }, expiresIn: 300 };
const uploaded = { uuid: 'upload-1', fileName: 'book.pdf', contentType: 'application/pdf', byteSize: 4, status: 'STORED' };
const json = (data: unknown) => new Response(JSON.stringify({ code: 200, success: true, data }), { status: 200 });
const attempt = (): UploadAttempt => ({ file: new File(['test'], 'book.pdf', { type: 'application/pdf' }), kbId: 1, purpose: 'WIKI_IMPORT_SOURCE' });

describe('browser direct upload', () => {
  beforeEach(() => { clearAuthToken(); setAuthToken('kwiki-secret'); });
  afterEach(() => { vi.unstubAllGlobals(); clearAuthToken(); });

  it('sends bytes only to storage, with no app authorization or cookies', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ candidates: 0 })).mockResolvedValueOnce(json(ticket))
      .mockResolvedValueOnce(new Response(null, { status: 200 })).mockResolvedValueOnce(json(uploaded));
    vi.stubGlobal('fetch', fetchMock);
    const request = attempt();
    expect(await uploadDirect(request)).toEqual(uploaded);
    const [lookup, init, put, complete] = fetchMock.mock.calls;
    expect(lookup[0]).toBe('/api/v1/knowledge-bases/1/attachments/dedup/prefix');
    expect(JSON.parse(lookup[1].body)).toMatchObject({ fileName: 'book.pdf', byteSize: 4, contentType: 'application/pdf', purpose: 'WIKI_IMPORT_SOURCE', hashVersion: 'sha256-prefix-1m-v1' });
    expect(lookup[1].headers.get('Authorization')).toBe('Bearer kwiki-secret');
    expect(init[0]).toBe('/api/v1/knowledge-bases/1/attachments/uploads');
    const initBody = JSON.parse(init[1].body);
    expect(initBody).toMatchObject({ fileName: 'book.pdf', byteSize: 4, contentType: 'application/pdf', purpose: 'WIKI_IMPORT_SOURCE', hashVersion: 'sha256-prefix-1m-v1' });
    expect(initBody.prefixSha256).toMatch(/^[0-9a-f]{64}$/);
    expect(initBody.fullSha256).toBe(initBody.prefixSha256);
    expect(init[1].headers.get('Authorization')).toBe('Bearer kwiki-secret');
    expect(put[0]).toBe(ticket.putUrl);
    expect(put[1].body).toBe(request.file);
    expect(put[1].credentials).toBe('omit');
    expect(put[1].headers).toEqual({ 'Content-Type': 'application/pdf' });
    expect(complete[0]).toBe('/api/v1/knowledge-bases/1/attachments/upload-1/complete');
    expect(complete[1].body).toBeUndefined();
  });

  it('does not confirm a failed PUT or fall back to uploading through kwiki', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ candidates: 0 })).mockResolvedValueOnce(json(ticket)).mockResolvedValueOnce(new Response(null, { status: 403 }));
    vi.stubGlobal('fetch', fetchMock);
    const request = attempt();
    await expect(uploadDirect(request)).rejects.toThrow('文件直传失败');
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(request.attachmentUuid).toBeUndefined();
  });

  it('retries a lost completion response without uploading bytes again', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ candidates: 0 })).mockResolvedValueOnce(json(ticket)).mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(json(uploaded));
    vi.stubGlobal('fetch', fetchMock);
    const request = attempt();
    await expect(uploadDirect(request)).rejects.toThrow('offline');
    expect(await uploadDirect(request)).toEqual(uploaded);
    expect(fetchMock).toHaveBeenCalledTimes(5);
    expect(fetchMock.mock.calls[4][0]).toContain('/upload-1/complete');
    await uploadDirect(request);
    expect(fetchMock).toHaveBeenCalledTimes(5);
  });

  it('aborts without issuing requests', async () => {
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController(); controller.abort();
    await expect(uploadDirect(attempt(), controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
