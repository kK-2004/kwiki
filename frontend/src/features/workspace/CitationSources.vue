<script setup lang="ts">
/**
 * 回答底部的参考来源：按文档分组，同一文档只显示一次标题，其下用编号标签列出各处引用。
 * 点击文档标题只打开文档；点击编号才定位并高亮对应片段。
 * 编号标签带 data-reference-index，供正文 [n] 引用标记定位并高亮。
 */
import { computed } from 'vue';
import type { CitationEntry } from '../wiki/sse';

const props = defineProps<{ citations: CitationEntry[] }>();
const emit = defineEmits<{ open: [citation: CitationEntry]; openDocument: [citation: CitationEntry] }>();

interface SourceGroup {
  key: string;
  title: string;
  entries: { citation: CitationEntry; index: number }[];
}

const groups = computed<SourceGroup[]>(() => {
  const map = new Map<string, SourceGroup>();
  props.citations.forEach((citation, index) => {
    const key = `${citation.kbId ?? ''}/${citation.resourceType}/${citation.resourceId}`;
    let group = map.get(key);
    if (!group) {
      group = { key, title: citation.displayName || citation.headingPath.split(' / ')[0] || '未命名来源', entries: [] };
      map.set(key, group);
    }
    group.entries.push({ citation, index });
  });
  return [...map.values()];
});

function chipTitle(citation: CitationEntry): string {
  return citation.headingPath || citation.displayName || '';
}
</script>

<template>
  <section class="citation-list" aria-label="参考来源">
    <header class="head">
      <span class="i-lucide-library-big" aria-hidden="true"></span>
      <span>参考来源</span>
      <span class="count">{{ citations.length }} 处引用 · {{ groups.length }} 篇文档</span>
    </header>
    <ul class="groups">
      <li v-for="group in groups" :key="group.key" class="group">
        <button type="button" class="doc" :title="group.title" @click="emit('openDocument', group.entries[0].citation)">
          <span class="i-lucide-file-text doc-icon" aria-hidden="true"></span>
          <span class="doc-title">{{ group.title }}</span>
        </button>
        <span class="chips">
          <button
            v-for="entry in group.entries"
            :key="entry.citation.childChunkKey"
            type="button"
            class="chip"
            :data-reference-index="entry.index"
            :title="chipTitle(entry.citation)"
            :aria-label="`引用 ${entry.index + 1}：${chipTitle(entry.citation)}`"
            @click="emit('open', entry.citation)"
          >{{ entry.index + 1 }}</button>
        </span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.citation-list {
  margin-top: 16px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-surface);
  font-size: 12px;
  overflow: hidden;
}
.head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  color: var(--k-ink-2);
  font-weight: 500;
  border-bottom: 1px solid var(--k-line);
}
.head .count {
  margin-left: auto;
  color: var(--k-muted);
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}
.groups {
  list-style: none;
  margin: 0;
  padding: 4px 0;
}
.group {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 12px;
}
.group:hover { background: var(--k-surface-hover); }
.doc {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: 50%;
  flex: none;
  border: 0;
  background: none;
  padding: 0;
  color: var(--k-ink-2);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.doc:hover .doc-title { color: var(--k-green-deep); text-decoration: underline; text-underline-offset: 3px; }
.doc-icon { flex: none; color: var(--k-muted); }
.doc-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  min-width: 0;
}
.chip {
  min-width: 20px;
  height: 18px;
  padding: 0 5px;
  border: 0;
  border-radius: var(--k-r-sm);
  background: var(--k-green-soft);
  color: var(--k-green-deep);
  font: inherit;
  font-size: 11px;
  line-height: 18px;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}
.chip:hover { background: color-mix(in srgb, var(--k-green) 24%, transparent); }
.doc:focus-visible, .chip:focus-visible { outline: 2px solid var(--k-green); outline-offset: 1px; }
.chip.citation-flash { animation: citation-flash 2.2s ease-out; }
@keyframes citation-flash {
  0%, 35% { background: var(--k-warn-soft); box-shadow: 0 0 0 3px var(--k-warn-soft); }
  100% { background: var(--k-green-soft); box-shadow: none; }
}
@media (max-width: 640px) {
  .group { flex-direction: column; align-items: flex-start; gap: 4px; }
  .doc { max-width: 100%; }
}
</style>
