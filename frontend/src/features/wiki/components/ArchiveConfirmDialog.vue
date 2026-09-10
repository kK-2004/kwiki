<script setup lang="ts">
/**
 * Two sequential confirmation modals for every archive entry. Step 1 explains
 * the object and its subtree impact; step 2 states the recycle-bin semantics
 * (7-day retention, immediate retrieval stop) and shows the affected count.
 * Cancelling either step never calls the backend; the final confirm emits
 * exactly once — the caller keeps the submit disabled while pending
 * (debounce + idempotent server call).
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
  background: #0003;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 20px;
}
.archive-dialog {
  width: min(420px, 100%);
  background: #fff;
  border: 1px solid #e1e9e3;
  border-radius: 12px;
  padding: 22px;
}
.archive-dialog h3 {
  margin: 0 0 10px;
  font-size: 16px;
}
.archive-dialog p {
  color: #667d6e;
  line-height: 1.7;
  font-size: 13px;
}
.archive-dialog footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}
.danger {
  color: #ad4c4c;
  border-color: #e0c4c4;
}
.danger:disabled {
  opacity: 0.6;
  cursor: wait;
}
</style>
