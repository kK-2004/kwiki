<template>
  <ul role="tree" aria-label="知识树" data-testid="wiki-tree">
    <li
      v-for="item in rows"
      :key="item.node.id"
      role="treeitem"
      :aria-expanded="item.node.children.length > 0 ? expanded.has(item.node.id) : hasOutline(item.node) ? outlineOpen : undefined"
      :aria-selected="item.node.id === selectedId"
      :tabindex="item.node.id === selectedId ? 0 : -1"
      :data-testid="`tree-node-${item.node.id}`"
      :style="{ paddingLeft: `${item.depth * 15}px` }"
      @keydown="onKeyDown($event, item.node)"
    >
      <button
        type="button"
        class="row"
        :class="{ selected: item.node.id === selectedId, leaf: item.node.children.length === 0 && !hasOutline(item.node) }"
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
      <div v-if="hasOutline(item.node)" class="outline-drawer" :class="{ open: outlineOpen }">
        <ul :id="outlineId" class="outline-list" role="group" aria-label="页面大纲" :inert="!outlineOpen">
          <li v-for="heading in outline" :key="heading.index">
            <button
              type="button"
              class="outline-item"
              :class="[`depth-${Math.min(heading.level - minLevel, 2)}`, { active: outlineJump?.index === heading.index }]"
              :aria-current="outlineJump?.index === heading.index ? 'location' : undefined"
              :style="{ paddingLeft: `${(heading.level - minLevel) * 12 + 12}px` }"
              :title="heading.text"
              data-testid="outline-item"
              @click="jumpToHeading(heading.index)"
              @keydown.stop
            >{{ heading.text }}</button>
          </li>
        </ul>
      </div>
    </li>
  </ul>
</template>

<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue';
import type { TreeNodeDto } from '../api';
import { flattenTree } from '../tree-filter';
import { useWikiStore } from '../store';
import { extractOutline, jumpToHeading, outlineJump } from '../pageOutline';

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

// 选中页面下方以抽屉展开其标题大纲；再次点击已选中的页面可折叠/展开
const outlineId = useId();
const outlineOpen = ref(true);
watch(selectedId, () => { outlineOpen.value = true; outlineJump.value = null; });
const outline = computed(() => extractOutline(store.page?.html ?? ''));
const minLevel = computed(() => Math.min(...outline.value.map(item => item.level)));
function hasOutline(node: TreeNodeDto): boolean {
  return node.nodeType === 'PAGE' && node.id === selectedId.value && outline.value.length > 0;
}

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
  if (hasOutline(node)) {
    outlineOpen.value = !outlineOpen.value;
    return;
  }
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
  } else if (hasOutline(node) && (event.key === 'ArrowRight' || event.key === 'ArrowLeft')) {
    outlineOpen.value = event.key === 'ArrowRight';
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
  min-height: 30px;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  border: 0;
  background: none;
  border-radius: var(--k-r-sm);
  text-align: left;
  font-size: 13px;
  color: var(--k-ink-2);
  cursor: pointer;
}
.row:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}
/* 选中项：白底 + 左侧 2px 品牌绿竖线 */
.row.selected {
  position: relative;
  background: var(--k-selected);
  color: var(--k-ink);
  font-weight: 500;
  box-shadow: var(--k-shadow-sm);
}
.row.selected::before {
  content: '';
  position: absolute;
  left: 0;
  top: 6px;
  bottom: 6px;
  width: 2px;
  border-radius: 2px;
  background: var(--k-green);
}
.caret {
  width: 14px;
  display: inline-flex;
  justify-content: center;
  color: var(--k-faint);
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
  color: var(--k-muted);
  font-size: 14px;
  flex: none;
}
.selected .node-icon {
  color: var(--k-green-deep);
}
.node-title {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.outline-drawer {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.2s ease;
}
.outline-drawer.open {
  grid-template-rows: 1fr;
}
/* 抽屉自身限高并内部滚动，不撑开整个目录列表 */
.outline-list {
  list-style: none;
  min-height: 0;
  max-height: min(320px, 42vh);
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  margin: 0 0 0 27px;
  /* 右侧为滚动条留出位置（macOS 浮层滚动条不占布局宽度），避免条目背景盖住滚动条 */
  padding: 0 10px 0 0;
  scrollbar-gutter: stable;
  border-left: 1px solid var(--k-line);
  scrollbar-width: thin;
  scrollbar-color: var(--k-line-strong) transparent;
}
.outline-drawer:not(.open) .outline-list {
  overflow: hidden;
}
.outline-drawer.open .outline-list {
  margin-block: 2px 6px;
}
.outline-list::-webkit-scrollbar { width: 6px; }
.outline-list::-webkit-scrollbar-thumb { background: var(--k-line-strong); border-radius: 999px; }
.outline-item {
  position: relative;
  display: block;
  width: 100%;
  min-height: 0;
  padding-block: 4px;
  padding-right: 6px;
  border: 0;
  background: none;
  border-radius: 0 var(--k-r-sm) var(--k-r-sm) 0;
  text-align: left;
  font: inherit;
  font-size: 12px;
  line-height: 1.5;
  color: var(--k-ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  transition: color 0.12s ease, background 0.12s ease;
}
.outline-item.depth-1 { color: var(--k-muted); }
.outline-item.depth-2 { color: var(--k-faint); font-size: 11.5px; }
.outline-item:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}
/* 当前定位项：压在左侧细线上的绿色短竖线 */
.outline-item.active {
  color: var(--k-green-deep);
  font-weight: 500;
}
.outline-item.active::before {
  content: '';
  position: absolute;
  left: -1px;
  top: 5px;
  bottom: 5px;
  width: 2px;
  border-radius: 2px;
  background: var(--k-green);
}
.outline-item:focus-visible {
  outline: 2px solid var(--k-green);
  outline-offset: -2px;
}
.node-count {
  color: var(--k-faint);
  font-size: 11px;
}
</style>
