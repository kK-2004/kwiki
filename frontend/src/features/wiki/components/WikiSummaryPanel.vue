<template>
  <section data-testid="summary-panel" aria-label="摘要列表" class="summary-list">
    <p class="summary-note">仅基于当前用户有权访问的已发布父块生成。</p>
    <button
      v-for="summary in summaries"
      :key="summary.pageId"
      type="button"
      class="summary-card"
      @click="open(summary.pageId)"
    >
      <strong>{{ summary.title }}</strong>
      <span>来源：{{ summary.source }}</span>
    </button>
    <p v-if="!summaries.length" class="empty" data-testid="summary-empty">暂无摘要</p>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useWikiStore } from '../store';

const summaries = computed(() => useWikiStore().summaries);
function open(pageId: number) {
  const match = window.location.hash.match(/#\/?knowledge-bases\/(\d+)/);
  if (match) window.location.hash = `#/knowledge-bases/${match[1]}/${pageId}`;
}
</script>

<style scoped>
.summary-note {
  margin: 0 3px 10px;
  color: var(--k-faint);
  font-size: 12px;
  line-height: 1.6;
}
.summary-card {
  width: 100%;
  padding: 12px;
  margin-bottom: 7px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-canvas);
  text-align: left;
  cursor: pointer;
  font: inherit;
  color: var(--k-ink);
  box-shadow: var(--k-shadow-sm);
}
.summary-card:hover {
  border-color: var(--k-line-strong);
  background: var(--k-surface-hover);
}
.summary-card strong {
  display: block;
  margin-bottom: 5px;
}
.summary-card span {
  color: var(--k-muted);
  font-size: 12px;
  line-height: 1.5;
}
.empty {
  padding: 34px 18px;
  text-align: center;
  color: var(--k-muted);
}
</style>
