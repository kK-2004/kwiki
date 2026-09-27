<script setup lang="ts">
/**
 * 针对每个归档条目的两段式顺序确认对话框。第一步说明
 * 归档对象及其子树影响；第二步说明回收站语义
 *（保留 7 天、立即停止检索）并展示受影响数量。
 * 取消任一步都不会调用后端；最终确认只会
 * 触发一次 —— 调用方在等待期间保持提交按钮禁用
 *（防抖 + 幂等的服务端调用）。
 */
import { computed, ref, watch } from 'vue';

const props = defineProps<{
  open: boolean;
  /** 页面 / 知识库 */
  scopeLabel: string;
  title: string;
  /** 受影响对象数量（子树页面数或知识库内容数）。 */
  itemCount?: number;
  pending?: boolean;
}>();

const emit = defineEmits<{ (e: 'cancel'): void; (e: 'confirm'): void }>();

const step = ref(1);

watch(
  () => props.open,
  (open) => {
    if (open) step.value = 1;
  },
);

const countText = computed(() =>
  props.itemCount == null ? '' : `将同时归档 ${props.itemCount} 个相关对象`,
);

function next() {
  step.value = 2;
}

function cancel() {
  emit('cancel');
}

function confirmArchive() {
  if (props.pending) return;
  emit('confirm');
}
</script>

<template>
  <div v-if="open" class="archive-overlay" data-testid="archive-confirm">
    <section role="dialog" aria-modal="true" :aria-label="`归档${scopeLabel}`" class="archive-dialog">
      <template v-if="step === 1">
        <h3>归档{{ scopeLabel }}「{{ title }}」？</h3>
        <p>归档会一并处理其下所有有效内容{{ countText ? '，' + countText : '' }}。此操作进入下一步确认前不会产生任何变更。</p>
        <footer>
          <button type="button" class="ui-button" data-testid="archive-cancel-1" @click="cancel">取消</button>
          <button type="button" class="ui-button" data-testid="archive-next" @click="next">下一步</button>
        </footer>
      </template>
      <template v-else>
        <h3>确认移入回收站</h3>
        <p>「{{ title }}」将移入回收站，保留 <strong>7 天</strong>，期间可恢复；归档后立即停止被检索。{{ countText }}</p>
        <footer>
          <button type="button" class="ui-button" data-testid="archive-cancel-2" @click="cancel">取消</button>
          <button type="button" class="ui-button danger" data-testid="archive-confirm-submit" :disabled="pending" @click="confirmArchive">
            {{ pending ? '正在归档…' : '确认归档' }}
          </button>
        </footer>
      </template>
    </section>
  </div>
</template>

<style scoped>
.archive-overlay {
  position: fixed;
  inset: 0;
  background: var(--k-overlay);
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 20px;
}
.archive-dialog {
  width: min(420px, 100%);
  background: var(--k-canvas);
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  box-shadow: var(--k-shadow-float);
  padding: 22px;
}
.archive-dialog h3 {
  margin: 0 0 10px;
  font-size: 16px;
  font-weight: 600;
  color: var(--k-ink);
}
.archive-dialog p {
  color: var(--k-ink-2);
  line-height: 1.7;
  font-size: 13px;
}
.archive-dialog footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}
/* 危险确认：实心红底；文字用 canvas，浅色下为白字、深色下为深字以保证对比 */
.ui-button.danger {
  color: var(--k-canvas);
  background: var(--k-danger);
  border-color: var(--k-danger);
}
.ui-button.danger:hover {
  color: var(--k-canvas);
  background: color-mix(in srgb, var(--k-danger) 88%, var(--k-ink));
  border-color: color-mix(in srgb, var(--k-danger) 88%, var(--k-ink));
}
.ui-button.danger:disabled {
  opacity: 0.6;
  cursor: wait;
}
</style>
