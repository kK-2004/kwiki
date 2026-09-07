<template>
  <section
    v-show="open"
    class="agentic"
    data-testid="agentic-panel"
    aria-label="智能问答"
  >
    <div class="panel-head">
      <strong>问问 kwiki</strong>
      <button type="button" class="icon" aria-label="收起问答面板" @click="open = false">
        <i class="i-lucide-x" aria-hidden="true"></i>
      </button>
    </div>
    <form @submit.prevent="ask">
      <input
        v-model="question"
        aria-label="提问"
        placeholder="向 kwiki 提问"
        data-testid="agentic-input"
      />
      <button
        type="submit"
        :disabled="stream.terminated && !!stream.answer"
        data-testid="agentic-ask"
      >
        提问
      </button>
    </form>

    <ol v-if="stream.progress.length" data-testid="agentic-progress" aria-live="polite">
      <li v-for="(step, index) in stream.progress" :key="index">{{ stepLabel(step) }}</li>
    </ol>

    <p v-if="stream.answer" data-testid="agentic-answer">{{ stream.answer }}</p>

    <p v-if="terminatedNoEvidence" data-testid="agentic-no-evidence">
      未在当前可访问知识中找到依据
    </p>

    <ul v-if="stream.citations.length" data-testid="agentic-citations">
      <li v-for="citation in stream.citations" :key="citation.childChunkKey">
        <button type="button" @click="emit('citation', citation)">
          {{ citation.headingPath }}（字符 {{ citation.charStart }}–{{ citation.charEnd }}）
        </button>
      </li>
    </ul>

    <p v-if="stream.error && stream.answer">回答尚未完成，请勿将其视为完整结论。</p>
    <p v-if="stream.message && !stream.answer" aria-live="polite">{{ stream.message }}</p>
    <p v-if="stream.error" role="alert" data-testid="agentic-error">
      {{ stream.error }}
    </p>
  </section>

  <button type="button" class="ask" :aria-expanded="open" @click="open = !open">
    <i class="i-lucide-sparkles" aria-hidden="true"></i>问问 kwiki
  </button>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { initialState, openStream, reduce, type CitationEntry, type StreamState } from '../sse';

const open = ref(false);
const question = ref('');
const stream = ref<StreamState>(initialState());
let dispose: (() => void) | null = null;

const terminatedNoEvidence = computed(
  () => stream.value.terminated && !stream.value.answer && !stream.value.error && !stream.value.message,
);

const stepLabels: Record<string, string> = {
  route: '意图识别',
  rewrite: '查询改写',
  retrieve: '知识检索',
  tool: '执行检索工具',
  quality: '检查证据质量',
  retry: '补充检索',
};

function stepLabel(step: string): string {
  return stepLabels[step] ?? step;
}

function ask() {
  if (!question.value.trim()) {
    return;
  }
  dispose?.();
  stream.value = initialState();
  dispose = openStream(question.value, (frame) => {
    stream.value = reduce(stream.value, frame);
  });
}

onBeforeUnmount(() => dispose?.());

const emit = defineEmits<{ citation: [citation: CitationEntry] }>();
</script>

<style scoped>
.agentic {
  position: fixed;
  right: 24px;
  bottom: 76px;
  z-index: 45;
  width: min(360px, calc(100vw - 32px));
  max-height: min(60vh, 520px);
  overflow: auto;
  padding: 14px;
  border: 1px solid var(--kwiki-line);
  border-radius: 12px;
  background: var(--kwiki-panel);
  box-shadow: 0 12px 40px rgba(28, 47, 38, 0.18);
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.icon {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  border-radius: 6px;
  color: #737a7a;
  cursor: pointer;
  font-size: 15px;
}
.icon:hover {
  background: #eef1ef;
}
form {
  display: flex;
  gap: 7px;
}
input {
  flex: 1;
  min-width: 0;
  height: 34px;
  padding: 0 10px;
  border: 1px solid #dfe3e2;
  border-radius: 6px;
  outline: none;
  font: inherit;
}
input:focus {
  border-color: #82dcb0;
  box-shadow: 0 0 0 3px rgba(24, 188, 114, 0.08);
}
form button[type='submit'] {
  height: 34px;
  padding: 0 13px;
  border: 0;
  border-radius: 6px;
  background: var(--kwiki-green);
  color: #fff;
  font-weight: 600;
  cursor: pointer;
}
form button[type='submit']:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
ol,
ul {
  margin: 10px 0 0;
  padding-left: 20px;
  color: #52605a;
  font-size: 13px;
}
.agentic-answer,
.agentic-no-evidence,
.agentic-error {
  margin: 10px 0 0;
  line-height: 1.7;
  white-space: pre-wrap;
}
.agentic-no-evidence,
.agentic-error {
  color: var(--kwiki-muted);
}
.agentic-error {
  color: var(--kwiki-danger);
}
.agentic-citations button {
  border: 0;
  background: none;
  padding: 0;
  color: var(--kwiki-green-dark);
  cursor: pointer;
  border-bottom: 1px dashed #5ed19a;
  font: inherit;
}
.ask {
  position: fixed;
  right: 24px;
  bottom: 22px;
  z-index: 44;
  height: 42px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  border: 0;
  border-radius: 22px;
  background: #26332d;
  color: #fff;
  box-shadow: 0 8px 24px rgba(28, 47, 38, 0.2);
  font-weight: 650;
  cursor: pointer;
}
.ask i {
  font-size: 16px;
  color: var(--kwiki-green);
}
</style>
