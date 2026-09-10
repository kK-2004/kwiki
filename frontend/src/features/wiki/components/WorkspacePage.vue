<template>
  <div data-testid="workspace-page">
    <p v-if="route.query.imported" class="ui-notice" role="status">{{ route.query.imported }}</p>
    <nav class="page-actions" aria-label="页面操作">
      <CollaborationPanel v-if="numericPageId && store.page?.canManage" resource-type="PAGE" :resource-id="numericPageId" :knowledge-base-id="numericKbId ?? undefined" />
      <button v-if="store.page?.canEdit"
        type="button"
        class="icon"
        :class="{ active: mode === 'edit' }"
        data-testid="action-edit"
        aria-label="编辑页面"
        :aria-pressed="mode === 'edit'"
        @click="mode = mode === 'edit' ? 'read' : 'edit'"
      >
        <i class="i-lucide-square-pen" aria-hidden="true"></i>
      </button>
      <button
        type="button"
        class="icon"
        :class="{ active: mode === 'history' }"
        data-testid="action-history"
        aria-label="查看版本历史"
        :aria-pressed="mode === 'history'"
        @click="toggleHistory"
      >
        <i class="i-lucide-history" aria-hidden="true"></i>
      </button>
      <button type="button" class="icon" aria-label="查看页面洞察" @click="store.middleTab = 'summary'">
        <i class="i-lucide-lightbulb" aria-hidden="true"></i>
      </button>
      <button v-if="store.page?.canManage" type="button" class="icon" aria-label="归档页面" data-testid="action-archive" @click="archiveDialogOpen = true">
        <i class="i-lucide-archive" aria-hidden="true"></i>
      </button>
    </nav>

    <ArchiveConfirmDialog
      :open="archiveDialogOpen"
      scope-label="页面"
      :title="store.page?.title ?? ''"
      :pending="archivePending"
      @cancel="archiveDialogOpen = false"
      @confirm="archivePage"
    />

    <PageReader v-if="mode !== 'edit'" :kb-id="numericKbId ?? undefined" :page-id="numericPageId ?? undefined" :anchor-resolution="anchorResolution" @comment="beginSelectionComment" />
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
import CollaborationPanel from '../../workspace/CollaborationPanel.vue';
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
  }
}
onMounted(loadCurrentPage);
watch(() => [props.kbId, props.pageId, route.query.anchor], loadCurrentPage);

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

/** 仅在二次弹窗确认时触发；带防抖，且服务端调用幂等。 */
async function archivePage() {
  if (!numericKbId.value || !numericPageId.value || archivePending.value) return;
  archivePending.value = true;
  try {
    await api.post(`/knowledge-bases/${numericKbId.value}/pages/${numericPageId.value}/archive`, {});
    archiveDialogOpen.value = false;
    await store.loadTree(numericKbId.value);
    await router.replace({ name: 'workspace', params: { kbId: numericKbId.value } });
  } catch {
    // 当服务端拒绝归档时，保持阅读器与对话框打开。
  } finally {
    archivePending.value = false;
  }
}
</script>

<style scoped>
.page-actions {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
  margin-bottom: 4px;
}
.icon {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  border-radius: 7px;
  color: #737a7a;
  cursor: pointer;
  font-size: 16px;
}
.icon:hover {
  background: #eef1ef;
  color: #303636;
}
.icon.active {
  background: #edf3ef;
  color: var(--kwiki-green-dark);
}
.overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: rgba(22, 31, 27, 0.25);
  backdrop-filter: blur(1px);
}
</style>
