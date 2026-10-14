<script setup lang="ts">
/**
 * 划线评论层（覆盖在阅读区上）：
 * - 已评论的划线以 Custom Highlight 加下划线（不改动正文 DOM），正文右侧同一行显示评论图标与数量；
 * - 点击图标弹出该划线的评论列表：一级评论 + 其下扁平化的回复（显示“回复 @某人”），支持 @ 提醒；
 * - 划词工具栏点“评论”时在选区旁弹出输入框，发表后登记锚点并标注。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import MentionInput from './MentionInput.vue';
import { findQuoteRange } from './locateChunk';
import { errorMessage } from '../api';
import { useAuthStore } from '../../auth/store';
import {
  createSelectionComment, deleteComment, loadAnchorGroups, postComment,
  type AnchorGroup, type PageComment,
} from '../selectionComments';

const props = defineProps<{
  kbId: number;
  pageId: number;
  /** 正文容器（解析文本区域）。 */
  container: Element | null;
  /** 正文内容标识，变化时重新定位。 */
  contentKey: string;
  focusCommentId?: number | null;
}>();

const auth = useAuthStore();
const layer = ref<HTMLElement | null>(null);
const comments = ref<PageComment[]>([]);
const groups = ref<AnchorGroup[]>([]);
const loadError = ref('');

interface Line { left: number; top: number; width: number }
interface Marker { group: AnchorGroup; range: Range; left: number; top: number; lines: Line[] }
const markers = ref<Marker[]>([]);

const POPOVER_WIDTH = 340;
const activeKey = ref<string | null>(null);
const activeGroup = computed(() => groups.value.find(group => group.key === activeKey.value) ?? null);
const listStyle = ref<Record<string, string>>({});

const composer = ref<{ text: string; range: Range; style: Record<string, string> } | null>(null);
const composerDraft = ref('');
const composerInput = ref<InstanceType<typeof MentionInput> | null>(null);
const composerError = ref('');
const replyDraft = ref('');
const replyInput = ref<InstanceType<typeof MentionInput> | null>(null);
const replyTarget = ref<PageComment | null>(null);
const listError = ref('');
const saving = ref(false);

const usernames = computed(() => new Map(comments.value.map(item => [item.id, item.authorUsername])));

let loadController: AbortController | null = null;
async function reload() {
  loadController?.abort();
  const controller = new AbortController(); loadController = controller;
  try {
    const result = await loadAnchorGroups(props.kbId, props.pageId, controller.signal);
    if (loadController !== controller) return;
    comments.value = result.comments;
    groups.value = result.groups;
    loadError.value = '';
  } catch (cause) {
    if ((cause as { name?: string })?.name !== 'AbortError') loadError.value = errorMessage(cause, '划线评论加载失败');
  }
}

// ---------- 定位与标注 ----------
type HighlightCtor = new (...ranges: Range[]) => unknown;
function registry(): { highlights: Map<string, unknown>; Highlight: HighlightCtor } | null {
  const api = globalThis as unknown as { CSS?: { highlights?: Map<string, unknown> }; Highlight?: HighlightCtor };
  return api.CSS?.highlights && api.Highlight ? { highlights: api.CSS.highlights, Highlight: api.Highlight } : null;
}

/** 下划线由覆盖层按行绘制（::highlight 的 text-decoration 支持不一）；Highlight 只用于当前项的背景。 */
function paintHighlights() {
  const reg = registry();
  if (!reg) return;
  reg.highlights.delete('kwiki-comment-active');
  const active = composer.value?.range ?? markers.value.find(marker => marker.group.key === activeKey.value)?.range;
  if (active) reg.highlights.set('kwiki-comment-active', new reg.Highlight(active));
}

function layout() {
  const container = props.container;
  const host = layer.value;
  if (!container || !host || !(container as HTMLElement).offsetParent) { markers.value = []; paintHighlights(); return; }
  const hostRect = host.getBoundingClientRect();
  const columnRight = container.getBoundingClientRect().right - hostRect.left;
  // 宽屏放在正文栏右侧留白；窄屏放进正文栏右缘
  const baseLeft = columnRight + 44 <= hostRect.width ? columnRight + 10 : columnRight - 40;
  const next: Marker[] = [];
  for (const group of groups.value) {
    const found = findQuoteRange(container, group.quote, group.startOffset, Number.POSITIVE_INFINITY);
    if (found.status !== 'located') continue;
    const first = found.range.getClientRects()[0] ?? found.range.getBoundingClientRect();
    const top = first.top - hostRect.top + first.height / 2 - 11;
    const sameLine = next.filter(marker => Math.abs(marker.top - top) < 6).length;
    next.push({ group, range: found.range, left: baseLeft + sameLine * 44, top, lines: underlineRects(found.range, hostRect) });
  }
  markers.value = next;
  paintHighlights();
}

