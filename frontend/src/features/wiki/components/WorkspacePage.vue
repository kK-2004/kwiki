<template>
  <div class="workspace-page" data-testid="workspace-page">
    <p v-if="route.query.imported" class="ui-notice" role="status">{{ route.query.imported }}</p>
    <ArchiveConfirmDialog
      :open="archiveDialogOpen"
      scope-label="页面"
      :title="store.page?.title ?? ''"
      :pending="archivePending"
      @cancel="archiveDialogOpen = false"
      @confirm="archivePage"
    />

    <PageReader v-if="mode !== 'edit'" :kb-id="numericKbId ?? undefined" :page-id="numericPageId ?? undefined" :anchor-resolution="anchorResolution" :chunk-key="typeof route.query.chunk === 'string' ? route.query.chunk : undefined" @comment="beginSelectionComment" />
    <WikiInteractionPanel v-if="mode !== 'edit' && numericKbId && numericPageId" :kb-id="numericKbId" :page-id="numericPageId" :selection-text="selectionForComment" :anchor-id="selectionAnchorId" :focus-comment-id="commentId" />
    <PageEditor
      v-if="mode === 'edit' && numericKbId && numericPageId"
      :kb-id="numericKbId"
      :page-id="numericPageId"
      @cancel="mode = 'read'"
      @published="mode = 'read'"
    />
    <RevisionHistoryDrawer
      v-if="mode === 'history'"
      :revisions="revisions"
      @restore="onRestore"
      @close="mode = 'read'"
    />
    <div v-if="mode === 'history'" class="overlay" @click="mode = 'read'"></div>


  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useRoute } from 'vue-router';
import PageReader from './PageReader.vue';
import PageEditor from './PageEditor.vue';
import RevisionHistoryDrawer from './RevisionHistoryDrawer.vue';

import WikiInteractionPanel from './WikiInteractionPanel.vue';
import ArchiveConfirmDialog from './ArchiveConfirmDialog.vue';
import { useWikiStore } from '../store';
import { api } from '../api';
import type { CitationEntry } from '../sse';

const props = defineProps<{ kbId?: string; pageId?: string }>();
const store = useWikiStore();
const router = useRouter();
const route = useRoute();
const mode = ref<'read' | 'edit' | 'history'>('read');
const revisions = ref<Array<{ revisionNo: number; changeNote?: string; createdAt: string }>>([]);
const citationTarget = ref<CitationEntry | null>(null);
const selectionForComment = ref('');
const selectionAnchorId = ref<number | null>(null);
const anchorResolution = ref<{ anchorId: number; status: string; quote: string; message: string; currentRevision: boolean } | null>(null);
const commentId = computed(() => { const raw = Array.isArray(route.query.comment) ? route.query.comment[0] : route.query.comment; const value = raw ? Number(raw) : 0; return value > 0 ? value : null; });

const numericKbId = computed(() => (props.kbId ? Number(props.kbId) : null));
const numericPageId = computed(() => (props.pageId ? Number(props.pageId) : null));

function toggleEdit() { mode.value = mode.value === 'edit' ? 'read' : 'edit'; }
async function toggleHistory() {
  if (mode.value === 'history') {
    mode.value = 'read';
    return;
  }
  if (!numericKbId.value || !numericPageId.value) return;
  try {
    revisions.value = await api.json<Array<{ revisionNo: number; changeNote?: string; createdAt: string }>>(
      `/knowledge-bases/${numericKbId.value}/pages/${numericPageId.value}/revisions`,
    );
    mode.value = 'history';
  } catch {
    revisions.value = [];
    mode.value = 'history';
  }
}

function loadCurrentPage() {
  mode.value = 'read';
  const kb = numericKbId.value;
  const page = numericPageId.value;
  if (kb && page) {
    store.selectPage(page);
    void store.loadPage(kb, page);
    void api.post(`/knowledge-bases/${kb}/pages/${page}/visit`)
      .then(() => store.loadRecentVisits(true))
      .catch(() => { /* 最近访问记录失败不阻断页面阅读。 */ });
    const rawAnchor = Array.isArray(route.query.anchor) ? route.query.anchor[0] : route.query.anchor;
    const anchorId = rawAnchor ? Number(rawAnchor) : 0;
    anchorResolution.value = null;
    if (anchorId > 0) {
      void api.json<NonNullable<typeof anchorResolution.value>>(`/knowledge-bases/${kb}/pages/${page}/anchors/${anchorId}`)
        .then((value) => { anchorResolution.value = value; })
        .catch(() => { anchorResolution.value = null; });
    }
  } else {
    store.selectPage(null);
    anchorResolution.value = null;
    selectionForComment.value = '';
    selectionAnchorId.value = null;
  }
}
onMounted(loadCurrentPage);
// 逐值比较：查询对象整体变化（如消费 ?chunk）不得触发页面重载。
watch([() => props.kbId, () => props.pageId, () => route.query.anchor], loadCurrentPage);

async function onRestore(revisionNo: number) {
  if (!numericKbId.value || !numericPageId.value) return;
  try {
    await api.post(`/knowledge-bases/${numericKbId.value}/pages/${numericPageId.value}/revisions/${revisionNo}/restore`, {});
    await store.loadPage(numericKbId.value, numericPageId.value);
    mode.value = 'edit';
  } catch {
    // 服务端通过 API 客户端返回冲突或权限错误。
  }
}

function onCitation(citation: CitationEntry) {
  citationTarget.value = citation;
}
async function beginSelectionComment(text: string) {
  selectionForComment.value = text;
  selectionAnchorId.value = null;
  if (numericKbId.value && numericPageId.value) {
    try { const anchor = await api.post<{ id: number }>(`/knowledge-bases/${numericKbId.value}/pages/${numericPageId.value}/anchors`, { selectedText: text }); selectionAnchorId.value = anchor.id; } catch { /* 即使没有锚点，评论仍可发布 */ }
  }
  requestAnimationFrame(() => document.querySelector('[data-testid="wiki-interactions"]')?.scrollIntoView({ behavior: 'smooth', block: 'center' }));
}

const archiveDialogOpen = ref(false);
const archivePending = ref(false);
function openArchive() { archiveDialogOpen.value = true; }

/** 仅在二次弹窗确认时触发；带防抖，且服务端调用幂等。 */
async function archivePage() {
  if (!numericKbId.value || !numericPageId.value || archivePending.value) return;
  archivePending.value = true;
  try {
    await api.post(`/knowledge-bases/${numericKbId.value}/pages/${numericPageId.value}/archive`, {});
    archiveDialogOpen.value = false;
    const kb = numericKbId.value;
    store.selectPage(null);
    store.tree = [];
    await router.replace(`/knowledge-bases/${kb}`);
    try { await store.loadTree(kb); } catch { /* 归档已完成，知识库页面仍可通过目录重试加载。 */ }
  } catch {
    // 当服务端拒绝归档时，保持阅读器与对话框打开。
  } finally {
    archivePending.value = false;
  }
}
defineExpose({ toggleEdit, toggleHistory, openArchive });
</script>

<style scoped>
.workspace-page {
  width:100%;
  max-width:1080px;
  margin:0 auto;
}
.overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: rgba(22, 31, 27, 0.25);
  backdrop-filter: blur(1px);
}
</style>
