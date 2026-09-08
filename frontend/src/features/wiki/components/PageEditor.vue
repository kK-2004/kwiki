<template>
  <section class="editor" data-testid="page-editor" aria-label="页面编辑器">
    <div class="toolbar" role="toolbar" aria-label="编辑工具栏">
      <button type="button" class="tool" aria-label="一级标题">H1</button>
      <button type="button" class="tool" aria-label="二级标题">H2</button>
      <button type="button" class="tool" aria-label="加粗"><strong>B</strong></button>
      <span class="spacer"></span>
      <button
        v-for="mode of ['edit', 'preview'] as const"
        :key="mode"
        type="button"
        class="tool mode"
        :class="{ active: viewMode === mode }"
        :aria-pressed="viewMode === mode"
        :data-testid="`editor-${mode}`"
        @click="viewMode = mode"
      >
        {{ mode === 'edit' ? '编辑' : '预览' }}
      </button>
    </div>
    <MarkdownEditorAdapter v-if="viewMode === 'edit'" v-model="draft" />
    <div v-else data-testid="editor-preview-content" class="markdown article preview" v-html="previewHtml"></div>
    <div class="editor-foot">
      <span class="draft" role="status" data-testid="editor-dirty">{{
        dirty ? '有未保存的修改' : '尚未修改'
      }}</span>
      <div class="buttons">
        <button type="button" class="btn" @click="emit('cancel')">取消</button>
        <button type="button" class="btn" :disabled="!dirty" data-testid="editor-save-draft" @click="save">
          保存草稿
        </button>
        <button type="button" class="btn primary" data-testid="editor-publish" @click="publish">
          发布
        </button>
      </div>
    </div>
    <p v-if="conflict" role="alert" data-testid="editor-conflict">
      页面已被他人修改（409），请重新载入后再试
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import MarkdownEditorAdapter from './MarkdownEditorAdapter.vue';
import { renderMarkdown } from './render';
import { useWikiStore } from '../store';

const props = defineProps<{ kbId: number; pageId: number }>();
const emit = defineEmits<{ cancel: []; published: [] }>();
const store = useWikiStore();

const initial = computed(() => store.page?.markdown ?? '');
const draft = ref(initial.value);
const viewMode = ref<'edit' | 'preview'>('edit');
const conflict = ref(false);

const dirty = computed(() => draft.value !== initial.value);
const previewHtml = computed(() => renderMarkdown(draft.value));

// The page may finish loading after the editor opens; adopt it while clean.
watch(initial, (value) => {
  if (!dirty.value) {
    draft.value = value;
  }
});

async function save() {
  try {
    conflict.value = false;
    await store.saveDraft(props.kbId, props.pageId, draft.value);
    await store.loadPage(props.kbId, props.pageId);
  } catch (error) {
    if (
      typeof error === 'object' &&
      error !== null &&
      (error as { code?: string }).code === 'http_409'
    ) {
      conflict.value = true;
    }
  }
}

async function publish() {
  await store.publishPage(props.kbId, props.pageId);
  await Promise.all([store.loadPage(props.kbId, props.pageId), store.loadTree(props.kbId)]);
  emit('published');
}
</script>

<style scoped>
.editor {
  margin-top: 22px;
  border: 1px solid #dfe4e2;
  border-radius: 9px;
  overflow: hidden;
  box-shadow: 0 8px 30px rgba(27, 52, 40, 0.06);
  background: var(--kwiki-panel);
}
.toolbar {
  min-height: 47px;
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 7px 9px;
  border-bottom: 1px solid var(--kwiki-line);
  background: var(--kwiki-sidebar-bg);
}
.tool {
  min-width: 31px;
  height: 31px;
  padding: 0 8px;
  border: 0;
  background: none;
  border-radius: 5px;
  color: #626969;
  cursor: pointer;
  font: inherit;
}
.tool:hover,
.tool.active {
  background: #edf3ef;
  color: var(--kwiki-green-dark);
}
.spacer {
  flex: 1;
}
.preview {
  min-height: 400px;
  padding: 22px;
}
.editor-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border-top: 1px solid var(--kwiki-line);
  background: var(--kwiki-sidebar-bg);
}
.draft {
  color: var(--kwiki-muted);
  font-size: 12px;
}
.buttons {
  display: flex;
  gap: 7px;
}
.btn {
  min-height: 34px;
  padding: 0 13px;
  border: 1px solid #dfe3e2;
  border-radius: 6px;
  background: var(--kwiki-panel);
  font-weight: 600;
  color: #555c5c;
  cursor: pointer;
  font: inherit;
}
.btn:hover:not(:disabled) {
  border-color: #bfc7c4;
}
.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.btn.primary {
  border-color: var(--kwiki-green);
  background: var(--kwiki-green);
  color: #fff;
}
.markdown.article {
  font-size: 15px;
  line-height: 1.85;
  color: #343a3a;
}
.markdown :deep(h1) {
  font-size: 25px;
}
.markdown :deep(h2) {
  margin: 27px 0 9px;
  font-size: 19px;
}
.markdown :deep(h3) {
  margin: 23px 0 8px;
  font-size: 17px;
}
@media (max-width: 640px) {
  .editor-foot {
    align-items: flex-start;
    flex-direction: column;
  }
  .buttons {
    width: 100%;
  }
  .buttons .btn {
    flex: 1;
  }
}
</style>
