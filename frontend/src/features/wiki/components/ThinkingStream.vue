<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue';
import { useFollowTail } from './followTail';

const props = defineProps<{ text: string; running?: boolean }>();
const area = ref<HTMLElement>();
const tail = useFollowTail();
const emptyLabel = computed(() => props.running ? '等待模型思考流…' : '模型未返回思考内容');
const latest = computed(() => props.text.trim().split('\n').filter(Boolean).at(-1)?.slice(-80) || emptyLabel.value);
const expanded = ref(false);
const contentId = useId();

/** prefers-reduced-motion 只缩短视觉过渡；内容、滚动与语义保持不变。 */
const reducedMotion = ref(false);
let media: MediaQueryList | null = null;
function syncReducedMotion() {
  reducedMotion.value = Boolean(media?.matches);
}
const duration = computed(() => reducedMotion.value ? '0ms' : '220ms');

onMounted(() => {
  if (area.value) tail.attach(area.value);
  if (typeof matchMedia === 'function') {
    media = matchMedia('(prefers-reduced-motion: reduce)');
    syncReducedMotion();
    media.addEventListener?.('change', syncReducedMotion);
  }
});
onBeforeUnmount(() => {
  tail.detach();
  media?.removeEventListener?.('change', syncReducedMotion);
});

/** 内层滚动只驱动内层跟随状态，绝不修改外层消息区的滚动。 */
function onScroll() { tail.handleUserScroll(); }

watch(() => props.text, () => {
  if (!expanded.value) return;
  void tail.notifyContentChanged();
});

async function onToggle() {
  expanded.value = !expanded.value;
  await nextTick();
  // 重新展开时不强制跳底：仅当思考区本就处于跟随状态才贴到最新内容。
  if (expanded.value) void tail.notifyContentChanged();
}
</script>
<template>
  <div class="thinking-stream" :class="{ open: expanded }" :style="{ '--thinking-duration': duration }">
    <button type="button" class="thinking-toggle" :aria-expanded="expanded" :aria-controls="contentId" @click="onToggle">
      <span class="collapsed-label">思考过程</span>
      <span class="expanded-label">收起思考过程</span>
      <span class="latest">{{ latest }}</span>
    </button>
    <div class="thinking-body">
      <div class="thinking-clip">
        <div :id="contentId" ref="area" class="thinking-content" tabindex="0" role="region" aria-label="模型思考过程" @scroll="onScroll">{{ text || emptyLabel }}</div>
      </div>
    </div>
  </div>
</template>
<style scoped>
.thinking-stream{min-width:0;margin:5px 0;color:#7b897f}.thinking-toggle{display:flex;align-items:center;gap:8px;width:100%;min-width:0;color:inherit;font:inherit;text-align:left;cursor:pointer;padding:3px 0;border:0;background:none;list-style:none}.thinking-toggle:focus-visible{outline:2px solid #18bc72;outline-offset:2px}.thinking-toggle::before{content:'›';flex-shrink:0;transition:transform .15s}.thinking-stream.open .thinking-toggle::before{transform:rotate(90deg)}.thinking-toggle>span{min-width:0}.collapsed-label,.expanded-label{flex:0 0 auto;white-space:nowrap}.expanded-label{display:none}.thinking-stream.open .collapsed-label,.thinking-stream.open .latest{display:none}.thinking-stream.open .expanded-label{display:inline}.latest{flex:1;text-align:left;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.thinking-body{display:grid;grid-template-rows:0fr;opacity:0;transition:grid-template-rows var(--thinking-duration) ease,opacity var(--thinking-duration) ease}.thinking-stream.open .thinking-body{grid-template-rows:1fr;opacity:1}.thinking-clip{overflow:hidden;min-height:0}.thinking-content{box-sizing:border-box;height:200px;min-height:0;overflow-y:auto;overscroll-behavior:contain;white-space:pre-wrap;overflow-wrap:anywhere;background:#f4f7f5;border:1px solid #e1e9e3;border-radius:6px;padding:10px;line-height:1.7}
</style>
