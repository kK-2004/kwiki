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
  color: #a0a6a6;
  font-size: 12px;
  line-height: 1.6;
}
.summary-card {
  width: 100%;
  padding: 12px;
  margin-bottom: 7px;
  border: 1px solid var(--kwiki-line);
  border-radius: 8px;
  background: var(--kwiki-panel);
  text-align: left;
  cursor: pointer;
  font: inherit;
  color: var(--kwiki-ink);
}
.summary-card:hover {
  border-color: #b9e7d0;
  background: #fbfefc;
}
.summary-card strong {
  display: block;
  margin-bottom: 5px;
}
.summary-card span {
  color: var(--kwiki-muted);
  font-size: 12px;
  line-height: 1.5;
}
.empty {
  padding: 34px 18px;
  text-align: center;
  color: var(--kwiki-muted);
}
</style>
