<template>
  <article v-if="page" data-testid="page-reader" class="reader">
    <a v-if="parentTitle" class="back" href="#" @click.prevent="selectParent">
      <i class="i-lucide-arrow-left" aria-hidden="true"></i>{{ parentTitle }}
    </a>
    <div class="title-row">
      <div class="title-copy">
        <h1 class="page-title">{{ displayTitle }}</h1>
        <p class="meta" data-testid="page-meta">
          <span class="updated"><i class="i-lucide-clock" aria-hidden="true"></i>更新于 {{ formattedCreatedAt }}</span>
        </p>
      </div>
    </div>
    <div v-if="sourceFormat" class="source-tabs" role="tablist" aria-label="阅读视图切换">
      <button type="button" role="tab" class="source-tab" :aria-selected="readerTab === 'source'" @click="readerTab = 'source'">{{ sourceFormat }} 源文件</button>
      <button type="button" role="tab" class="source-tab" :aria-selected="readerTab === 'parsed'" @click="readerTab = 'parsed'">解析文本</button>
    </div>
    <div v-if="anchorResolution" class="anchor-notice" :class="{ warning: !anchorResolution.currentRevision }" role="status">
      <strong>{{ anchorResolution.status === 'RELOCATED' ? '已定位划词' : '划词位置提示' }}</strong>
      <span>{{ anchorResolution.message }}</span>
    </div>
    <p v-if="chunkNotice" role="status" class="anchor-notice">{{ chunkNotice }}</p>
    <MediaMountRegion
      v-show="!sourceFormat || readerTab === 'parsed'"
      ref="contentRef"
      class="markdown article"
      data-testid="page-content"
      :html="resolvedHtml"
      :resolver="mediaResolver"
      :kb-id="props.kbId"
      @mouseup="captureSelection"
    />
    <SourcePreview
      v-if="sourceFormat && readerTab === 'source' && props.kbId && props.pageId"
      class="source-preview-area"
      :kb-id="props.kbId"
      :page-id="props.pageId"
      :source="page.sourceDocument!"
    />
    <p v-if="selectionNotice" class="selection-notice" role="status">{{ selectionNotice }}</p>
    <div v-if="selectionText" class="selection-toolbar" :style="selectionToolbarStyle">
      <button type="button" @click="questionOpen = true">问 AI</button>
      <button type="button" @click="startComment">评论</button>
    </div>
    <div v-if="questionOpen" class="selection-modal" role="dialog" aria-label="划词问 AI">
      <div class="selection-modal-card"><header><strong>问 AI</strong><button type="button" aria-label="关闭" @click="closeQuestion">×</button></header><p class="selection-quote">“{{ selectionText }}”</p><textarea v-model="question" placeholder="针对划词内容提问…" @keydown.enter.exact.prevent="askQuestion"></textarea><div class="selection-actions"><button type="button" @click="closeQuestion">取消</button><button v-if="asking" type="button" @click="stopQuestion">停止</button><button v-else type="button" :disabled="!question.trim()" @click="askQuestion">{{ questionError ? '重试' : '提问' }}</button></div><p v-if="questionError" class="state">{{ questionError }}</p><div v-if="answer" class="selection-answer">{{ answer }}</div></div>
    </div>
    <footer v-if="(backlinks ?? []).length" class="provenance" data-testid="page-backlinks">
      <span>被链接</span>
      <div class="prov-links">
        <a v-for="link in backlinks" :key="link.id" class="link" href="#">{{ link.title }}</a>
      </div>
    </footer>
  </article>
  <p v-else-if="error && store.pageErrorStatus !== 404" class="state" data-testid="page-error">{{ errorText }}</p>
  <p v-else-if="!error" class="state" data-testid="page-empty">请选择左侧页面</p>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useWikiStore, normalizedError } from '../store';
