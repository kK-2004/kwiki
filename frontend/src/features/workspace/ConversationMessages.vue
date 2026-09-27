<template>
  <div class="chat-surface" :class="{ compact }">
    <section ref="scrollArea" class="messages" aria-label="聊天消息" @scroll="trackScroll" @click="openCitation">
      <div v-if="store.loading" class="chat-loading">正在加载会话…</div>
      <div v-else-if="!store.history.length && !store.running && !store.answer" class="welcome"><span class="welcome-mark"><i class="i-lucide-sparkles" /></span><p class="welcome-kicker">你的知识，随问随得</p><h1>有什么可以帮你？</h1><p>从知识库中寻找答案，把想法变成行动。</p><div v-if="!compact" class="suggestions"><button v-for="question in suggestions" :key="question.title" @click="store.query = question.query"><i :class="question.icon" /><strong>{{ question.title }}</strong><span>{{ question.subtitle }}</span><i class="i-lucide-arrow-up-right" /></button></div></div>
      <div ref="messageList" class="message-list"><article v-for="message in store.history" :key="message.id" class="message" :class="message.role === 'USER' ? 'user' : 'assistant'"><span class="message-avatar"><i :class="message.role === 'USER' ? 'i-lucide-user-round' : 'i-lucide-sparkles'" /></span><div class="message-content"><small>{{ message.role === 'USER' ? '你' : 'kwiki' }}</small><div v-if="message.role === 'USER'" class="user-text">{{ message.content }}</div><template v-else><RetrievalActivity v-if="store.runActivities[message.requestId ?? '']?.length" :steps="store.runActivities[message.requestId ?? '']" :running="false" :failed="false" /><div class="markdown" v-html="renderAnswer(message.content, store.runCitations[message.requestId ?? ''] ?? [])" /><div v-if="numberedCitations(store.runCitations[message.requestId ?? ''] ?? []).length" class="citation-list"><button v-for="entry in numberedCitations(store.runCitations[message.requestId ?? ''] ?? [])" :key="entry.citation.childChunkKey" type="button" :data-reference-index="entry.index" @click="goToCitation(entry.citation)"><span>[{{ entry.index }}]</span> {{ entry.citation.displayName || entry.citation.headingPath }}</button></div></template></div></article>
      <article v-if="store.running || store.answer" class="message assistant"><span class="message-avatar"><i class="i-lucide-sparkles" /></span><div class="message-content"><small>kwiki</small><RetrievalActivity v-if="store.state.activitySteps.length" :steps="store.state.activitySteps" :running="store.running && !store.state.terminated" :failed="!!store.state.error" /><div v-if="store.running && !store.answer && !store.state.activitySteps.length" class="generating"><span class="pulse" />{{ progressLabel }}</div><div v-if="store.answer" class="markdown" v-html="renderAnswer(store.answer, store.state.citations)" /><div v-if="numberedCitations(store.state.citations).length" class="citation-list"><button v-for="entry in numberedCitations(store.state.citations)" :key="entry.citation.childChunkKey" type="button" :data-reference-index="entry.index" @click="goToCitation(entry.citation)"><span>[{{ entry.index }}]</span> {{ entry.citation.displayName || entry.citation.headingPath }}</button></div></div></article>
      <div v-if="store.error || store.historyError" class="ui-error" role="alert">{{ store.historyError || store.error }}<button class="ui-button" @click="retry">{{ store.historyError ? '重新加载' : '重新提问' }}</button></div><div class="messages-bottom" aria-hidden="true"></div></div>
    </section>
    <form class="composer-area" @submit.prevent="store.send"><div class="composer-box"><textarea v-model="store.query" aria-label="输入问题" placeholder="向知识库提问…" rows="2" :disabled="store.loading" @keydown="onKeydown" /><div class="composer-toolbar"><button type="button" class="scope-trigger" @click="scopeOpen = true"><i class="i-lucide-library" /> {{ scopeLabel }}<i class="i-lucide-chevron-down" /></button><button v-if="store.running" type="button" class="send-button" aria-label="停止生成" @click="store.cancel"><i class="i-lucide-square" /></button><button v-else type="submit" class="send-button" aria-label="发送问题" :disabled="!store.query.trim() || store.loading || !!store.historyError"><i class="i-lucide-arrow-up" /></button></div></div><p class="composer-note">回答基于你有权访问的知识内容。<span v-if="!compact"> Enter 发送 · Shift + Enter 换行</span></p></form>
    <KnowledgeScopeDialog v-if="scopeOpen" :compact="!!props.compact" @close="scopeOpen = false" />
  </div>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useConversationStore } from './conversationStore'; import KnowledgeScopeDialog from './KnowledgeScopeDialog.vue'; import { renderMarkdown } from '../wiki/components/render'; import RetrievalActivity from '../wiki/components/RetrievalActivity.vue'; import { useFollowTail } from '../wiki/components/followTail'; import { api } from '../wiki/api'; import { showMissingWikiToast } from '../wiki/toast'; import type { CitationEntry } from '../wiki/sse';
