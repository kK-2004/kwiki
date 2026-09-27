<template>
  <div class="segmented" role="radiogroup" aria-label="主题">
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      role="radio"
      :aria-checked="mode === option.value"
      :aria-label="option.label"
      :title="option.label"
      :class="{ active: mode === option.value }"
      @click="setMode(option.value)"
    >
      <svg viewBox="0 0 24 24" aria-hidden="true"><path :d="option.path" /></svg>
    </button>
  </div>
</template>

<script setup lang="ts">
import { useTheme, type ThemeMode } from "./useTheme";

// 管理端未接入 UnoCSS 图标，这里内联 lucide 的 sun / moon / monitor 路径
const options: { value: ThemeMode; label: string; path: string }[] = [
  { value: "light", label: "白天", path: "M12 3v2M12 19v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M3 12h2M19 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z" },
  { value: "dark", label: "黑夜", path: "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" },
  { value: "system", label: "跟随系统", path: "M4 4h16v12H4zM8 20h8M12 16v4" },
];
const { mode, setMode } = useTheme();
</script>

<style scoped>
.segmented {
  display: flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-surface);
}

.segmented button {
  flex: 1;
  display: grid;
  place-items: center;
  height: 26px;
  border: 0;
  border-radius: var(--k-r-sm);
  background: transparent;
  color: var(--k-muted);
  cursor: pointer;
}

.segmented button:hover {
  color: var(--k-ink);
}

.segmented button.active {
  background: var(--k-canvas);
  color: var(--k-ink);
  box-shadow: var(--k-shadow-sm);
}

svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