/** 选区每一行的下划线位置；合并同一行内相邻/重叠的矩形（行内元素会重复返回）。 */
function underlineRects(range: Range, hostRect: DOMRect): Line[] {
  const rows: { left: number; right: number; bottom: number }[] = [];
  for (const rect of range.getClientRects()) {
    if (rect.width < 1 || rect.height < 1) continue;
    const row = rows.find(item => Math.abs(item.bottom - rect.bottom) < 4 && rect.left <= item.right + 2 && rect.right >= item.left - 2);
    if (row) { row.left = Math.min(row.left, rect.left); row.right = Math.max(row.right, rect.right); row.bottom = Math.max(row.bottom, rect.bottom); }
    else rows.push({ left: rect.left, right: rect.right, bottom: rect.bottom });
  }
  return rows.map(row => ({ left: row.left - hostRect.left, top: row.bottom - hostRect.top + 1, width: row.right - row.left }));
}

/** 点击已划线的文字（无拖选）也可打开评论列表。 */
function onContainerClick(event: MouseEvent) {
  if (!window.getSelection()?.isCollapsed) return;
  const doc = document as Document & { caretRangeFromPoint?: (x: number, y: number) => Range | null; caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null };
  const position = doc.caretPositionFromPoint?.(event.clientX, event.clientY);
  const caret = position ? { node: position.offsetNode, offset: position.offset } : (() => { const range = doc.caretRangeFromPoint?.(event.clientX, event.clientY); return range ? { node: range.startContainer, offset: range.startOffset } : null; })();
  if (!caret) return;
  const marker = markers.value.find(item => { try { return item.range.isPointInRange(caret.node, caret.offset); } catch { return false; } });
  if (marker && activeKey.value !== marker.group.key) openList(marker);
}

let layoutFrame = 0;
function scheduleLayout() {
  cancelAnimationFrame(layoutFrame);
  layoutFrame = requestAnimationFrame(() => layout());
}

let resizeObserver: ResizeObserver | undefined;
watch(() => props.container, (container, previous) => {
  if (previous) { resizeObserver?.unobserve(previous); previous.removeEventListener('click', onContainerClick as EventListener); }
  if (container) { resizeObserver?.observe(container); container.addEventListener('click', onContainerClick as EventListener); }
  scheduleLayout();
});
watch(() => [groups.value, props.contentKey], async () => { await nextTick(); await nextTick(); scheduleLayout(); });
watch([activeKey, composer], paintHighlights);
watch(() => [props.kbId, props.pageId], () => { activeKey.value = null; composer.value = null; void reload(); }, { immediate: true });

// ---------- 弹层 ----------
function clampLeft(left: number): number {
  const width = layer.value?.clientWidth ?? POPOVER_WIDTH;
  return Math.max(0, Math.min(left, width - POPOVER_WIDTH));
}

function openList(marker: Marker) {
  composer.value = null;
  if (activeKey.value === marker.group.key) { activeKey.value = null; return; }
  activeKey.value = marker.group.key;
  replyTarget.value = null; replyDraft.value = ''; listError.value = '';
  listStyle.value = { left: `${clampLeft(marker.left + 36 - POPOVER_WIDTH)}px`, top: `${marker.top + 30}px` };
}

/** 由划词工具栏调用：在选区下方弹出评论输入框。 */
function openComposer(text: string, range: Range) {
  const host = layer.value;
  if (!host) return;
  const hostRect = host.getBoundingClientRect();
  const rects = range.getClientRects();
  const last = rects[rects.length - 1] ?? range.getBoundingClientRect();
  activeKey.value = null;
  composerDraft.value = ''; composerError.value = '';
  composer.value = { text, range: range.cloneRange(), style: { left: `${clampLeft(last.left - hostRect.left - 20)}px`, top: `${last.bottom - hostRect.top + 8}px` } };
  void nextTick(() => composerInput.value?.focus());
}

