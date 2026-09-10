<template>
  <a
    v-if="media.kind === 'ATTACHMENT'"
    class="attachment-card"
    :class="{ unavailable: !downloadUrl }"
    :href="downloadUrl || '#'"
    target="_blank"
    rel="noopener"
    @click.stop
  >
    <i class="i-lucide-paperclip" aria-hidden="true"></i>
    <span><strong>{{ media.fileName || '附件' }}</strong><small>{{ formatBytes(media.byteSize || 0) }}</small></span>
    <i class="i-lucide-download" aria-hidden="true"></i>
  </a>
  <KMediaViewer
    v-else
    :kind="viewerKind"
    :source="media.src"
    :alt="media.alt || ''"
    :title="media.alt || media.fileName || ''"
    :align="media.align || 'center'"
    :width="widthSpec"
    :resolve-source="effectiveResolver"
    :download-handler="mediaDownloadHandler"
    allow-playback-rate
    @ready="emit('ready', $event)"
  >
    <slot />
  </KMediaViewer>
</template>

<script setup lang="ts">
/**
 * 连接 kwiki 与共享 KMediaViewer 的桥接：将扫描器（scanner）的 MediaAttrs 映射为
 * 查看器（viewer）的属性，并将 ATTACHMENT 链接卡片保留在本地。三种
 * 展示场景（源卡片、预览、阅读器）都通过此
 * 媒体块渲染媒体，从而保持行为一致；编辑器工具经由默认插槽（slot）
 * 传递，即使在错误状态下也保持可用。
 */
import { computed, defineAsyncComponent, onMounted, ref } from 'vue';
import { attachmentUuidOf } from './mediaBlocks';
import { resolveAttachmentDownloadUrl } from './mediaResolver';
import type { MediaAttrs } from './mediaBlocks';
import type { KMediaViewerProps, MediaSourceResolver } from '@kk-2004/ui-components/components/KMediaViewer';

// 隔离的子路径导入：查看器（viewer）代码块（以及其中的 Plyr，用于
// 音视频）仅在该媒体块真正渲染时才被加载。
const KMediaViewer = defineAsyncComponent(() =>
  Promise.all([
    import('@kk-2004/ui-components/components/KMediaViewer'),
    import('@kk-2004/ui-components/components/KMediaViewer/style.css'),
  ]).then(([module]) => module.KMediaViewer));

const props = defineProps<{
  media: MediaAttrs;
  resolver?: MediaSourceResolver;
  kbId?: number;
}>();
const emit = defineEmits<{ ready: [detail: { url: string; width?: number; height?: number }] }>();

const viewerKind = computed(() => props.media.kind.toLowerCase() as 'image' | 'audio' | 'video');

const widthSpec = computed(() => {
  if (props.media.widthPercent) return `${props.media.widthPercent}%` as `${number}%`;
  if (props.media.widthPx) return props.media.widthPx;
  return 'auto' as const;
});

const identityResolver: MediaSourceResolver = source =>
  isStableHttp(source) ? { url: source } : null;
const effectiveResolver = computed<MediaSourceResolver>(() => props.resolver ?? identityResolver);
const mediaDownloadHandler = computed<KMediaViewerProps['downloadHandler']>(() => {
  const uuid = attachmentUuidOf(props.media.src);
  if (!uuid) return undefined;
  return async () => {
    const url = await resolveAttachmentDownloadUrl(props.kbId, uuid);
    if (!url) throw new Error('无法获取附件下载地址');
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.rel = 'noopener';
    anchor.click();
  };
});

const downloadUrl = ref('');
onMounted(async () => {
  if (props.media.kind !== 'ATTACHMENT') return;
  const uuid = attachmentUuidOf(props.media.src);
  if (!uuid) {
    downloadUrl.value = props.media.src;
    return;
  }
  downloadUrl.value = (await resolveAttachmentDownloadUrl(props.kbId, uuid)) ?? '';
});

function isStableHttp(source: string): source is `https://${string}` | `http://${string}` {
  return /^https?:\/\//i.test(source);
}

function formatBytes(bytes: number): string {
  if (!bytes) return '大小未知';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
</script>

<style scoped>
.attachment-card { width: min(520px, 100%); display: flex; align-items: center; gap: 12px; padding: 13px 15px; border: 1px solid #dce7df; border-radius: 10px; color: #3f5e49; text-decoration: none; background: #f8fbf9; box-sizing: border-box; }
.attachment-card > i:first-child { font-size: 20px; }
.attachment-card > i:last-child { margin-left: auto; }
.attachment-card span { display: grid; gap: 3px; min-width: 0; }
.attachment-card strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attachment-card small { color: #8b9990; }
.attachment-card.unavailable { opacity: 0.75; }
</style>
