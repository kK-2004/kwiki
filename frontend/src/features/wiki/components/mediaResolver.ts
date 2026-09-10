/**
 * attachment:// → short-lived authorized URL resolution for KMediaViewer.
 * The component stays application-independent; this adapter owns kwiki's
 * access model: stable attachment tokens in stored content, signed preview
 * URLs only in memory. Fetches are shared per resolver instance so multiple
 * viewers of the same attachment hit the endpoint once; the viewer's own
 * token guard already ignores results for switched/unmounted sources.
 */
import { api } from '../api';
import { attachmentUuidOf, isAttachmentRef } from './mediaBlocks';
import type { MediaSourceResolver, ResolvedMediaSource } from '@kk-2004/ui-components/components/KMediaViewer';

export function createMediaPreviewResolver(kbId: number | undefined): MediaSourceResolver {
  const cache = new Map<string, Promise<string | null>>();
  const resolveUuid = (uuid: string) => {
    let entry = cache.get(uuid);
    if (!entry) {
      entry = kbId == null
        ? Promise.resolve(null)
        : api.json<{ url: string }>(`/knowledge-bases/${kbId}/attachments/${encodeURIComponent(uuid)}/media-preview-url`)
            .then(({ url }) => url)
            .catch(() => null);
      cache.set(uuid, entry);
    }
    return entry;
  };
  return (source: string): Promise<ResolvedMediaSource | null> | ResolvedMediaSource | null => {
    if (!isAttachmentRef(source)) return { url: source };
    const uuid = attachmentUuidOf(source);
    if (!uuid || kbId == null) return null;
    return resolveUuid(uuid).then(url => (url ? { url } : null));
  };
}

/** Resolves the download endpoint used by ATTACHMENT cards (not media viewers). */
export async function resolveAttachmentDownloadUrl(kbId: number | undefined, uuid: string): Promise<string | null> {
  if (kbId == null) return null;
  try {
    const { url } = await api.json<{ url: string }>(
      `/knowledge-bases/${kbId}/attachments/${encodeURIComponent(uuid)}/download-url`);
    return url;
  } catch {
    return null;
  }
}
