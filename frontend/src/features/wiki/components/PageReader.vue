<template>
  <article v-if="page" data-testid="page-reader" class="reader">
    <a v-if="parentTitle" class="back" href="#" @click.prevent="selectParent">
      <i class="i-lucide-arrow-left" aria-hidden="true"></i>{{ parentTitle }}
    </a>
    <div class="title-row">
      <div class="title-copy">
        <h1 class="page-title">{{ displayTitle }}</h1>
        <p class="meta" data-testid="page-meta">
          <span class="pill"><i class="i-lucide-file-text" aria-hidden="true"></i>页面</span>
          <span class="pill">r{{ page.revisionNo }}</span>
          <span class="pill">已发布</span>
          <span class="updated"><i class="i-lucide-clock" aria-hidden="true"></i>更新于 {{ page.createdAt }}</span>
        </p>
      </div>
      <div v-if="props.kbId && props.pageId" class="export-menu">
        <button type="button" class="export-toggle" :aria-expanded="exportOpen" aria-label="导出当前修订" data-testid="reader-export" @click="exportOpen = !exportOpen">导出 <i class="i-lucide-chevron-down" aria-hidden="true"></i></button>
        <div v-if="exportOpen" class="export-panel">
          <button type="button" :disabled="exporting" @click="exportRevision('md')">Markdown</button>
          <button type="button" :disabled="exporting" @click="exportRevision('html')">HTML</button>
        </div>
      </div>
    </div>
    <div v-if="anchorResolution" class="anchor-notice" :class="{ warning: !anchorResolution.currentRevision }" role="status">
      <strong>{{ anchorResolution.status === 'RELOCATED' ? '已定位划词' : '划词位置提示' }}</strong>
      <span>{{ anchorResolution.message }}</span>
    </div>
    <MediaMountRegion
      class="markdown article"
      data-testid="page-content"
      :html="resolvedHtml"
      :resolver="mediaResolver"
      :kb-id="props.kbId"
      @mouseup="captureSelection"
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
  <p v-else-if="error" class="state" data-testid="page-error">{{ errorText }}</p>
  <p v-else class="state" data-testid="page-empty">请选择左侧页面</p>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useWikiStore, normalizedError } from '../store';
import { getAuthToken, setAuthToken } from '../api';
import type { TreeNodeDto } from '../api';
import MediaMountRegion from './MediaMountRegion.vue';
import { createMediaPreviewResolver } from './mediaResolver';
import type { MediaSourceResolver } from '@kk-2004/ui-components/components/KMediaViewer';
const props = defineProps<{
  breadcrumb?: string;
  title?: string;
  backlinks?: Array<{ id: number; title: string }>;
  kbId?: number;
  pageId?: number;
  anchorResolution?: { anchorId: number; status: string; quote: string; message: string; currentRevision: boolean } | null;
}>();
const emit = defineEmits<{ comment: [text: string] }>();

const store = useWikiStore();
const page = computed(() => store.page);
const error = computed(() => store.pageError);
const errorText = computed(() => normalizedError(error.value));

/** Server-rendered HTML is already sanitized by MarkdownPort. */
const sanitizedHtml = computed(() => page.value?.html ?? '');

/**
 * Media in the rendered HTML is displayed by the shared viewer via
 * MediaMountRegion (markers → one live instance per media). attachment://
 * references are resolved client-side to fresh authorized links; unresolved
 * ones keep the stable reference instead of a broken source URL. Signed URLs
 * never enter stored content.
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

/** Reader export of the currently viewed revision (never publishes). */
const exportOpen = ref(false);
const exporting = ref(false);
async function exportRevision(format: 'md' | 'html') {
  if (!props.kbId || !props.pageId || !page.value || exporting.value) return;
  exportOpen.value = false;
  exporting.value = true;
  try {
    const response = await fetch(
      `/api/v1/knowledge-bases/${props.kbId}/pages/${props.pageId}/export`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(getAuthToken() ? { Authorization: `Bearer ${getAuthToken()}` } : {}),
        },
        body: JSON.stringify({ format, revisionNo: page.value.revisionNo }),
      },
    );
    if (!response.ok) return;
    const blob = await response.blob();
    const disposition = response.headers.get('Content-Disposition') || '';
    const match = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
    let name = `${displayTitle.value || 'export'}.${format}`;
    if (match) {
      try { name = decodeURIComponent(match[1]); } catch { /* keep fallback */ }
    }
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = name;
    anchor.click();
    URL.revokeObjectURL(url);
  } finally {
    exporting.value = false;
  }
}

