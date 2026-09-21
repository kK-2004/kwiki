<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { NModal } from 'naive-ui';
import { api, type PageDto } from '../api';
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
const label = computed(() => `${wikiName.value}：${props.source.excerpt.replace(/\s+/g, ' ').slice(0, 16)}${props.source.excerpt.length > 16 ? '…' : ''}`);
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
  <button type="button" class="source" :title="label" @click="open = true">{{ label }}</button>
  <NModal v-model:show="open" preset="card" :title="wikiName" style="width:min(680px,92vw)" :bordered="false" role="dialog" aria-label="命中片段预览">
    <div class="chunk-content" tabindex="0">{{ source.excerpt }}</div>
    <template #footer><button type="button" class="wiki-jump" @click="openWiki">跳转至 Wiki 并定位片段 ↗</button></template>
  </NModal>
</template>
<style scoped>
.source{max-width:320px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;background:#eaf8f1;color:#0e9858;border:0;border-radius:4px;padding:1px 6px;font:inherit;cursor:pointer}.chunk-content{height:320px;max-height:55vh;min-height:0;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}.wiki-jump{padding:0;border:0;background:none;color:#0e9858;font:inherit;cursor:pointer}
</style>
