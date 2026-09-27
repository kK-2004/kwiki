<template>
  <div v-if="variant === 'segmented'" class="segmented" role="radiogroup" aria-label="主题">
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      role="radio"
      :aria-checked="mode === option.value"
      :class="{ active: mode === option.value }"
      @click="setMode(option.value)"
    >
      <i :class="option.icon" aria-hidden="true" /><span>{{ option.label }}</span>
    </button>
  </div>
  <div v-else ref="rootEl" class="theme-menu">
    <button
      type="button"
      class="ui-icon trigger"
      :class="{ wide: !compact }"
      aria-label="切换主题"
      aria-haspopup="menu"
      :aria-expanded="open"
      :title="`主题：${current.label}`"
      @click="open = !open"
    >
      <i :class="current.icon" aria-hidden="true" /><span v-if="!compact">{{ current.label }}</span>
    </button>
    <div v-if="open" class="menu" role="menu" aria-label="主题" @keydown.esc.stop="close">
      <button
        v-for="option in options"
        :key="option.value"
        type="button"
        role="menuitemradio"
        :aria-checked="mode === option.value"
        @click="pick(option.value)"
      >
        <i :class="option.icon" aria-hidden="true" /><span>{{ option.label }}</span>
        <i v-if="mode === option.value" class="i-lucide-check check" aria-hidden="true" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useTheme, type ThemeMode } from './useTheme';

withDefaults(defineProps<{ variant?: 'menu' | 'segmented'; compact?: boolean }>(), {
  variant: 'menu',
  compact: false,
});

const options: { value: ThemeMode; label: string; icon: string }[] = [
  { value: 'light', label: '白天', icon: 'i-lucide-sun' },
  { value: 'dark', label: '黑夜', icon: 'i-lucide-moon' },
  { value: 'system', label: '跟随系统', icon: 'i-lucide-monitor' },
];

const { mode, setMode } = useTheme();
const current = computed(() => options.find((option) => option.value === mode.value)!);
const open = ref(false);
const rootEl = ref<HTMLElement | null>(null);

function close() {
  open.value = false;
}

function pick(value: ThemeMode) {
  setMode(value);
  close();
}

// 点击组件外部时收起菜单
function onDocumentPointer(event: PointerEvent) {
  if (rootEl.value && !rootEl.value.contains(event.target as Node)) close();
}

watch(open, async (value) => {
  if (value) {
    document.addEventListener('pointerdown', onDocumentPointer);
    await nextTick();
    rootEl.value?.querySelector<HTMLElement>('[role="menuitemradio"][aria-checked="true"]')?.focus();
  } else {
    document.removeEventListener('pointerdown', onDocumentPointer);
  }
});

onBeforeUnmount(() => document.removeEventListener('pointerdown', onDocumentPointer));
</script>

<style scoped>
.theme-menu {
  position: relative;
}

.trigger.wide {
  width: auto;
  gap: 6px;
  padding: 0 8px;
  font-size: 12px;
}

.menu {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  z-index: 90;
  display: grid;
  min-width: 148px;
  padding: 4px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  box-shadow: var(--k-shadow-float);
}

.menu button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 0;
  border-radius: var(--k-r-sm);
  background: transparent;
  color: var(--k-ink-2);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.menu button:hover,
.menu button:focus-visible {
  background: var(--k-surface-hover);
  color: var(--k-ink);
  outline: none;
}

.menu .check {
  margin-left: auto;
  color: var(--k-green-deep);
}

.segmented {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-surface);
}

.segmented button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border: 0;
  border-radius: var(--k-r-sm);
  background: transparent;
  color: var(--k-muted);
  font-size: 13px;
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
</style>
