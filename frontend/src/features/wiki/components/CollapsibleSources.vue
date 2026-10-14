<script setup lang="ts">
/**
 * 命中片段条：默认只显示一行，溢出部分在右侧渐隐；点击末尾按钮展开全部或收起。
 * 仅当内容确实超过一行时才显示展开按钮。
 */
import { nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue';

const props = defineProps<{ count: number }>();

const listId = useId();
const expanded = ref(false);
const overflowing = ref(false);
const list = ref<HTMLElement | null>(null);
const rowHeight = ref<number>();
let observer: ResizeObserver | undefined;

function measure() {
  const el = list.value;
  if (!el || expanded.value) return;
  const first = el.firstElementChild as HTMLElement | null;
  if (first?.offsetHeight) rowHeight.value = first.offsetHeight;
  overflowing.value = el.scrollHeight > el.clientHeight + 1;
}

onMounted(() => {
  measure();
  if (typeof ResizeObserver !== 'undefined' && list.value) {
    observer = new ResizeObserver(measure);
    observer.observe(list.value);
  }
});
onBeforeUnmount(() => observer?.disconnect());
watch(() => props.count, () => nextTick(measure));

function toggle() {
  expanded.value = !expanded.value;
  if (!expanded.value) nextTick(measure);
}
</script>

<template>
  <div class="collapsible-sources" :class="{ expanded, overflowing }">
    <div :id="listId" ref="list" class="list" :style="!expanded && rowHeight ? { maxHeight: `${rowHeight}px` } : undefined" data-testid="collapsible-sources-list">
      <slot />
    </div>
    <button
      v-if="overflowing || expanded"
      type="button"
      class="toggle"
      :aria-expanded="expanded"
      :aria-controls="listId"
      :aria-label="expanded ? '收起命中片段' : `展开全部 ${count} 个命中片段`"
      data-testid="collapsible-sources-toggle"
      @click="toggle"
    >
      <span>{{ expanded ? '收起' : `全部 ${count}` }}</span>
      <span class="i-lucide-chevron-down chevron" :class="{ open: expanded }" aria-hidden="true"></span>
    </button>
  </div>
</template>

<style scoped>
.collapsible-sources {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 2px;
}
.list {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  /* 收起时只保留一行；实际行高挂载后按首个片段测量 */
  max-height: 22px;
  overflow: hidden;
}
.collapsible-sources.overflowing:not(.expanded) .list {
  -webkit-mask-image: linear-gradient(to right, #000 calc(100% - 72px), transparent);
  mask-image: linear-gradient(to right, #000 calc(100% - 72px), transparent);
}
.collapsible-sources.expanded .list {
  max-height: none;
}
.toggle {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  height: 20px;
  padding: 0 6px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-sm);
  background: var(--k-canvas);
  color: var(--k-muted);
  font: inherit;
  cursor: pointer;
  white-space: nowrap;
}
.toggle:hover { color: var(--k-green-deep); border-color: var(--k-green); }
.toggle:focus-visible { outline: 2px solid var(--k-green); outline-offset: 1px; }
.chevron { transition: transform 0.15s ease; }
.chevron.open { transform: rotate(180deg); }
</style>