import { api, getAuthToken, setAuthToken } from '../api';
import { locateChunk, clearCitationHighlight, type ChunkLocation } from './locateChunk';
import type { CitationEntry } from '../sse';
import type { TreeNodeDto } from '../api';
import MediaMountRegion from './MediaMountRegion.vue';
import SourcePreview from './SourcePreview.vue';
import { createMediaPreviewResolver } from './mediaResolver';
import type { MediaSourceResolver } from '@kk-2004/ui-components/components/KMediaViewer';
const props = defineProps<{
  chunkKey?: string;
  breadcrumb?: string;
  title?: string;
  backlinks?: Array<{ id: number; title: string }>;
  kbId?: number;
  pageId?: number;
  anchorResolution?: { anchorId: number; status: string; quote: string; message: string; currentRevision: boolean } | null;
}>();
const emit = defineEmits<{ comment: [text: string] }>();

const store = useWikiStore();
const route = useRoute();
const router = useRouter();
const page = computed(() => store.page);
const formattedCreatedAt = computed(() => formatDateTime(page.value?.createdAt ?? ''));
const chunkNotice = ref('');
const contentRef = ref<InstanceType<typeof MediaMountRegion> | null>(null);

function formatDateTime(value: string) {
  const match = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(value);
  return match ? `${match[1]} ${match[2]}` : value;
}

/** PDF/DOCX 导入页的来源/解析文本页签；其他页面保持单一阅读视图。 */
const sourceFormat = computed(() => {
  const format = page.value?.sourceDocument?.format;
  return format === 'PDF' || format === 'DOCX' ? format : null;
});
const readerTab = ref<'parsed' | 'source'>('parsed');
watch(() => [page.value?.createdAt, page.value?.revisionNo], () => { readerTab.value = 'parsed'; });

/**
 * 引用定位是一次性事务：以递增票据使旧任务失效，
 * 目标键为 (chunkKey, 页面内容, 页Id)；目标改变或组件卸载时
 * 取消未完成的解析并清理已有高亮。
 */
let citationTicket = 0;
let citationLocation: ChunkLocation | null = null;

function invalidateCitation() {
  citationTicket++;
  citationLocation?.cleanup();
  citationLocation = null;
  clearCitationHighlight();
}

async function consumeChunkQuery() {
  const { chunk: _chunk, ...query } = route.query;
  await router.replace({ query });
}

watch(() => [props.chunkKey, page.value, props.pageId], async () => {
  const run = ++citationTicket;
  const starting = Boolean(props.chunkKey && page.value);
  // 定位成功或已展示最终错误后消费路由参数会使 chunkKey 变为空：
  // 此时保留刚应用的高亮/状态提示（由超时/下一次目标清理），
  // 只在有新目标或页面切换时清理。
  if (starting || !page.value) {
    citationLocation?.cleanup();
    citationLocation = null;
    clearCitationHighlight();
    chunkNotice.value = '';
  }
  if (!starting || props.pageId == null) return;
  const chunkKey = props.chunkKey;
  if (!chunkKey) return;
  try {
    const citation = await api.json<CitationEntry>(`/citations/${encodeURIComponent(chunkKey)}`);
    if (run !== citationTicket || citation.resourceId !== props.pageId) return;
    // 引用命中的是解析文本；若正在查看源文件页签则切回再定位。
    readerTab.value = 'parsed';
    // 就绪握手：等待正文提交与异步媒体挂载（MediaMountRegion 在下一个 tick reconcile）。
    await nextTick();
    await nextTick();
    if (run !== citationTicket) return;
    const container = contentRef.value?.$el;
    if (!(container instanceof Element)) return;
    const location = locateChunk(container, { excerpt: citation.excerpt, charStart: citation.charStart });
    if (location.status === 'located') {
      citationLocation = location;
    } else {
      location.cleanup();
      chunkNotice.value = `页面内容可能已更新，未能精确定位原片段。引用摘要：“${citation.excerpt}”`;
    }
    await consumeChunkQuery();
  } catch {
    if (run !== citationTicket) return;
    chunkNotice.value = '原片段已失效或无权访问，无法定位。';
    await consumeChunkQuery();
  }
}, { flush: 'post', immediate: true });

