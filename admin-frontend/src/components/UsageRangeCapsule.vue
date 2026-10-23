<script setup lang="ts">
import { computed, ref } from "vue";

/**
 * 胶囊范围选择器：背景是全时段用量轮廓（平方根缩放，小调用也看得见），选区为高亮胶囊。
 * 拖动胶囊平移、拖两端手柄缩放、在空白处拖拽新建选区；方向键微调。值为含两端的桶下标。
 */
const props = defineProps<{ values: number[]; modelValue: [number, number]; labels: [string, string] }>();
const emit = defineEmits<{ "update:modelValue": [range: [number, number]] }>();

const MIN_SPAN = 2;
const track = ref<HTMLElement>();
const last = computed(() => Math.max(1, props.values.length - 1));
const pct = (i: number) => `${(i / last.value) * 100}%`;
/** 选区过窄时两端标签会重叠，合并成一个居中标签 */
const narrow = computed(() => (props.modelValue[1] - props.modelValue[0]) / last.value < 0.14);
/** 选区贴近两端时标签改为向内对齐，避免溢出卡片 */
const edge = computed(() => {
  const [a, b] = props.modelValue, mid = (a + b) / 2 / last.value;
  return { l: a / last.value < 0.05, r: b / last.value > 0.95, midL: mid < 0.1, midR: mid > 0.9 };
});

const path = computed(() => {
  const max = Math.max(...props.values, 1);
  const h = 36, pad = 6;
  const ys = props.values.map(v => h - pad - (h - pad * 2) * Math.sqrt(v / max));
  const line = ys.map((y, i) => `${i ? "L" : "M"}${i},${y.toFixed(2)}`).join("");
  return { line, area: `${line}L${last.value},${h}L0,${h}Z`, viewBox: `0 0 ${last.value} ${h}` };
});

function indexAt(clientX: number) {
  const rect = track.value!.getBoundingClientRect();
  return Math.round(Math.min(1, Math.max(0, (clientX - rect.left) / rect.width)) * last.value);
}

function onPointerDown(event: PointerEvent) {
  if (props.values.length < 2) return;
  const el = event.currentTarget as HTMLElement;
  el.setPointerCapture?.(event.pointerId);
  const target = event.target as HTMLElement;
  const handle = target.closest<HTMLElement>("[data-handle]")?.dataset.handle;
  const mode = handle ?? (target.closest(".window") ? "move" : "new");
  const i0 = indexAt(event.clientX);
  const [a0, b0] = props.modelValue;
  const move = (ev: PointerEvent) => {
    const i = indexAt(ev.clientX);
    let a = a0, b = b0;
    if (mode === "l") a = Math.min(i, b0 - MIN_SPAN);
    else if (mode === "r") b = Math.max(i, a0 + MIN_SPAN);
    else if (mode === "move") { const d = Math.max(-a0, Math.min(last.value - b0, i - i0)); a += d; b += d; }
    else { a = Math.min(i0, i); b = Math.max(i0, i); if (b - a < MIN_SPAN) return; }
    if (a !== props.modelValue[0] || b !== props.modelValue[1]) emit("update:modelValue", [a, b]);
  };
  const up = () => { el.removeEventListener("pointermove", move); el.removeEventListener("pointerup", up); el.removeEventListener("pointercancel", up); };
  el.addEventListener("pointermove", move);
  el.addEventListener("pointerup", up);
  el.addEventListener("pointercancel", up);
}

function onKey(event: KeyboardEvent) {
  const step = event.key === "ArrowLeft" ? -1 : event.key === "ArrowRight" ? 1 : 0;
  if (!step) return;
  event.preventDefault();
  const [a, b] = props.modelValue;
  const d = Math.max(-a, Math.min(last.value - b, step));
  if (d) emit("update:modelValue", [a + d, b + d]);
}
</script>

<template>
  <div class="capsule" :class="{ empty: values.length < 2 }">
    <div ref="track" class="track" data-testid="range-capsule" @pointerdown="onPointerDown">
      <svg v-if="values.length > 1" class="shape" :viewBox="path.viewBox" preserveAspectRatio="none" aria-hidden="true">
        <path :d="path.area" class="area" />
        <path :d="path.line" class="line" vector-effect="non-scaling-stroke" />
      </svg>
      <div class="window" :style="{ left: pct(modelValue[0]), width: `calc(${pct(modelValue[1] - modelValue[0])})` }"
           role="slider" tabindex="0" aria-label="图表时间范围" :aria-valuetext="`${labels[0]} 至 ${labels[1]}`" @keydown="onKey">
        <i class="handle" data-handle="l" />
        <i class="handle r" data-handle="r" />
        <span v-if="narrow" class="label mid" :class="{ 'in-l': edge.midL, 'in-r': edge.midR }">{{ labels[0] }} – {{ labels[1] }}</span>
        <template v-else>
          <span class="label" :class="{ 'in-l': edge.l }">{{ labels[0] }}</span>
          <span class="label r" :class="{ 'in-r': edge.r }">{{ labels[1] }}</span>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.capsule {
  margin-top: 30px;
  user-select: none;
  touch-action: none;
}

.track {
  position: relative;
  height: 36px;
  border-radius: var(--k-r-pill);
  background: var(--k-surface);
  border: 1px solid var(--k-line);
  cursor: crosshair;
}

.shape {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: var(--k-r-pill);
}

.area {
  fill: var(--k-faint);
  opacity: 0.22;
}

.line {
  fill: none;
  stroke: var(--k-faint);
  stroke-width: 1.2;
}

.window {
  position: absolute;
  top: -1px;
  bottom: -1px;
  border-radius: var(--k-r-pill);
  background: var(--k-chart-accent-soft);
  box-shadow: inset 0 0 0 1.5px var(--k-chart-accent), 0 0 0 4px var(--k-chart-accent-soft);
  cursor: grab;
  outline: none;
}

.window:active {
  cursor: grabbing;
}

.window:focus-visible {
  box-shadow: inset 0 0 0 1.5px var(--k-chart-accent), 0 0 0 4px var(--k-chart-accent-soft), var(--k-focus-ring);
}

.handle {
  position: absolute;
  top: 50%;
  left: 0;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--k-canvas);
  box-shadow: 0 0 0 1.5px var(--k-chart-accent), 0 2px 6px rgba(0, 0, 0, 0.25);
  transform: translate(-50%, -50%);
  cursor: ew-resize;
}

.handle.r {
  left: 100%;
}

/* 扩大手柄命中区域 */
.handle::before {
  content: "";
  position: absolute;
  inset: -10px -8px;
}

.label {
  position: absolute;
  top: -24px;
  left: 0;
  transform: translateX(-50%);
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid var(--k-line);
  background: var(--k-canvas);
  color: var(--k-ink-2);
  font-size: 11px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
  pointer-events: none;
}

.label.r {
  left: 100%;
}

.label.mid {
  left: 50%;
}

.label.in-l {
  left: -8px;
  transform: none;
}

.label.in-r {
  left: calc(100% + 8px);
  transform: translateX(-100%);
}

.empty .window {
  display: none;
}
</style>