/** Title and path fall back to the tree entry of the selected page. */
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
    for (;;) { const { done, value } = await reader.read(); if (done) break; buffer += decoder.decode(value, { stream: true }); let boundary = buffer.indexOf('\n\n'); while (boundary >= 0) { const raw = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2); const event = raw.match(/^event: (.+)$/m)?.[1]; const data = raw.match(/^data: (.+)$/m)?.[1]; if (event === 'token' && data) { try { answer.value += String((JSON.parse(data) as { text?: string }).text ?? ''); } catch { /* ignore malformed frame */ } } else if (event === 'error') throw new Error('selection question failed'); boundary = buffer.indexOf('\n\n'); } }
  } catch (error) { if ((error as { name?: string })?.name !== 'AbortError') questionError.value = '问 AI 失败，请重试'; } finally { if (questionController.value === controller) questionController.value = null; asking.value = false; }
}
watch([() => props.anchorResolution?.anchorId, () => page.value?.revisionNo], () => { void locateAnchor(); }, { immediate: true });
onBeforeUnmount(() => questionController.value?.abort());
</script>

<style scoped>
.reader {
  color: var(--kwiki-ink);
}
.anchor-notice { display:flex; gap:8px; align-items:baseline; margin:18px 0 10px; padding:10px 12px; border:1px solid #b9e3c8; border-radius:8px; background:#f3fcf6; color:#27734b; font-size:13px; }
.anchor-notice.warning { border-color:#efd39e; background:#fffaf0; color:#876023; }
.selection-notice { margin:10px 0; padding:9px 12px; border-radius:8px; background:#fff8ed; color:#876023; font-size:13px; }
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 12px;
  color: #7f8585;
  text-decoration: none;
  font-size: 12px;
}
.back i {
  font-size: 13px;
}
.back:hover {
  color: var(--kwiki-green-dark);
}
.title-row {
  display: flex;
  align-items: flex-start;
  gap: 20px;
}
.export-menu {
  position: relative;
  flex-shrink: 0;
}
.export-toggle {
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid #dfe3e2;
  border-radius: 6px;
  background: var(--kwiki-panel, #fff);
  font: inherit;
  font-size: 12px;
  color: #555c5c;
  cursor: pointer;
}
.export-panel {
  position: absolute;
  right: 0;
  top: calc(100% + 4px);
  z-index: 30;
  background: #fff;
  border: 1px solid #e0e8e2;
  border-radius: 8px;
  box-shadow: 0 10px 30px rgba(27, 52, 40, 0.12);
  display: grid;
  min-width: 200px;
}
.export-panel button {
  border: 0;
  background: none;
  text-align: left;
  padding: 10px 14px;
  font: inherit;
  font-size: 12px;
  color: #4a5454;
  cursor: pointer;
}
.export-panel button:hover {
  background: #f0f7f2;
}
.title-copy {
  min-width: 0;
  flex: 1;
}
.page-title {
  margin: 0 0 9px;
  font-size: 29px;
  line-height: 1.15;
  letter-spacing: -0.025em;
}
.meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin: 0;
  color: #8f9696;
  font-size: 12px;
}
.pill {
  padding: 3px 7px;
  border-radius: 5px;
  background: #f3f5f4;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.pill i {
  font-size: 12px;
}
.updated {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #a4aaaa;
}
.updated i {
  font-size: 12px;
}
.markdown.article {
  font-size: 15px;
  line-height: 1.85;
  color: #343a3a;
}
.markdown :deep(h1) {
  font-size: 25px;
  margin: 27px 0 9px;
  letter-spacing: -0.02em;
}
.markdown :deep(h2) {
  margin: 27px 0 9px;
  font-size: 19px;
}
.markdown :deep(h3) {
  margin: 23px 0 8px;
  font-size: 17px;
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
.markdown :deep(a) {
  color: var(--kwiki-green-dark);
  text-decoration: none;
  border-bottom: 1px dashed #5ed19a;
}
.markdown :deep(table) {
  border-collapse: separate;
  border-spacing: 0;
  margin: 12px 0 16px;
  border: 1px solid #e0e4e3;
  border-radius: 7px;
  overflow: hidden;
  font-size: 14px;
}
.markdown :deep(th),
.markdown :deep(td) {
  min-width: 88px;
  padding: 8px 12px;
  border-right: 1px solid #e6e9e8;
  border-bottom: 1px solid #e6e9e8;
  text-align: left;
}
.markdown :deep(th) {
  background: var(--kwiki-soft);
}
.markdown :deep(tr:last-child td) {
  border-bottom: 0;
}
.markdown :deep(th:last-child),
.markdown :deep(td:last-child) {
  border-right: 0;
}
.markdown :deep(pre) {
  background: var(--kwiki-soft);
  padding: 12px 14px;
  border-radius: 7px;
  overflow: auto;
  font: 13px/1.75 ui-monospace, SFMono-Regular, Menlo, monospace;
}
.markdown :deep(code) {
  background: var(--kwiki-soft);
  border-radius: 4px;
  padding: 1px 5px;
  font: 13px ui-monospace, SFMono-Regular, Menlo, monospace;
}
.markdown :deep(pre code) {
  background: none;
  padding: 0;
}
.markdown :deep(.kwiki-media-mount .k-media-viewer--image[data-status='resolving']),
.markdown :deep(.kwiki-media-mount .k-media-viewer--image[data-status='loading']) {
  height: 160px !important;
  aspect-ratio: auto !important;
}
.provenance {
  margin-top: 32px;
  padding-top: 17px;
  border-top: 1px solid var(--kwiki-line);
  display: grid;
  grid-template-columns: 70px 1fr;
  gap: 10px 8px;
  color: #979d9d;
  font-size: 12px;
}
.prov-links {
  display: flex;
  flex-wrap: wrap;
  gap: 9px 18px;
}
.link {
  color: var(--kwiki-green-dark);
  text-decoration: none;
  border-bottom: 1px dashed #5ed19a;
}
.state {
  color: var(--kwiki-muted);
  line-height: 1.7;
}
.selection-toolbar { position:fixed; z-index:30; display:flex; gap:4px; padding:4px; border:1px solid #d8e5dc; border-radius:7px; background:#fff; box-shadow:0 5px 17px #273a2c22; } .selection-toolbar button { border:0; border-radius:5px; padding:6px 9px; background:#f0f8f3; color:#187b4a; cursor:pointer; } .selection-modal { position:fixed; inset:0; z-index:50; display:grid; place-items:center; padding:20px; background:#1c282255; } .selection-modal-card { width:min(520px,100%); max-height:min(80vh,620px); overflow:auto; padding:18px; border-radius:12px; background:#fff; box-shadow:0 20px 60px #17261d33; } .selection-modal-card header { display:flex; justify-content:space-between; } .selection-modal-card header button { border:0; background:none; font-size:22px; cursor:pointer; } .selection-quote { margin:14px 0; padding:10px 12px; border-left:3px solid #84d8ae; color:#718078; } .selection-modal-card textarea { width:100%; min-height:70px; box-sizing:border-box; padding:10px; border:1px solid #dfe7e2; border-radius:8px; font:inherit; } .selection-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:10px; } .selection-actions button { padding:8px 13px; border:1px solid #cedbd3; border-radius:7px; background:#fff; cursor:pointer; } .selection-actions button:last-child { background:#1b9d61; border-color:#1b9d61; color:#fff; } .selection-actions button:disabled { opacity:.5; } .selection-answer { margin-top:16px; padding-top:14px; border-top:1px solid #e5ece7; line-height:1.75; white-space:pre-wrap; }
@media (max-width: 640px) {
  .title-row {
    display: block;
  }
  .page-title {
    font-size: 25px;
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
