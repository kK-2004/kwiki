<template>
  <div data-testid="workspace-page">
    <nav class="page-actions" aria-label="页面操作">
      <button
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
        @click="mode = mode === 'history' ? 'read' : 'history'"
      >
        <i class="i-lucide-history" aria-hidden="true"></i>
      </button>
      <button type="button" class="icon" aria-label="查看页面洞察">
        <i class="i-lucide-lightbulb" aria-hidden="true"></i>
      </button>
      <button type="button" class="icon" aria-label="归档页面">
        <i class="i-lucide-archive" aria-hidden="true"></i>
      </button>
    </nav>

    <PageReader v-if="mode !== 'edit'" />
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

    <AgenticAnswerPanel @citation="onCitation" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import PageReader from './PageReader.vue';
import PageEditor from './PageEditor.vue';
import RevisionHistoryDrawer from './RevisionHistoryDrawer.vue';
import AgenticAnswerPanel from './AgenticAnswerPanel.vue';
import { useWikiStore } from '../store';
import type { CitationEntry } from '../sse';

const props = defineProps<{ kbId?: string; pageId?: string }>();
const store = useWikiStore();
const mode = ref<'read' | 'edit' | 'history'>('read');
const revisions = ref<Array<{ revisionNo: number; changeNote?: string; createdAt: string }>>([]);
const citationTarget = ref<CitationEntry | null>(null);

const numericKbId = computed(() => (props.kbId ? Number(props.kbId) : null));
const numericPageId = computed(() => (props.pageId ? Number(props.pageId) : null));

onMounted(() => {
  const kb = numericKbId.value;
  const page = numericPageId.value;
  if (kb && page) {
    void store.loadPage(kb, page);
  }
});

function onRestore(revisionNo: number) {
  // restore creates a new revision server-side; refresh history after action
  revisions.value = [...revisions.value];
  void revisionNo;
}

function onCitation(citation: CitationEntry) {
  citationTarget.value = citation;
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
