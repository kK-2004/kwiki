<template>
  <ul role="tree" aria-label="知识树" data-testid="wiki-tree">
    <li
      v-for="item in rows"
      :key="item.node.id"
      role="treeitem"
      :aria-expanded="item.node.children.length > 0 ? expanded.has(item.node.id) : undefined"
      :aria-selected="item.node.id === selectedId"
      :tabindex="item.node.id === selectedId ? 0 : -1"
      :data-testid="`tree-node-${item.node.id}`"
      :style="{ paddingLeft: `${item.depth * 15}px` }"
      @keydown="onKeyDown($event, item.node)"
    >
      <button
        type="button"
        class="row"
        :class="{ selected: item.node.id === selectedId, leaf: item.node.children.length === 0 }"
        @click="select(item.node)"
      >
        <span class="caret" aria-hidden="true"><i class="i-lucide-chevron-down"></i></span>
        <span class="node-icon" aria-hidden="true">
          <i :class="item.node.nodeType === 'FOLDER' ? 'i-lucide-folder' : 'i-lucide-file-text'"></i>
        </span>
        <span class="node-title">{{ item.node.title }}</span>
        <span v-if="item.node.children.length > 0" class="node-count">{{
          countPages(item.node.children)
        }}</span>
      </button>
    </li>
  </ul>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { TreeNodeDto } from '../api';
import { flattenTree } from '../tree-filter';
import { useWikiStore } from '../store';

const props = defineProps<{ tree: TreeNodeDto[] }>();
const store = useWikiStore();
const selectedId = computed(() => store.selectedPageId);
const expanded = ref(new Set<number>());

watch(() => [props.tree, selectedId.value], () => {
  function reveal(nodes: TreeNodeDto[]): boolean {
    return nodes.some(node => { const found = node.id === selectedId.value || reveal(node.children || []); if (found && node.children.length) expanded.value.add(node.id); return found; });
  }
  reveal(props.tree);
}, { immediate: true });
const rows = computed(() => flattenTree(props.tree, expanded.value));

function countPages(nodes: TreeNodeDto[]): number {
  return nodes.reduce(
    (total, node) =>
      total + (node.nodeType === 'PAGE' ? 1 : 0) + countPages(node.children ?? []),
    0,
  );
}

function toggle(id: number) {
  const next = new Set(expanded.value);
  if (next.has(id)) {
    next.delete(id);
  } else {
    next.add(id);
  }
  expanded.value = next;
}

function select(node: TreeNodeDto) {
  if (node.children.length > 0) {
    toggle(node.id);
  }
  if (node.nodeType === 'PAGE') {
    store.selectPage(node.id);
    const match = window.location.hash.match(/#\/?knowledge-bases\/(\d+)/);
    if (match) window.location.hash = `#/knowledge-bases/${match[1]}/${node.id}`;
  } else {
    store.selectPage(null);
    const match = window.location.hash.match(/#\/?knowledge-bases\/(\d+)/);
    if (match) window.location.hash = `#/knowledge-bases/${match[1]}/${node.id}`;
  }
}

function onKeyDown(event: KeyboardEvent, node: TreeNodeDto) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    select(node);
  } else if (event.key === 'ArrowRight' && node.children.length > 0) {
    toggle(node.id);
  } else if (event.key === 'ArrowLeft' && expanded.value.has(node.id)) {
    toggle(node.id);
  }
}
</script>

<style scoped>
ul {
  list-style: none;
  margin: 0;
  padding: 0;
}
li {
  min-width: 0;
}
.row {
  min-height: 34px;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  border: 0;
  background: none;
  border-radius: 7px;
  text-align: left;
  color: #404646;
  cursor: pointer;
}
.row:hover {
  background: var(--kwiki-hover-bg);
}
.row.selected {
  background: var(--kwiki-selected-bg);
  color: var(--kwiki-green-dark);
  font-weight: 650;
}
.caret {
  width: 14px;
  display: inline-flex;
  justify-content: center;
  color: #9ea4a4;
  font-size: 12px;
  transition: transform 0.14s;
  flex: none;
}
li[aria-expanded='false'] .caret {
  transform: rotate(-90deg);
}
.leaf .caret {
  visibility: hidden;
}
.node-icon {
  width: 16px;
  display: inline-flex;
  color: #929999;
  font-size: 14px;
  flex: none;
}
.selected .node-icon {
  color: var(--kwiki-green);
}
.node-title {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-count {
  color: #afb4b4;
  font-size: 11px;
}
</style>
