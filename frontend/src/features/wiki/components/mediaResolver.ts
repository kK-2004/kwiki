/**
 * attachment:// → 为 KMediaViewer 解析短期有效的授权 URL。
 * 该组件保持与应用无关；这个适配器（adapter）负责 kwiki 的
 * 访问模型：持久化内容中使用稳定的附件 token，已签名的预览
 * URL 仅存于内存中。请求按解析器实例共享，因此同一个
 * 附件的多个查看器只需请求一次接口；查看器自身的
 * token 守卫已忽略切换 / 卸载（unmount）源的查询结果。
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

/** 解析 ATTACHMENT（附件）卡片使用的下载端点（不是媒体查看器）。 */
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
