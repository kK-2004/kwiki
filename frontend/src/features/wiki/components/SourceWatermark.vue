<template>
  <div ref="rootEl" class="source-watermark" data-testid="source-watermark" aria-hidden="true">
    <div class="wm-grid" :style="gridStyle">
      <span v-for="tile in tileCount" :key="tile">{{ text }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

/**
 * 源文件预览水印：铺满父容器（父容器需为定位元素），不随文档缩放或滚动。
 * 平铺数量按容器实际尺寸计算，保证任意宽高下都没有空白区域。
 */
const props = defineProps<{ text: string }>();

// 水印字号与格间距，决定平铺密度
const FONT_SIZE = 13;
const GAP_X = 72;
const TILE_HEIGHT = 120;
// 旋转后四角会露出空白，网格按对角线尺寸铺开
const FALLBACK_SIZE = { width: 1200, height: 900 };

const rootEl = ref<HTMLElement | null>(null);
const size = ref({ ...FALLBACK_SIZE });
let observer: ResizeObserver | null = null;

/** 按字符估算文本宽度（中日韩字符约一个字号宽，其余约 0.6 个字号），格宽随文本变长，避免相邻水印重叠 */
const tileWidth = computed(() => {
  let width = 0;
  for (const char of props.text) width += /[\u2e80-\u9fff\uff00-\uffef]/.test(char) ? FONT_SIZE : FONT_SIZE * 0.6;
  return Math.ceil(width) + GAP_X;
});
const span = computed(() => Math.ceil(Math.hypot(size.value.width, size.value.height)));
const columns = computed(() => Math.max(1, Math.ceil(span.value / tileWidth.value)));
const rows = computed(() => Math.max(1, Math.ceil(span.value / TILE_HEIGHT)));
const tileCount = computed(() => columns.value * rows.value);
const gridStyle = computed(() => ({
  width: `${span.value}px`,
  height: `${span.value}px`,
  gridTemplateColumns: `repeat(${columns.value}, ${tileWidth.value}px)`,
  gridAutoRows: `${TILE_HEIGHT}px`,
}));

function measure() {
  const element = rootEl.value;
  if (!element) return;
  const { width, height } = element.getBoundingClientRect();
  // jsdom 等环境拿不到尺寸时保留兜底值，仍能渲染水印文本
  if (width > 0 && height > 0) size.value = { width, height };
}

onMounted(() => {
  measure();
  if (typeof ResizeObserver !== 'undefined' && rootEl.value) {
    observer = new ResizeObserver(measure);
    observer.observe(rootEl.value);
  }
});
onBeforeUnmount(() => observer?.disconnect());
</script>

<style scoped>
.source-watermark {
  position: absolute;
  inset: 0;
  z-index: 4;
  overflow: hidden;
  pointer-events: none;
  user-select: none;
}

/* 以容器中心为原点旋转，对角线尺寸的网格保证旋转后仍覆盖四角 */
.wm-grid {
  position: absolute;
  top: 50%;
  left: 50%;
  display: grid;
  place-items: center;
  justify-content: center;
  align-content: center;
  transform: translate(-50%, -50%) rotate(-22deg);
  /* 水印叠在白色纸张与主题画布上，两种主题都用半透明中性色 */
  color: color-mix(in srgb, var(--k-muted) 28%, transparent);
  font-size: 13px; /* 与脚本中 FONT_SIZE 保持一致 */
  white-space: nowrap;
}

@media print {
  .source-watermark {
    position: fixed;
    z-index: 9999;
  }
}
</style>
