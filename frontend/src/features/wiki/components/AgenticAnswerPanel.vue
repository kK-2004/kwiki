<template>
  <section
    v-show="open"
    class="agentic"
    ref="panel"
    data-testid="agentic-panel"
    aria-label="智能问答"
    @scroll="onPanelScroll"
  >
      <div class="panel-head">
        <strong>问问 kwiki</strong>
        <div class="panel-actions">
          <button type="button" class="icon" aria-label="最大化到会话页" @click="maximize"><i class="i-lucide-maximize-2" aria-hidden="true"></i></button>
          <button type="button" class="icon" aria-label="最小化问答面板" @click="open = false"><i class="i-lucide-minus" aria-hidden="true"></i></button>
        </div>
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

    <RetrievalActivity
      v-if="stream.activitySteps.length"
      :steps="stream.activitySteps"
      :running="!stream.terminated"
      :failed="!!stream.error"
      data-testid="agentic-progress"
    />

    <p v-if="stream.answer" data-testid="agentic-answer">{{ stream.answer }}</p>
    <details v-if="stream.reasoning.length" class="reasoning">
      <summary>{{ stream.reasoning[stream.reasoning.length - 1] }}</summary>
      <div ref="reasoningPanel" class="reasoning-scroll" @scroll="onReasoningScroll"><p v-for="(item, index) in stream.reasoning" :key="index">{{ item }}</p></div>
      <button v-if="showNewReasoning" type="button" class="follow-tail" @click="followReasoning">有新解释 · 回到底部</button>
    </details>

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
    <button v-if="showNewContent" type="button" class="follow-tail" @click="followLatest">有新内容 · 回到底部</button>
  </section>

  <button type="button" class="ask" :aria-expanded="open" @click="open = !open">
    <i class="i-lucide-sparkles" aria-hidden="true"></i>问问 kwiki
  </button>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { initialState, openStream, reduce, type CitationEntry, type StreamState } from '../sse';
import RetrievalActivity from './RetrievalActivity.vue';

const open = ref(false);
const question = ref('');
const stream = ref<StreamState>(initialState());
const panel = ref<HTMLElement | null>(null);
const reasoningPanel = ref<HTMLElement | null>(null);
const followTail = ref(true);
const showNewContent = ref(false);
const reasoningFollowTail = ref(true);
const showNewReasoning = ref(false);
let dispose: (() => void) | null = null;

const terminatedNoEvidence = computed(
  () => stream.value.terminated && !stream.value.answer && !stream.value.error && !stream.value.message,
);

function ask() {
  if (!question.value.trim()) {
    return;
  }
  dispose?.();
  stream.value = initialState();
  followTail.value = true;
  showNewContent.value = false;
  reasoningFollowTail.value = true;
  showNewReasoning.value = false;
  dispose = openStream(question.value, (frame) => {
    stream.value = reduce(stream.value, frame);
  });
}

function onPanelScroll() {
  const element = panel.value;
  if (!element) return;
  const atBottom = element.scrollHeight - element.scrollTop - element.clientHeight <= 48;
  followTail.value = atBottom;
  if (atBottom) showNewContent.value = false;
}

function followLatest() {
  const element = panel.value;
  if (!element) return;
  followTail.value = true;
  showNewContent.value = false;
  element.scrollTo({ top: element.scrollHeight, behavior: 'smooth' });
}

function onReasoningScroll() {
  const element = reasoningPanel.value;
  if (!element) return;
  const atBottom = element.scrollHeight - element.scrollTop - element.clientHeight <= 32;
  reasoningFollowTail.value = atBottom;
  if (atBottom) showNewReasoning.value = false;
}

function followReasoning() {
  const element = reasoningPanel.value;
  if (!element) return;
  reasoningFollowTail.value = true;
  showNewReasoning.value = false;
  element.scrollTo({ top: element.scrollHeight, behavior: 'smooth' });
}

watch(
  () => [stream.value.answer.length, stream.value.progressItems.length, stream.value.reasoning.length, stream.value.terminated],
  async () => {
    await nextTick();
    const element = panel.value;
    if (!element) return;
    if (followTail.value) element.scrollTop = element.scrollHeight;
    else showNewContent.value = true;
    const reasoningElement = reasoningPanel.value;
    if (reasoningElement) {
      if (reasoningFollowTail.value) reasoningElement.scrollTop = reasoningElement.scrollHeight;
      else showNewReasoning.value = true;
    }
  },
);

function maximize() {
  open.value = false;
  window.location.hash = '#/conversations?compose=1';
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
.panel-actions {
  display: flex;
  gap: 2px;
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
ol li {
  display: grid;
  gap: 3px;
}
.progress-main {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.progress-main span,
ol li small {
  color: #7a877f;
  font-size: 11px;
}
.status-succeeded { color: #227c4d; }
.status-failed { color: #ad4c4c; }
.status-cancelled { color: #8a6c42; }
.reasoning {
  margin-top: 10px;
  color: #66756d;
  font-size: 12px;
}
.reasoning-scroll {
  max-height: 220px;
  overflow: auto;
  overscroll-behavior: contain;
}
.reasoning p {
  margin: 8px 0 0;
  white-space: pre-wrap;
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
.follow-tail {
  position: sticky;
  bottom: 0;
  display: block;
  margin: 10px auto 0;
  padding: 6px 10px;
  border: 1px solid #b8dfc8;
  border-radius: 99px;
  background: #f0faf4;
  color: #187b4a;
  cursor: pointer;
  font-size: 12px;
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
