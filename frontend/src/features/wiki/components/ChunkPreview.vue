<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { NImage, NModal } from 'naive-ui';
import { api, type PageDto } from '../api';
import { parseExcerpt, plainExcerpt } from '../imageBlocks';
import { showMissingWikiToast } from '../toast';
import type { RetrievalSource } from '../sse';
const props = defineProps<{ source: RetrievalSource }>();
const open = ref(false);
const title = ref('');
const pageUnavailable = ref(false);
const router = useRouter();
watch(() => [props.source.kbId, props.source.resourceId], async () => {
  title.value = '';
  pageUnavailable.value = false;
  const key = `${props.source.kbId}/${props.source.resourceId}`;
  try {
    const page = await api.json<PageDto>(`/knowledge-bases/${props.source.kbId}/pages/${props.source.resourceId}`);
    if (key === `${props.source.kbId}/${props.source.resourceId}`) title.value = page.title || '';
  } catch {
    if (key === `${props.source.kbId}/${props.source.resourceId}`) pageUnavailable.value = true;
  }
}, { immediate: true });
const wikiName = computed(() => title.value || props.source.headingPath?.split(' / ')[0] || `Wiki ${props.source.resourceId}`);
// 片段中的图片受保护块：标记不展示给用户，图片按 contentId 经引用接口（含鉴权）取预览链接
const segments = computed(() => parseExcerpt(props.source.excerpt));
const imageIds = computed(() => segments.value.flatMap(segment => (segment.kind === 'image' ? [segment.contentId] : [])));
const previews = ref<Record<number, string>>({});
watch(() => [props.source.childChunkKey, imageIds.value.join(',')] as const, async ([key]) => {
  previews.value = {};
  if (!imageIds.value.length) return;
  try {
    const citation = await api.json<{ resources?: { contentId: number; previewUrl?: string | null }[] }>(`/citations/${encodeURIComponent(key)}`);
    if (key !== props.source.childChunkKey) return;
    const resolved: Record<number, string> = {};
    for (const resource of citation.resources ?? []) {
      if (resource.previewUrl) resolved[resource.contentId] = resource.previewUrl;
    }
    previews.value = resolved;
  } catch {
    // 预览链接不可用时只显示摘要文字，不影响片段本身
  }
}, { immediate: true });
const thumbnail = computed(() => imageIds.value.map(id => previews.value[id]).find(Boolean));
const plain = computed(() => plainExcerpt(segments.value).replace(/\s+/g, ' ').trim());
const label = computed(() => `${wikiName.value}：${plain.value.slice(0, 16)}${plain.value.length > 16 ? '…' : ''}`);
async function openWiki(event: MouseEvent) {
  event.preventDefault();
  if (pageUnavailable.value) {
    showMissingWikiToast();
    return;
  }

  try {
    await api.json(`/knowledge-bases/${props.source.kbId}/pages/${props.source.resourceId}`);
  } catch {
    showMissingWikiToast();
    return;
  }

  open.value = false;
  await router.push({
    name: 'workspace',
    params: { kbId: props.source.kbId, pageId: props.source.resourceId },
    query: { chunk: props.source.childChunkKey },
  });
}
</script>
<template>
  <span class="source">
    <NImage v-if="thumbnail" class="thumb" :src="thumbnail" width="18" height="18" object-fit="cover" alt="片段图片，点击放大" @click.stop />
    <button type="button" class="source-label" :title="label" @click="open = true">{{ label }}</button>
  </span>
  <NModal v-model:show="open" preset="card" :title="wikiName" style="width:min(680px,92vw)" :bordered="false" role="dialog" aria-label="命中片段预览">
    <div class="chunk-content" tabindex="0">
      <template v-for="(segment, index) in segments" :key="index">
        <div v-if="segment.kind === 'text'" class="chunk-text">{{ segment.text }}</div>
        <figure v-else class="chunk-image">
          <NImage v-if="previews[segment.contentId]" :src="previews[segment.contentId]" width="220" object-fit="contain" alt="片段图片，点击放大" />
          <div v-else class="image-missing">[图片]</div>
          <figcaption>{{ segment.summary }}</figcaption>
        </figure>
      </template>
    </div>
    <template #footer><button type="button" class="wiki-jump" @click="openWiki">跳转至 Wiki 并定位片段 ↗</button></template>
  </NModal>
</template>
<style scoped>
.source{display:inline-flex;align-items:center;gap:4px;max-width:320px;background:var(--k-green-soft);color:var(--k-green-deep);border-radius:var(--k-r-sm);padding:1px 6px}.source-label{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;background:none;color:inherit;border:0;padding:0;font:inherit;cursor:pointer}.thumb{flex:none;display:inline-flex;border-radius:3px;overflow:hidden;cursor:zoom-in}.chunk-content{height:320px;max-height:55vh;min-height:0;overflow:auto;overflow-wrap:anywhere;line-height:1.8;color:var(--k-ink-2)}.chunk-text{white-space:pre-wrap}.chunk-image{margin:8px 0}.chunk-image :deep(img){border-radius:var(--k-r-sm);cursor:zoom-in}.chunk-image figcaption{white-space:pre-wrap;color:var(--k-ink-3,var(--k-ink-2));font-size:13px;margin-top:4px}.image-missing{color:var(--k-ink-3,var(--k-ink-2))}.wiki-jump{padding:0;border:0;background:none;color:var(--k-green-deep);font:inherit;cursor:pointer}.wiki-jump:hover{text-decoration:underline;text-underline-offset:3px}
</style>
