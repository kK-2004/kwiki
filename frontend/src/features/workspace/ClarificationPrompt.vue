<template>
  <section class="clarification" aria-label="需要你确认">
    <p v-if="showQuestion" class="clarification-question">{{ clarification.question }}</p>
    <div v-if="clarification.options.length" class="clarification-options">
      <button v-for="option in clarification.options" :key="option.id" type="button" class="clarification-option" :disabled="disabled" @click="emit('choose', option)">{{ option.label }}</button>
    </div>
    <form v-if="clarification.allowFreeText" class="clarification-free" @submit.prevent="submit">
      <input v-model="text" aria-label="补充说明" placeholder="或者直接告诉我…" maxlength="500" :disabled="disabled" />
      <button type="submit" class="clarification-send" :disabled="disabled || !text.trim()">发送</button>
    </form>
  </section>
</template>
<script setup lang="ts">
import { ref } from 'vue';
import type { ClarificationOption, ClarificationState } from '../wiki/sse';
defineProps<{ clarification: ClarificationState; disabled?: boolean; showQuestion?: boolean }>();
const emit = defineEmits<{ choose: [option: ClarificationOption]; submit: [text: string] }>();
const text = ref('');
function submit() {
  const value = text.value.trim();
  if (!value) return;
  emit('submit', value);
  text.value = '';
}
</script>
<style scoped>
.clarification { margin: 0 0 24px 43px; display: grid; gap: 10px; }
.clarification-question { margin: 0; color: var(--k-ink); }
.clarification-options { display: flex; flex-wrap: wrap; gap: 8px; }
.clarification-option { border: 1px solid var(--k-line); border-radius: var(--k-r); background: var(--k-surface); color: var(--k-ink-2); padding: 7px 14px; font: inherit; font-size: 13px; cursor: pointer; }
.clarification-option:hover:not(:disabled) { border-color: var(--k-line-strong); background: var(--k-surface-hover); }
.clarification-free { display: flex; gap: 8px; max-width: 520px; }
.clarification-free input { flex: 1; min-width: 0; border: 1px solid var(--k-line); border-radius: var(--k-r); background: var(--k-canvas); color: var(--k-ink); padding: 7px 12px; font: inherit; font-size: 13px; }
.clarification-free input:focus { outline: none; border-color: var(--k-line-strong); box-shadow: var(--k-focus-ring); }
.clarification-send { border: 0; border-radius: var(--k-r); background: var(--k-primary); color: var(--k-on-primary); padding: 0 16px; font: inherit; font-size: 13px; cursor: pointer; }
.clarification-send:disabled, .clarification-option:disabled { opacity: .5; cursor: not-allowed; }
</style>