const props = defineProps<{ compact?: boolean }>(); const store = useConversationStore(); const router = useRouter(); const scrollArea = ref<HTMLElement>(); const messageList = ref<HTMLElement>(); const tail = useFollowTail(); const scopeOpen = ref(false);
function numberedCitations(citations: CitationEntry[]) { return citations.map((citation, index) => ({ citation, index })); }
function renderAnswer(content: string, citations: CitationEntry[]) { const entries = new Map(numberedCitations(citations).map(entry => [entry.citation.citationId ?? `P${entry.index}`, entry])); return renderMarkdown(content, { citationTarget: id => { const entry = entries.get(id); const citation = entry?.citation; return entry && citation?.kbId && citation.resourceType === 'PAGE' ? { label: `[${entry.index}]`, referenceIndex: entry.index, kbId: citation.kbId, pageId: citation.resourceId, chunkKey: citation.childChunkKey } : null; } }); }
async function goToCitation(citation: CitationEntry) {
  if (!citation.kbId || citation.resourceType !== 'PAGE') return;

  try {
    await api.json(`/knowledge-bases/${citation.kbId}/pages/${citation.resourceId}`);
  } catch {
    showMissingWikiToast();
    return;
  }

  await router.push({
    name: 'workspace',
    params: { kbId: citation.kbId, pageId: citation.resourceId },
    query: { chunk: citation.childChunkKey },
  });
}
function openCitation(event: MouseEvent) { const marker = (event.target as HTMLElement).closest<HTMLElement>('.kwiki-citation[data-reference-index]'); if (!marker) return; const scope = marker.closest('.message-content'); const reference = scope?.querySelector<HTMLElement>(`.citation-list [data-reference-index="${marker.dataset.referenceIndex}"]`); if (!reference) return; reference.scrollIntoView({ behavior: 'smooth', block: 'center' }); reference.classList.remove('citation-flash'); requestAnimationFrame(() => reference.classList.add('citation-flash')); window.setTimeout(() => reference.classList.remove('citation-flash'), 2200); }
const scopeLabel = computed(() => { const count = store.selectedKnowledgeBaseIds.length + store.selectedPageIds.length; return count ? `已选择 ${count} 项` : '可访问的知识库'; });
const suggestions = [{ title: '快速了解', subtitle: '概括文档中的关键内容', icon: 'i-lucide-book-open', query: '请概括知识库文档中的关键内容。' }, { title: '找到答案', subtitle: '查找与你的问题相关的信息', icon: 'i-lucide-search', query: '我想了解知识库中关于' }, { title: '梳理步骤', subtitle: '将说明整理成行动清单', icon: 'i-lucide-list-checks', query: '请根据知识库内容，整理一份可执行的操作步骤。' }];
const progressLabel = computed(() => {
  const last = store.state.activitySteps.at(-1);
  return last?.summary || '正在寻找相关内容…';
});
/**
 * 消息区跟随底部由共享 follow-tail 状态机驱动：
 * 用户上滚离底即暂停，回到阈值内自动恢复；token、检索步骤、
 * 引用与媒体等任何高度变化（ResizeObserver + 底部哨兵所在内容）
 * 只在 FOLLOWING 时合并写入滚动位置。
 */
