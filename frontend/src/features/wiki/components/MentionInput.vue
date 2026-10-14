<script setup lang="ts">
/**
 * 支持 @ 提及的评论输入框：输入 @ 后按用户名搜索本页可见成员，
 * 方向键/回车选择；发表时只提交正文中仍保留的提及。
 * Cmd/Ctrl + Enter 直接提交。
 */
import { nextTick, onBeforeUnmount, ref } from 'vue';
import { api } from '../api';

interface MentionCandidate { id: number; username: string; displayName?: string }

const props = defineProps<{ kbId: number; pageId: number; placeholder?: string; label?: string; disabled?: boolean }>();
const emit = defineEmits<{ submit: [] }>();
const draft = defineModel<string>({ default: '' });

const editor = ref<HTMLTextAreaElement | null>(null);
const candidates = ref<MentionCandidate[]>([]);
const selected = ref<MentionCandidate[]>([]);
const open = ref(false); const activeIndex = ref(0); const mentionStart = ref(-1);
let timer: ReturnType<typeof setTimeout> | undefined; let request = 0; let controller: AbortController | null = null;

function onInput() {
  const position = editor.value?.selectionStart ?? draft.value.length;
  const match = /(?:^|\s)@([\w.-]*)$/.exec(draft.value.slice(0, position));
  if (!match) { close(); return; }
  mentionStart.value = position - match[1].length - 1;
  activeIndex.value = 0;
  open.value = true;
  if (timer) clearTimeout(timer);
  controller?.abort();
  const current = ++request;
  timer = setTimeout(async () => {
    const abort = new AbortController(); controller = abort;
    try {
      const rows = await api.json<MentionCandidate[]>(`/knowledge-bases/${props.kbId}/pages/${props.pageId}/mention-candidates?q=${encodeURIComponent(match[1])}&limit=20`, abort.signal);
      if (current === request) candidates.value = rows;
    } catch (cause) {
      if ((cause as { name?: string })?.name !== 'AbortError' && current === request) candidates.value = [];
    }
  }, 180);
}

function onKeydown(event: KeyboardEvent) {
  if (open.value) {
    if (event.key === 'ArrowDown') { event.preventDefault(); activeIndex.value = Math.min(activeIndex.value + 1, Math.max(candidates.value.length - 1, 0)); return; }
    if (event.key === 'ArrowUp') { event.preventDefault(); activeIndex.value = Math.max(activeIndex.value - 1, 0); return; }
    if (event.key === 'Enter' && candidates.value[activeIndex.value]) { event.preventDefault(); choose(candidates.value[activeIndex.value]); return; }
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); return; }
  }
  if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); emit('submit'); }
}

function choose(candidate: MentionCandidate) {
  const position = editor.value?.selectionStart ?? draft.value.length;
  const start = mentionStart.value >= 0 ? mentionStart.value : Math.max(0, position - candidate.username.length - 1);
  draft.value = `${draft.value.slice(0, start)}@${candidate.username} ${draft.value.slice(position)}`;
  if (!selected.value.some(item => item.id === candidate.id)) selected.value.push(candidate);
  close();
  void nextTick(() => { editor.value?.focus(); const cursor = start + candidate.username.length + 2; editor.value?.setSelectionRange(cursor, cursor); });
}

function insertAt() {
  const position = editor.value?.selectionStart ?? draft.value.length;
  draft.value = `${draft.value.slice(0, position)}@${draft.value.slice(position)}`;
  void nextTick(() => { editor.value?.focus(); editor.value?.setSelectionRange(position + 1, position + 1); onInput(); });
}

function close() { open.value = false; candidates.value = []; mentionStart.value = -1; controller?.abort(); }

/** 正文中仍保留的提及用户 id。 */
function mentionUserIds(): number[] {
  return selected.value.filter(item => draft.value.includes(`@${item.username}`)).map(item => item.id);
}
function reset() { draft.value = ''; selected.value = []; close(); }
function focus() { editor.value?.focus(); }

onBeforeUnmount(() => { if (timer) clearTimeout(timer); controller?.abort(); });
defineExpose({ mentionUserIds, reset, focus, insertAt });
</script>

<template>
  <div class="mention-input">
    <textarea
      ref="editor"
      v-model="draft"
      :aria-label="label ?? '评论内容'"
      :placeholder="placeholder ?? '写下你的评论，输入 @ 提醒成员'"
      :disabled="disabled"
      rows="3"
      @input="onInput"
      @keydown="onKeydown"
      @blur="close"
    ></textarea>
    <ul v-if="open" class="mention-menu" role="listbox" aria-label="提及用户">
      <li
        v-for="(candidate, index) in candidates"
        :key="candidate.id"
        :class="{ active: index === activeIndex }"
        role="option"
        :aria-selected="index === activeIndex"
        @mousedown.prevent="choose(candidate)"
      >
        <strong>@{{ candidate.username }}</strong><span>{{ candidate.displayName || '' }}</span>
      </li>
      <li v-if="!candidates.length" class="mention-empty">没有匹配的可见用户</li>
    </ul>
  </div>
</template>

<style scoped>
.mention-input { position: relative; }
textarea {
  display: block;
  width: 100%;
  box-sizing: border-box;
  min-height: 64px;
  max-height: 160px;
  resize: none;
  padding: 8px 10px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  outline: none;
  background: var(--k-canvas);
  color: var(--k-ink);
  font: inherit;
  font-size: 13px;
  line-height: 1.6;
}
textarea:focus { border-color: var(--k-green); box-shadow: var(--k-focus-ring); }
.mention-menu {
  position: absolute;
  z-index: 5;
  left: 0;
  right: 0;
  top: calc(100% + 4px);
  max-height: 180px;
  overflow: auto;
  margin: 0;
  padding: 4px;
  list-style: none;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-canvas);
  box-shadow: var(--k-shadow);
}
.mention-menu li { display: flex; gap: 8px; align-items: baseline; padding: 6px 8px; border-radius: var(--k-r-sm); font-size: 12px; cursor: pointer; }
.mention-menu li span { color: var(--k-muted); }
.mention-menu li.active, .mention-menu li:hover { background: var(--k-surface-hover); }
.mention-menu .mention-empty { color: var(--k-muted); cursor: default; }
</style>