function closeAll() { composer.value = null; activeKey.value = null; replyTarget.value = null; }

async function submitComposer() {
  const current = composer.value;
  if (!current || !composerDraft.value.trim() || saving.value) return;
  saving.value = true; composerError.value = '';
  try {
    await createSelectionComment(props.kbId, props.pageId, current.text, composerDraft.value.trim(), composerInput.value?.mentionUserIds() ?? []);
    composerInput.value?.reset();
    composer.value = null;
    window.getSelection()?.removeAllRanges();
    await reload();
  } catch (cause) {
    composerError.value = errorMessage(cause, '评论发表失败，请重试');
  } finally {
    saving.value = false;
  }
}

function startReply(comment: PageComment) {
  replyTarget.value = comment;
  void nextTick(() => replyInput.value?.focus());
}

async function submitReply() {
  const group = activeGroup.value;
  if (!group || !replyDraft.value.trim() || saving.value) return;
  const target = replyTarget.value;
  const thread = target ? group.threads.find(item => item.root.id === target.id || item.replies.some(reply => reply.id === target.id)) : null;
  saving.value = true; listError.value = '';
  try {
    await postComment(props.kbId, props.pageId, {
      body: replyDraft.value.trim(),
      anchorId: thread?.root.anchorId ?? group.anchorIds[0],
      replyTo: target?.id,
      mentionUserIds: replyInput.value?.mentionUserIds() ?? [],
    });
    replyInput.value?.reset();
    replyTarget.value = null;
    await reload();
  } catch (cause) {
    listError.value = errorMessage(cause, '评论发表失败，请重试');
  } finally {
    saving.value = false;
  }
}

async function remove(comment: PageComment) {
  listError.value = '';
  try {
    await deleteComment(props.kbId, props.pageId, comment.id);
    if (replyTarget.value?.id === comment.id) replyTarget.value = null;
    await reload();
    if (!activeGroup.value) activeKey.value = null;
  } catch (cause) {
    listError.value = errorMessage(cause, '评论删除失败，请重试');
  }
}

function canDelete(comment: PageComment): boolean {
  return !comment.deleted && comment.authorId === auth.user?.id;
}

function formatTime(value: string): string {
  const match = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(value);
  return match ? `${match[1]} ${match[2]}` : value;
}

