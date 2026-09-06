<template>
  <div class="search">
    <form class="search-box" role="search" @submit.prevent="submit">
      <span class="lens" aria-hidden="true"><i class="i-lucide-search"></i></span>
      <input
        v-model="phrase"
        type="search"
        aria-label="搜索 Wiki 页面"
        placeholder="搜索 Wiki 页面…"
        data-testid="wiki-search-input"
      />
      <button
        type="button"
        class="clear"
        :class="{ show: phrase }"
        aria-label="清空搜索"
        data-testid="wiki-search-clear"
        @click="clear"
      >
        <i class="i-lucide-x" aria-hidden="true"></i>
      </button>
    </form>
    <slot name="toolbar"></slot>
    <p class="count" aria-live="polite" data-testid="wiki-search-count">{{ countText }}</p>
    <div class="tree-area">
      <WikiTree :tree="filtered.tree" />
      <p v-if="phrase && filtered.matchCount === 0" class="empty" data-testid="wiki-search-empty">
        没有找到相关页面<br /><span>试试更短的关键词</span>
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import type { TreeNodeDto } from '../api';
import { filterTree } from '../tree-filter';
import { useWikiStore } from '../store';
import WikiTree from './WikiTree.vue';

const store = useWikiStore();
const phrase = ref('');

const filtered = computed(() => filterTree(store.tree, phrase.value));
const countText = computed(() =>
  phrase.value.trim()
    ? `${filtered.value.matchCount} 个匹配页面`
    : `${filtered.value.matchCount} 个页面`,
);
const emit = defineEmits<{ submit: [] }>();
function submit() {
  emit('submit');
}
function clear() {
  phrase.value = '';
}
</script>

<style scoped>
.search {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 3px;
}
.search-box {
  position: relative;
  margin: 0 0 12px;
}
.search-box .lens {
  position: absolute;
  left: 11px;
  top: 50%;
  transform: translateY(-50%);
  display: inline-flex;
  font-size: 15px;
  color: #909797;
  pointer-events: none;
}
.search-box input {
  width: 100%;
  height: 36px;
  border: 1px solid #dfe3e2;
  border-radius: var(--kwiki-radius);
  padding: 0 34px;
  color: #363c3c;
  outline: none;
  background: var(--kwiki-panel);
  font: inherit;
}
.search-box input:focus {
  border-color: #82dcb0;
  box-shadow: 0 0 0 3px rgba(24, 188, 114, 0.08);
}
.clear {
  position: absolute;
  right: 7px;
  top: 6px;
  width: 24px;
  height: 24px;
  display: none;
  place-items: center;
  border: 0;
  background: none;
  border-radius: 5px;
  color: #969d9d;
  cursor: pointer;
  font-size: 15px;
}
.clear.show {
  display: grid;
}
.clear:hover {
  background: #f1f3f2;
}
.count {
  margin: 0 0 7px;
  padding: 0 3px;
  color: var(--kwiki-muted);
  font-size: 12px;
}
.tree-area {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 10px 0 26px;
  scrollbar-width: thin;
}
.empty {
  padding: 34px 18px;
  text-align: center;
  color: var(--kwiki-muted);
  line-height: 1.7;
}
.empty span {
  font-size: 12px;
}
</style>
