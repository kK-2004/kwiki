import { api } from './api';

export interface UploadedAttachment {
  uuid: string;
  fileName: string;
  contentType: string;
  byteSize: number;
  status: string;
}

interface UploadTicket {
  state: 'UPLOAD' | 'REUSED' | 'PENDING';
  attachmentUuid: string;
  putUrl: string;
  headers: Record<string, string>;
  expiresIn: number;
}

const HASH_VERSION = 'sha256-prefix-1m-v1';
const PREFIX_BYTES = 1024 * 1024;

async function sha256Hex(bytes: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
}

async function bytesOf(blob: Blob): Promise<ArrayBuffer> {
  // Response.arrayBuffer also works in older WebViews where jsdom/Blob lacks Blob.arrayBuffer.
  return new Response(blob).arrayBuffer();
}

async function prefixFingerprint(file: File): Promise<string> {
  return sha256Hex(await bytesOf(file.slice(0, Math.min(PREFIX_BYTES, file.size))));
}

async function fullFingerprint(file: File, prefixSha256: string): Promise<string> {
  return file.size <= PREFIX_BYTES ? prefixSha256 : sha256Hex(await bytesOf(file));
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
}

/** In-memory retry state: a failed completion retries metadata only, never re-PUTs a finished file. */
export interface UploadAttempt {
  file: File;
  kbId: number;
  purpose: 'GENERAL' | 'WIKI_IMPORT_SOURCE';
  attachmentUuid?: string;
  uploaded?: UploadedAttachment;
}

export async function uploadDirect(attempt: UploadAttempt, signal?: AbortSignal,
                                   onStage?: (stage: string) => void): Promise<UploadedAttachment> {
  throwIfAborted(signal);
  if (attempt.uploaded) return attempt.uploaded;
  const path = `/knowledge-bases/${attempt.kbId}/attachments`;
  if (!attempt.attachmentUuid) {
    onStage?.('计算文件指纹…');
    const contentType = attempt.file.type || 'application/octet-stream';
    const prefixSha256 = await prefixFingerprint(attempt.file);
    throwIfAborted(signal);
    const lookup = await api.post<{ candidates: number }>(`${path}/dedup/prefix`, {
      fileName: attempt.file.name, contentType, byteSize: attempt.file.size,
      purpose: attempt.purpose, hashVersion: HASH_VERSION, prefixSha256,
    }, signal);
    onStage?.(lookup.candidates > 0 ? '核对重复文件…' : '计算完整指纹…');
    const fullSha256 = await fullFingerprint(attempt.file, prefixSha256);
    throwIfAborted(signal);
    onStage?.('准备上传…');
    const ticket = await api.post<UploadTicket>(`${path}/uploads`, {
      fileName: attempt.file.name,
      contentType,
      byteSize: attempt.file.size,
      purpose: attempt.purpose,
      hashVersion: HASH_VERSION,
      prefixSha256,
      fullSha256,
    }, signal);
    if (ticket.state === 'PENDING') throw new Error('相同文件正在上传，请稍后重试');
    if (ticket.state === 'REUSED') {
      attempt.attachmentUuid = ticket.attachmentUuid;
      onStage?.('已复用相同文件…');
      attempt.uploaded = await api.post<UploadedAttachment>(`${path}/${ticket.attachmentUuid}/complete`, undefined, signal);
      return attempt.uploaded;
    }
    onStage?.('正在上传文件…');
    // Do not use the authenticated API client: app tokens/cookies must never reach storage.
    const response = await fetch(ticket.putUrl, {
      method: 'PUT', body: attempt.file, headers: ticket.headers, signal,
      credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer',
    });
    if (!response.ok) throw new Error('文件直传失败，请检查网络后重试');
    attempt.attachmentUuid = ticket.attachmentUuid;
  }
  onStage?.('确认上传结果…');
  attempt.uploaded = await api.post<UploadedAttachment>(`${path}/${attempt.attachmentUuid}/complete`, undefined, signal);
  return attempt.uploaded;
}
