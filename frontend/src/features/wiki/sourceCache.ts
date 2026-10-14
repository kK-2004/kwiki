/**
 * 源文件预览的浏览器缓存：附件内容按 attachmentUuid 不可变，命中后切回页面
 * 无需重新下载。两级缓存——内存 LRU（同一会话内切页即时可用）+ Cache Storage
 * （刷新后仍可用）。缓存只省下载，不省授权：调用方仍须先通过 source-preview
 * 授权接口，撤权后不会再读到缓存内容。退出登录时整体清空。
 */
const CACHE_NAME = 'kwiki-source-preview-v1';
const MEMORY_MAX_BYTES = 120 * 1024 * 1024;
const PERSISTENT_MAX_ENTRIES = 30;

const memory = new Map<string, Blob>();
let memoryBytes = 0;

function cacheKey(uuid: string) {
  return `/__kwiki_source_cache__/${encodeURIComponent(uuid)}`;
}

function storage(): CacheStorage | null {
  return typeof caches === 'undefined' ? null : caches;
}

function remember(uuid: string, blob: Blob) {
  const previous = memory.get(uuid);
  if (previous) {
    memory.delete(uuid);
    memoryBytes -= previous.size;
  }
  if (blob.size > MEMORY_MAX_BYTES) return;
  memory.set(uuid, blob);
  memoryBytes += blob.size;
  // Map 按插入顺序迭代：最早的即最久未用。
  for (const [key, value] of memory) {
    if (memoryBytes <= MEMORY_MAX_BYTES) break;
    memory.delete(key);
    memoryBytes -= value.size;
  }
}

export async function getCachedSource(uuid: string | null | undefined): Promise<Blob | null> {
  if (!uuid) return null;
  const hit = memory.get(uuid);
  if (hit) {
    remember(uuid, hit);
    return hit;
  }
  try {
    const cache = await storage()?.open(CACHE_NAME);
    const response = await cache?.match(cacheKey(uuid));
    if (!response) return null;
    const blob = await response.blob();
    remember(uuid, blob);
    return blob;
  } catch {
    return null;
  }
}

export async function putCachedSource(uuid: string | null | undefined, blob: Blob): Promise<void> {
  if (!uuid || !blob.size) return;
  remember(uuid, blob);
  try {
    const cache = await storage()?.open(CACHE_NAME);
    if (!cache) return;
    await cache.put(cacheKey(uuid), new Response(blob, { headers: { 'Content-Type': blob.type || 'application/octet-stream' } }));
    const keys = await cache.keys();
    // Cache Storage 的 keys() 按写入顺序返回，超出上限时淘汰最早写入的条目。
    for (const request of keys.slice(0, Math.max(0, keys.length - PERSISTENT_MAX_ENTRIES))) {
      await cache.delete(request);
    }
  } catch {
    // 配额不足或隐私模式下不可用时只保留内存缓存。
  }
}

export async function clearSourceCache(): Promise<void> {
  memory.clear();
  memoryBytes = 0;
  try {
    await storage()?.delete(CACHE_NAME);
  } catch {
    // 忽略：缓存不可用时无需清理。
  }
}