const error = computed(() => store.pageError);
const errorText = computed(() => normalizedError(error.value));

/** 服务端渲染的 HTML 已由 MarkdownPort 净化。 */
const sanitizedHtml = computed(() => page.value?.html ?? '');

/**
 * 渲染后 HTML 中的媒体由共享查看器通过
 * MediaMountRegion（标记 → 每种媒体一个存活实例）展示。attachment://
 * 引用在客户端被解析为全新的授权链接；无法
 * 解析的则保留稳定引用，而非损坏的源 URL。已签名的 URL
 * 永远不会进入持久化内容。
 */
const mediaResolver = ref<MediaSourceResolver>(() => null);
watch(
  () => props.kbId,
  kbId => { mediaResolver.value = createMediaPreviewResolver(kbId); },
  { immediate: true },
);
const attachmentHrefUrls = ref<Record<string, string>>({});
const resolvedHtml = computed(() =>
  sanitizedHtml.value.replace(/href="(attachment:\/\/[^"]+)"/g, (match, ref: string) => {
    const url = attachmentHrefUrls.value[ref];
    return url && url !== 'unavailable' ? `href="${url}"` : match;
  }));

watch(sanitizedHtml, (html) => {
  const refs = [...html.matchAll(/href="(attachment:\/\/[^"]+)"/g)].map(match => match[1]);
  for (const ref of refs) {
    if (attachmentHrefUrls.value[ref] !== undefined || !props.kbId) continue;
    attachmentHrefUrls.value = { ...attachmentHrefUrls.value, [ref]: '' };
    const uuid = ref.slice('attachment://'.length);
    void fetch(`/api/v1/knowledge-bases/${props.kbId}/attachments/${encodeURIComponent(uuid)}/download-url`, {
      headers: getAuthToken() ? { Authorization: `Bearer ${getAuthToken()}` } : {},
    })
      .then(response => (response.ok ? response.json() : null))
      .then((body: { data?: { url?: string } } | null) => {
        attachmentHrefUrls.value = {
          ...attachmentHrefUrls.value,
          [ref]: body?.data?.url || 'unavailable',
        };
      })
      .catch(() => {
        attachmentHrefUrls.value = { ...attachmentHrefUrls.value, [ref]: 'unavailable' };
      });
  }
}, { immediate: true });

/** 标题与路径回退到所选页面在树中的条目。 */
function findPath(nodes: TreeNodeDto[], id: number, trail: TreeNodeDto[]): TreeNodeDto[] | null {
  for (const node of nodes) {
    const next = [...trail, node];
    if (node.id === id) {
      return next;
    }
    const found = findPath(node.children ?? [], id, next);
    if (found) {
      return found;
    }
  }
  return null;
}
const selectedPath = computed(() =>
  store.selectedPageId == null ? null : findPath(store.tree, store.selectedPageId, []),
);
const displayTitle = computed(() => props.title ?? selectedPath.value?.at(-1)?.title ?? '');
const selectionText = ref(''); const selectionNotice = ref(''); const selectionToolbarStyle = ref<Record<string, string>>({}); const questionOpen = ref(false); const question = ref(''); const answer = ref(''); const asking = ref(false); const questionError = ref(''); const questionController = ref<AbortController | null>(null);
const parentTitle = computed(() => {
  const path = selectedPath.value;
  if (!path || path.length < 2) {
    return null;
  }
  return path.at(-2)?.title ?? null;
});