// 通知跳转（?comment=）：打开该评论所在划线的评论列表
watch(() => [props.focusCommentId, markers.value] as const, ([id]) => {
  if (!id) return;
  const marker = markers.value.find(item => item.group.threads.some(thread => thread.root.id === id || thread.replies.some(reply => reply.id === id)));
  if (!marker || activeKey.value === marker.group.key) return;
  openList(marker);
  void nextTick(() => layer.value?.querySelector(`[data-selection-comment-id="${id}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }));
});

function onDocumentPointerDown(event: PointerEvent) {
  if (!composer.value && !activeKey.value) return;
  const path = event.composedPath();
  if (path.some(node => node instanceof Element && (node.classList.contains('comment-popover') || node.classList.contains('comment-marker') || node.classList.contains('selection-toolbar')))) return;
  closeAll();
}
function onKeydown(event: KeyboardEvent) { if (event.key === 'Escape') closeAll(); }

onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(scheduleLayout);
    if (props.container) resizeObserver.observe(props.container);
  }
  props.container?.addEventListener('click', onContainerClick as EventListener);
  window.addEventListener('resize', scheduleLayout);
  document.addEventListener('pointerdown', onDocumentPointerDown, true);
  document.addEventListener('keydown', onKeydown);
});
onBeforeUnmount(() => {
  loadController?.abort();
  cancelAnimationFrame(layoutFrame);
  resizeObserver?.disconnect();
  props.container?.removeEventListener('click', onContainerClick as EventListener);
  window.removeEventListener('resize', scheduleLayout);
  document.removeEventListener('pointerdown', onDocumentPointerDown, true);
  document.removeEventListener('keydown', onKeydown);
  const reg = registry();
  reg?.highlights.delete('kwiki-comment-active');
});

defineExpose({ openComposer, reload });
</script>

<template>
  <div ref="layer" class="selection-comment-layer" data-testid="selection-comment-layer">
    <template v-for="marker in markers" :key="`line-${marker.group.key}`">
      <span
        v-for="(line, index) in marker.lines"
        :key="index"
        class="comment-underline"
        :class="{ active: activeKey === marker.group.key }"
        :style="{ left: `${line.left}px`, top: `${line.top}px`, width: `${line.width}px` }"
        aria-hidden="true"
      ></span>
    </template>
    <button
      v-for="marker in markers"
      :key="marker.group.key"
      type="button"
      class="comment-marker"
      :class="{ active: activeKey === marker.group.key }"
      :style="{ left: `${marker.left}px`, top: `${marker.top}px` }"
      :aria-label="`划线评论 ${marker.group.count} 条：${marker.group.quote}`"
      :aria-expanded="activeKey === marker.group.key"
      data-testid="comment-marker"
      @click="openList(marker)"
    >
      <i class="i-lucide-message-square-text" aria-hidden="true"></i>
      <span>{{ marker.group.count }}</span>
    </button>

    <form v-if="composer" class="comment-popover composer" :style="composer.style" role="dialog" aria-label="划线评论" @submit.prevent="submitComposer">
      <p class="quote">{{ composer.text }}</p>
      <MentionInput ref="composerInput" v-model="composerDraft" :kb-id="kbId" :page-id="pageId" label="划线评论内容" @submit="submitComposer" />
      <p v-if="composerError" class="error" role="alert">{{ composerError }}</p>
      <footer>
        <button type="button" class="ghost icon" aria-label="提及成员" title="提及成员" @click="composerInput?.insertAt()"><i class="i-lucide-at-sign" aria-hidden="true"></i></button>
        <span class="hint">⌘/Ctrl + Enter 发表</span>
        <button type="button" class="ghost" @click="composer = null">取消</button>
        <button type="submit" class="primary" :disabled="!composerDraft.trim() || saving">{{ saving ? '发表中…' : '发表' }}</button>
      </footer>
    </form>

    <section v-if="activeGroup" class="comment-popover list" :style="listStyle" role="dialog" aria-label="划线评论列表" data-testid="selection-comment-list">
      <header>
        <p class="quote">{{ activeGroup.quote }}</p>
        <button type="button" class="ghost icon" aria-label="关闭" @click="activeKey = null"><i class="i-lucide-x" aria-hidden="true"></i></button>
      </header>
      <div class="threads">
        <article v-for="thread in activeGroup.threads" :key="thread.root.id" class="thread">
          <div v-for="item in [thread.root, ...thread.replies]" :key="item.id" class="comment" :class="{ reply: item.parentId != null, focused: focusCommentId === item.id }" :data-selection-comment-id="item.id">
            <div class="comment-head">
              <strong>{{ item.authorUsername }}</strong>
              <span v-if="item.parentId != null && item.replyTo != null" class="reply-to">回复 <b>@{{ usernames.get(item.replyTo) ?? '已删除' }}</b></span>
              <time>{{ formatTime(item.createdAt) }}</time>
            </div>
            <p class="body" :class="{ deleted: item.deleted }">{{ item.body }}</p>
            <div v-if="!item.deleted" class="comment-actions">
              <button type="button" @click="startReply(item)">回复</button>
              <button v-if="canDelete(item)" type="button" @click="remove(item)">删除</button>
            </div>
          </div>
        </article>
      </div>
      <form class="reply-form" @submit.prevent="submitReply">
        <div v-if="replyTarget" class="replying">
          回复 <b>@{{ replyTarget.authorUsername }}</b>
          <button type="button" class="ghost icon" aria-label="取消回复" @click="replyTarget = null"><i class="i-lucide-x" aria-hidden="true"></i></button>
        </div>
        <MentionInput ref="replyInput" v-model="replyDraft" :kb-id="kbId" :page-id="pageId" :placeholder="replyTarget ? `回复 @${replyTarget.authorUsername}…` : '继续评论，输入 @ 提醒成员'" label="回复划线评论" @submit="submitReply" />
        <p v-if="listError" class="error" role="alert">{{ listError }}</p>
        <footer>
          <button type="button" class="ghost icon" aria-label="提及成员" title="提及成员" @click="replyInput?.insertAt()"><i class="i-lucide-at-sign" aria-hidden="true"></i></button>
          <span class="hint">⌘/Ctrl + Enter 发送</span>
          <button type="submit" class="primary" :disabled="!replyDraft.trim() || saving">{{ saving ? '发送中…' : '发送' }}</button>
        </footer>
      </form>
    </section>
    <p v-if="loadError" class="load-error" role="status">{{ loadError }}</p>
  </div>
</template>

<style scoped>
.selection-comment-layer { position: absolute; inset: 0; pointer-events: none; z-index: 20; }
.selection-comment-layer > * { pointer-events: auto; }
.selection-comment-layer > .comment-underline {
  position: absolute;
  height: 2px;
  border-radius: 1px;
  background: color-mix(in srgb, var(--k-green) 65%, transparent);
  pointer-events: none;
}
.selection-comment-layer > .comment-underline.active { background: var(--k-green); }
.comment-marker {
  position: absolute;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 22px;
  padding: 0 7px;
  border: 1px solid color-mix(in srgb, var(--k-green) 35%, transparent);
  border-radius: 999px;
  background: var(--k-canvas);
  color: var(--k-green-deep);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  box-shadow: var(--k-shadow-sm);
  cursor: pointer;
  transition: background .12s ease, transform .12s ease;
}
.comment-marker:hover, .comment-marker.active { background: var(--k-green-soft); transform: translateY(-1px); }
.comment-marker:focus-visible { outline: 2px solid var(--k-green); outline-offset: 1px; }
.comment-popover {
  position: absolute;
  width: 340px;
  max-width: 100%;
  box-sizing: border-box;
  padding: 12px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  box-shadow: var(--k-shadow-float);
  font-size: 13px;
  color: var(--k-ink);
}
.quote {
  margin: 0 0 10px;
  padding-left: 8px;
  border-left: 2px solid var(--k-green);
  color: var(--k-muted);
  font-size: 12px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.list header { display: flex; align-items: flex-start; gap: 6px; }
.list header .quote { flex: 1; }
.threads { max-height: min(320px, 45vh); overflow-y: auto; margin: 0 -12px; padding: 0 12px; scrollbar-width: thin; }
.thread + .thread { border-top: 1px solid var(--k-line); margin-top: 8px; padding-top: 8px; }
.comment { padding: 6px 0; border-radius: var(--k-r-sm); }
.comment.reply { margin-left: 14px; padding-left: 10px; border-left: 1px solid var(--k-line); }
.comment.focused { background: var(--k-warn-soft); }
.comment-head { display: flex; align-items: baseline; gap: 6px; font-size: 12px; }
.comment-head strong { color: var(--k-ink); font-weight: 600; }
.reply-to { color: var(--k-muted); }
.reply-to b { color: var(--k-green-deep); font-weight: 500; }
.comment-head time { margin-left: auto; color: var(--k-faint); font-size: 11px; white-space: nowrap; }
.body { margin: 3px 0 2px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--k-ink-2); }
.body.deleted { color: var(--k-faint); font-style: italic; }
.comment-actions { display: flex; gap: 10px; }
.comment-actions button { padding: 0; border: 0; background: none; color: var(--k-muted); font: inherit; font-size: 11px; cursor: pointer; }
.comment-actions button:hover { color: var(--k-green-deep); }
.reply-form { margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--k-line); }
.replying { display: flex; align-items: center; gap: 4px; margin-bottom: 6px; color: var(--k-muted); font-size: 12px; }
.replying b { color: var(--k-green-deep); font-weight: 500; }
footer { display: flex; align-items: center; gap: 6px; margin-top: 8px; }
.hint { margin-right: auto; color: var(--k-faint); font-size: 11px; }
.ghost, .primary { height: 28px; padding: 0 12px; border-radius: var(--k-r); font: inherit; font-size: 12px; cursor: pointer; }
.ghost { border: 0; background: none; color: var(--k-muted); }
.ghost:hover { background: var(--k-surface-hover); color: var(--k-ink); }
.ghost.icon { width: 28px; padding: 0; display: inline-flex; align-items: center; justify-content: center; flex: none; }
.replying .ghost.icon { width: 20px; height: 20px; }
.primary { border: 0; background: var(--k-ink); color: var(--k-canvas); }
.primary:disabled { opacity: .45; cursor: default; }
.error { margin: 6px 0 0; color: var(--k-danger); font-size: 12px; }
.load-error { position: absolute; right: 0; top: 0; margin: 0; color: var(--k-warn); font-size: 12px; }
</style>