onMounted(() => { if (scrollArea.value) tail.attach(scrollArea.value, messageList.value ?? undefined); });
onBeforeUnmount(() => tail.detach());
function trackScroll() { tail.handleUserScroll(); }
watch(() => [store.answer, store.history.length, store.running], async () => { await tail.notifyContentChanged(); });
watch(() => store.sessionId, () => { tail.scrollToBottom(); });
function onKeydown(e: KeyboardEvent) { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); store.send(); } }
function retry() { if (store.historyError) void store.select(store.sessionId); else { store.query = store.lastQuery; store.state.error = null; } }
</script>
<style scoped>
.chat-surface { min-height: 0; flex: 1; display: flex; flex-direction: column; position: relative; }
.messages { flex: 1; min-height: 0; overflow: auto; padding: 32px 36px 18px; scrollbar-width: thin; }
.welcome { max-width: 760px; margin: clamp(18px, 8vh, 100px) auto 35px; text-align: center; }
.welcome-mark { display: inline-flex; background: var(--k-green-soft); padding: 16px; border-radius: 16px; color: var(--k-green-deep); font-size: 29px; }
.welcome-kicker { margin: 24px 0 12px; color: var(--k-muted); font-size: 12px; letter-spacing: 1px; }
.welcome h1 { font-size: 28px; letter-spacing: -0.5px; font-weight: 600; margin: 0 0 14px; color: var(--k-ink); }
.welcome > p:last-of-type { color: var(--k-muted); font-size: 13px; }
.suggestions { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 38px; text-align: left; }
.suggestions button { position: relative; display: flex; flex-direction: column; gap: 12px; align-items: flex-start; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas); color: var(--k-ink); padding: 20px 16px; cursor: pointer; }
.suggestions button:hover { border-color: var(--k-line-strong); box-shadow: var(--k-shadow-sm); }
.suggestions button > i:first-child { color: var(--k-green-deep); font-size: 19px; }
.suggestions strong { font-weight: 550; font-size: 13px; }
.suggestions span { font-size: 11px; color: var(--k-muted); }
.suggestions button > i:last-child { position: absolute; right: 13px; top: 20px; color: var(--k-faint); }
.message-list { max-width: 800px; margin: 0 auto; }
.message { display: flex; gap: 13px; margin: 0 0 30px; }
.message-avatar { display: flex; align-items: center; justify-content: center; width: 30px; height: 30px; flex-shrink: 0; border-radius: var(--k-r); background: var(--k-green-soft); color: var(--k-green-deep); }
.user .message-avatar { background: var(--k-surface-hover); color: var(--k-muted); }
.message-content { min-width: 0; flex: 1; line-height: 1.85; }
.message-content > small { display: block; color: var(--k-muted); font-size: 11px; margin: 2px 0 8px; }
.user-text { display: inline-block; white-space: pre-wrap; overflow-wrap: anywhere; padding: 10px 16px; border-radius: var(--k-r-lg); background: var(--k-surface-hover); color: var(--k-ink); }
/* 助手回答的排版与颜色统一由全局 markdown.css 提供，这里只保留换行规则 */
.markdown { overflow-wrap: anywhere; }
.generating { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--k-muted); margin-bottom: 12px; }
.pulse { width: 6px; height: 6px; background: var(--k-green); border-radius: 50%; animation: pulse 1.2s infinite; }
@keyframes pulse { 50% { opacity: .3; } }
.retrieval { font-size: 12px; color: var(--k-muted); margin: 18px 0; }
.retrieval summary { cursor: pointer; }
.retrieval p { padding-left: 12px; border-left: 2px solid var(--k-line); }
.citations { display: grid; gap: 7px; font-size: 12px; margin: 16px 0; }
.citations strong { color: var(--k-muted); font-weight: 500; }
.citations button { display: flex; align-items: center; gap: 8px; border: 1px solid var(--k-line); background: var(--k-surface); border-radius: var(--k-r); padding: 10px; text-align: left; color: var(--k-ink-2); cursor: pointer; }
.citations button span { border-radius: var(--k-r-sm); background: var(--k-surface-hover); padding: 2px 5px; }
.citations button i { margin-left: auto; }
.ui-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
/* 输入区顶部渐隐：由画布色半透明过渡到实色，两种主题通用 */
.composer-area { flex-shrink: 0; padding: 12px 36px 18px; background: linear-gradient(color-mix(in srgb, var(--k-canvas) 75%, transparent), var(--k-canvas) 25%); }
.composer-box { max-width: 800px; margin: 0 auto; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas); box-shadow: var(--k-shadow); padding: 15px; }
.composer-box:focus-within { border-color: var(--k-line-strong); box-shadow: var(--k-shadow), var(--k-focus-ring); }
.composer-box textarea { width: 100%; border: 0; outline: none; box-shadow: none; resize: none; background: transparent; color: var(--k-ink); font: inherit; line-height: 1.7; padding: 0; max-height: 150px; }
.composer-box textarea::placeholder { color: var(--k-faint); }
.composer-toolbar { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; }
.composer-toolbar > span { font-size: 11px; color: var(--k-muted); display: flex; align-items: center; gap: 6px; }
.send-button { width: 32px; height: 32px; display: grid; place-items: center; border: 0; border-radius: var(--k-r); background: var(--k-primary); color: var(--k-on-primary); font-size: 17px; cursor: pointer; }
.send-button:hover:not(:disabled) { background: var(--k-primary-hover); }
.composer-note { text-align: center; font-size: 10px; color: var(--k-faint); margin: 12px 0 0; line-height: 1.6; }
.compact .messages { padding: 18px; }
.compact .welcome { margin: 20px auto; }
.compact .welcome h1 { font-size: 22px; }
.compact .welcome-mark { padding: 12px; font-size: 22px; }
.compact .welcome-kicker { margin-top: 16px; }
.compact .composer-area { padding: 12px 16px; }
.compact .composer-note { font-size: 9px; }
.compact .message { gap: 8px; }
.compact .message-content { font-size: 13px; }
.compact .markdown { font-size: 13px; }
.citation-overlay { position: absolute; inset: 0; background: var(--k-overlay); z-index: 10; display: grid; place-items: center; padding: 20px; }
.citation-dialog { padding: 24px; background: var(--k-canvas); border: 1px solid var(--k-line); border-radius: var(--k-r-lg); box-shadow: var(--k-shadow-float); max-height: 85%; overflow: auto; width: min(600px, 100%); }
.citation-dialog header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.citation-dialog h3 { font-size: 16px; color: var(--k-ink); }
.citation-dialog p { white-space: pre-wrap; line-height: 1.8; color: var(--k-ink-2); }
.chat-loading { text-align: center; color: var(--k-muted); margin-top: 40px; }
@media (max-width: 1000px) { .messages { padding: 24px; } .suggestions { gap: 8px; } .suggestions button { padding: 16px 12px; } .suggestions span { line-height: 1.6; } .composer-area { padding: 12px 24px 18px; } }
@media (max-width: 600px) { .messages { padding: 18px; } .welcome h1 { font-size: 24px; } .suggestions { grid-template-columns: 1fr; } .suggestions button { display: grid; grid-template-columns: 22px 1fr; gap: 6px 10px; } .suggestions span { grid-column: 2; } .composer-area { padding: 10px 14px; } .composer-note span { display: none; } }
</style>
<style scoped>
/* 聊天气泡内的间距与标题字号比 Wiki 正文更紧凑；颜色不在此定义 */
.markdown :deep(p) { margin: 8px 0 14px; }
.markdown :deep(h1), .markdown :deep(h2), .markdown :deep(h3) { font-size: 18px; margin: 24px 0 12px; }
.markdown :deep(h3) { font-size: 16px; }
.markdown :deep(ul), .markdown :deep(ol) { padding-left: 22px; }
.markdown :deep(li) { margin: 7px 0; }
.markdown :deep(.table-scroll) { overflow: auto; }
.markdown :deep(.kwiki-citation) { display: inline-block; margin: 0 2px; padding: 0 6px; border: 0; border-radius: var(--k-r-sm); background: var(--k-green-soft); color: var(--k-green-deep); font: inherit; font-size: .88em; line-height: 1.6; cursor: pointer; }
.markdown :deep(.kwiki-citation:hover) { background: color-mix(in srgb, var(--k-green) 24%, transparent); }
.citation-list { display: grid; gap: 5px; margin-top: 14px; }
.citation-list button { width: max-content; max-width: 100%; border: 0; background: none; padding: 2px 0; color: var(--k-muted); font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.citation-list button:hover { color: var(--k-green-deep); text-decoration: underline; text-underline-offset: 3px; }
.citation-list span { font-variant-numeric: tabular-nums; }
.citation-list button.citation-flash { animation: citation-flash 2.2s ease-out; }
@keyframes citation-flash { 0%, 35% { background: var(--k-warn-soft); box-shadow: 0 0 0 4px var(--k-warn-soft); } 100% { background: transparent; box-shadow: none; } }
.scope-trigger { border: 0; background: none; padding: 5px 0; font: inherit; font-size: 11px; color: var(--k-muted); display: flex; align-items: center; gap: 6px; cursor: pointer; }
.scope-trigger:hover { color: var(--k-ink); }
.scope-trigger i:last-child { font-size: 10px; }
</style>