function selectParent() {
  const path = selectedPath.value;
  if (!path || path.length < 2) {
    return;
  }
  const parent = path.at(-2);
  if (parent) {
    if (parent.nodeType === 'PAGE') {
      store.selectPage(parent.id);
      const match = window.location.hash.match(/#\/?knowledge-bases\/(\d+)/);
      if (match) window.location.hash = `#/knowledge-bases/${match[1]}/${parent.id}`;
    } else {
      store.selectPage(null);
    }
  }
}
function captureSelection(event: MouseEvent) {
  const selection = window.getSelection(); const text = selection?.toString().trim() ?? '';
  if (!text || !(event.target as HTMLElement)?.closest('.article')) return;
  selectionNotice.value = '';
  if (/\r?\n\s*\r?\n/.test(text)) { selectionText.value = ''; selectionNotice.value = '划词评论和问 AI 需要在同一段落内，请重新选择。'; return; }
  const rect = selection?.rangeCount ? selection.getRangeAt(0).getBoundingClientRect() : null;
  if (!rect) return;
  selectionText.value = text; selectionToolbarStyle.value = { left: `${Math.max(8, rect.left)}px`, top: `${Math.max(8, rect.top - 42)}px` };
}
function clearSelection() { selectionText.value = ''; closeQuestion(); }
function closeQuestion() { questionController.value?.abort(); questionController.value = null; asking.value = false; questionOpen.value = false; }
function stopQuestion() { questionController.value?.abort(); questionController.value = null; asking.value = false; }
function startComment() { if (selectionText.value) emit('comment', selectionText.value); clearSelection(); }
async function locateAnchor() {
  const anchor = props.anchorResolution;
  if (!anchor?.currentRevision || !anchor.quote) return;
  await nextTick();
  const container = document.querySelector('[data-testid="page-content"]');
  if (!container) return;
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
  const nodes: Text[] = []; let combined = ''; let node: Node | null;
  while ((node = walker.nextNode())) { const text = node as Text; nodes.push(text); combined += text.data; }
  const start = combined.indexOf(anchor.quote); if (start < 0) return;
  const end = start + anchor.quote.length; let cursor = 0; let startNode: Text | null = null; let endNode: Text | null = null; let startOffset = 0; let endOffset = 0;
  for (const text of nodes) {
    const next = cursor + text.data.length;
    if (!startNode && start >= cursor && start < next) { startNode = text; startOffset = start - cursor; }
    if (end > cursor && end <= next) { endNode = text; endOffset = end - cursor; break; }
    cursor = next;
  }
  if (!startNode || !endNode) return;
  const range = document.createRange(); range.setStart(startNode, startOffset); range.setEnd(endNode, endOffset);
  const selection = window.getSelection(); selection?.removeAllRanges(); selection?.addRange(range);
  (startNode.parentElement ?? container).scrollIntoView({ behavior: 'smooth', block: 'center' });
}
async function askQuestion() {
  if (!props.kbId || !props.pageId || !selectionText.value || !question.value.trim() || asking.value) return;
  asking.value = true; answer.value = ''; questionError.value = '';
  questionController.value?.abort(); const controller = new AbortController(); questionController.value = controller;
  try {
    const response = await fetch(`/api/v1/knowledge-bases/${props.kbId}/pages/${props.pageId}/selection-question`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(getAuthToken() ? { Authorization: `Bearer ${getAuthToken()}` } : {}) }, body: JSON.stringify({ selectedText: selectionText.value, query: question.value.trim() }), signal: controller.signal });
    const renewed = response.headers.get('X-Auth-Token'); if (renewed) setAuthToken(renewed);
    if (!response.ok || !response.body) throw new Error('selection question failed');
    const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = '';
    for (;;) { const { done, value } = await reader.read(); if (done) break; buffer += decoder.decode(value, { stream: true }); let boundary = buffer.indexOf('\n\n'); while (boundary >= 0) { const raw = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2); const event = raw.match(/^event: (.+)$/m)?.[1]; const data = raw.match(/^data: (.+)$/m)?.[1]; if (event === 'token' && data) { try { answer.value += String((JSON.parse(data) as { text?: string }).text ?? ''); } catch { /* 忽略格式错误的帧 */ } } else if (event === 'error') throw new Error('selection question failed'); boundary = buffer.indexOf('\n\n'); } }
  } catch (error) { if ((error as { name?: string })?.name !== 'AbortError') questionError.value = '问 AI 失败，请重试'; } finally { if (questionController.value === controller) questionController.value = null; asking.value = false; }
}
watch([() => props.anchorResolution?.anchorId, () => page.value?.revisionNo], () => { void locateAnchor(); }, { immediate: true });
onBeforeUnmount(() => { invalidateCitation(); questionController.value?.abort(); });
</script>

