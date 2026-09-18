<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NModal } from 'naive-ui';
import { api, type PageDto } from '../api';
import type { RetrievalSource } from '../sse';
const props = defineProps<{ source: RetrievalSource }>();
const open = ref(false);
const title = ref('');
watch(() => [props.source.kbId, props.source.resourceId], async () => {
  title.value = '';
  const key = `${props.source.kbId}/${props.source.resourceId}`;
  try {
    const page = await api.json<PageDto>(`/knowledge-bases/${props.source.kbId}/pages/${props.source.resourceId}`);
    if (key === `${props.source.kbId}/${props.source.resourceId}`) title.value = page.title || '';
  } catch { /* 当前权限或页面状态可能已变化，预览仍显示本次命中的文本。 */ }
}, { immediate: true });
const wikiName = computed(() => title.value || props.source.headingPath?.split(' / ')[0] || `Wiki ${props.source.resourceId}`);
const label = computed(() => `${wikiName.value}：${props.source.excerpt.replace(/\s+/g, ' ').slice(0, 16)}${props.source.excerpt.length > 16 ? '…' : ''}`);
const href = computed(() => `#/knowledge-bases/${props.source.kbId}/${props.source.resourceId}?chunk=${encodeURIComponent(props.source.childChunkKey)}`);
</script>
<template>
  <button type="button" class="source" :title="label" @click="open = true">{{ label }}</button>
  <NModal v-model:show="open" preset="card" :title="wikiName" style="width:min(680px,92vw)" :bordered="false" role="dialog" aria-label="命中片段预览">
    <div class="chunk-content" tabindex="0">{{ source.excerpt }}</div>
    <template #footer><a :href="href" @click="open = false">跳转至 Wiki 并定位片段 ↗</a></template>
  </NModal>
</template>
<style scoped>
.source{max-width:320px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;background:#eaf8f1;color:#0e9858;border:0;border-radius:4px;padding:1px 6px;font:inherit;cursor:pointer}.chunk-content{height:320px;max-height:55vh;min-height:0;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}a{color:#0e9858}
</style>