<style scoped>
/* 引用高亮：用警示色混入透明，两种主题下都能看清且不遮挡文字颜色 */
:global(::highlight(kwiki-citation)) { background: color-mix(in srgb, var(--k-warn) 32%, transparent); color: inherit; }
:global(mark[data-kwiki-citation]) { background: color-mix(in srgb, var(--k-warn) 32%, transparent); color: inherit; border-radius: 2px; }
.source-tabs { display: inline-flex; gap: 2px; margin: 20px 0 4px; padding: 3px; border: 1px solid var(--k-line); border-radius: var(--k-r); background: var(--k-surface); }
.source-tab { height: 30px; border: 0; border-radius: var(--k-r-sm); padding: 0 14px; background: none; font: inherit; font-size: 13px; color: var(--k-muted); cursor: pointer; }
.source-tab:hover { color: var(--k-ink); }
.source-tab[aria-selected='true'] { background: var(--k-canvas); color: var(--k-ink); font-weight: 500; box-shadow: var(--k-shadow-sm); }
.source-preview-area { margin: 8px 0 18px; min-height: 60vh; display: flex; }
/* 阅读区：居中窄栏 */
.reader {
  max-width: 760px;
  margin: 0 auto;
  padding: 40px 32px 96px;
  color: var(--k-ink);
}
.anchor-notice { display:flex; gap:8px; align-items:baseline; margin:18px 0 10px; padding:10px 12px; border:1px solid color-mix(in srgb, var(--k-green) 30%, transparent); border-radius:var(--k-r); background:var(--k-green-soft); color:var(--k-green-deep); font-size:13px; }
.anchor-notice.warning { border-color:color-mix(in srgb, var(--k-warn) 30%, transparent); background:var(--k-warn-soft); color:var(--k-warn); }
.selection-notice { margin:10px 0; padding:9px 12px; border-radius:var(--k-r); background:var(--k-warn-soft); color:var(--k-warn); font-size:13px; }
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 12px;
  color: var(--k-muted);
  text-decoration: none;
  font-size: 12px;
}
.back i {
  font-size: 13px;
}
.back:hover {
  color: var(--k-ink);
}
.title-row {
  display: flex;
  align-items: flex-start;
  gap: 20px;
}
.title-copy {
  min-width: 0;
  flex: 1;
}
.page-title {
  margin: 0 0 10px;
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.8px;
  line-height: 1.2;
  color: var(--k-ink);
}
.meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin: 0;
  color: var(--k-muted);
  font-size: 13px;
}
.pill {
  padding: 3px 7px;
  border-radius: var(--k-r-sm);
  background: var(--k-surface-hover);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.pill i {
  font-size: 12px;
}
.updated {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--k-muted);
}
.updated i {
  font-size: 12px;
}
/* 正文排版（标题、链接、代码、表格、引用等）由全局 markdown.css 统一接管，这里只保留间距 */
.markdown.article {
  margin-top: 28px;
}
.markdown :deep(p) {
  margin: 9px 0;
}
.markdown :deep(ol),
.markdown :deep(ul) {
  margin: 7px 0 12px;
  padding-left: 24px;
}
.markdown :deep(li) {
  margin: 4px 0;
}
.markdown :deep(.kwiki-media-mount .k-media-viewer--image[data-status='resolving']),
.markdown :deep(.kwiki-media-mount .k-media-viewer--image[data-status='loading']) {
  height: 160px !important;
  aspect-ratio: auto !important;
}
.provenance {
  margin-top: 32px;
  padding-top: 17px;
  border-top: 1px solid var(--k-line);
  display: grid;
  grid-template-columns: 70px 1fr;
  gap: 10px 8px;
  color: var(--k-muted);
  font-size: 12px;
}
.prov-links {
  display: flex;
  flex-wrap: wrap;
  gap: 9px 18px;
}
.link {
  color: var(--k-green-deep);
  text-decoration: none;
  border-bottom: 1px solid color-mix(in srgb, var(--k-green) 40%, transparent);
}
.link:hover {
  border-bottom-color: var(--k-green-deep);
}
.state {
  color: var(--k-muted);
  line-height: 1.7;
}
.selection-toolbar { position:fixed; z-index:30; display:flex; gap:2px; padding:4px; border:1px solid var(--k-line); border-radius:var(--k-r); background:var(--k-canvas); box-shadow:var(--k-shadow); }
.selection-toolbar button { border:0; border-radius:var(--k-r-sm); padding:6px 10px; background:transparent; color:var(--k-ink-2); font-size:13px; cursor:pointer; }
.selection-toolbar button:hover { background:var(--k-surface-hover); color:var(--k-ink); }
.selection-modal { position:fixed; inset:0; z-index:50; display:grid; place-items:center; padding:20px; background:var(--k-overlay); }
.selection-modal-card { width:min(520px,100%); max-height:min(80vh,620px); overflow:auto; padding:18px; border:1px solid var(--k-line); border-radius:var(--k-r-lg); background:var(--k-canvas); color:var(--k-ink); box-shadow:var(--k-shadow-float); }
.selection-modal-card header { display:flex; justify-content:space-between; }
.selection-modal-card header button { border:0; background:none; font-size:22px; color:var(--k-muted); cursor:pointer; }
.selection-modal-card header button:hover { color:var(--k-ink); }
.selection-quote { margin:14px 0; padding:10px 12px; border-left:3px solid var(--k-line-strong); color:var(--k-muted); }
.selection-modal-card textarea { width:100%; min-height:70px; box-sizing:border-box; padding:10px; border:1px solid var(--k-line); border-radius:var(--k-r); background:var(--k-canvas); font:inherit; outline:none; }
.selection-modal-card textarea:hover { border-color:var(--k-line-strong); }
.selection-modal-card textarea:focus { border-color:var(--k-green); box-shadow:var(--k-focus-ring); }
.selection-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:10px; }
.selection-actions button { padding:8px 13px; border:1px solid var(--k-line); border-radius:var(--k-r); background:var(--k-canvas); color:var(--k-ink); cursor:pointer; }
.selection-actions button:hover { border-color:var(--k-line-strong); background:var(--k-surface); }
.selection-actions button:last-child { background:var(--k-primary); border-color:var(--k-primary); color:var(--k-on-primary); }
.selection-actions button:last-child:hover { background:var(--k-primary-hover); border-color:var(--k-primary-hover); }
.selection-actions button:disabled { opacity:.5; }
.selection-answer { margin-top:16px; padding-top:14px; border-top:1px solid var(--k-line); color:var(--k-ink-2); line-height:1.75; white-space:pre-wrap; }
@media (max-width: 640px) {
  .reader {
    padding: 24px 16px 64px;
  }
  .title-row {
    display: block;
  }
  .page-title {
    font-size: 26px;
  }
  .provenance {
    grid-template-columns: 1fr;
  }
  .markdown :deep(.kwiki-media-mount .k-media-viewer--image[data-status='resolving']),
  .markdown :deep(.kwiki-media-mount .k-media-viewer--image[data-status='loading']) {
    height: 120px !important;
  }
}
</style>
