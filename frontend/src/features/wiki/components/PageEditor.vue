<template>
  <section class="editor" data-testid="page-editor" aria-label="页面编辑器">
    <div class="toolbar" role="toolbar" aria-label="编辑工具栏">
      <button type="button" class="tool" aria-label="一级标题" data-testid="tool-h1" @click="command('h1')">H1</button>
      <button type="button" class="tool" aria-label="二级标题" data-testid="tool-h2" @click="command('h2')">H2</button>
      <button type="button" class="tool" aria-label="加粗" data-testid="tool-bold" @click="command('bold')"><strong>B</strong></button>
      <div class="menu">
        <button type="button" class="tool tool-icon" aria-label="代码块语言" data-testid="tool-code" @click="openMenu = openMenu === 'CODE' ? null : 'CODE'">
          <i class="i-lucide-code-2" aria-hidden="true"></i><i class="i-lucide-chevron-down chevron" aria-hidden="true"></i>
        </button>
        <div v-if="openMenu === 'CODE'" class="menu-panel language-menu">
          <button v-for="language in codeLanguages" :key="language.value" type="button" @click="insertCode(language.value)">{{ language.label }}</button>
        </div>
      </div>
      <span class="divider" aria-hidden="true"></span>
      <div v-for="menu in mediaMenus" :key="menu.kind" class="menu">
        <button type="button" class="tool" :aria-expanded="openMenu === menu.kind"
                :aria-label="`${menu.label}菜单`" :data-testid="`tool-${menu.kind.toLowerCase()}`"
                @click="openMenu = openMenu === menu.kind ? null : menu.kind">
          {{ menu.label }} <i class="i-lucide-chevron-down chevron" aria-hidden="true"></i>
        </button>
        <div v-if="openMenu === menu.kind" class="menu-panel" :data-testid="`menu-${menu.kind.toLowerCase()}`">
          <button type="button" @click="beginUpload(menu.kind)">本地上传</button>
          <button type="button" @click="beginExternalLink(menu.kind)">已有链接</button>
        </div>
      </div>
      <button type="button" class="tool tool-icon" aria-label="上传附件" data-testid="tool-attachment" @click="beginUpload('ATTACHMENT')">
        <i class="i-lucide-paperclip" aria-hidden="true"></i><span>附件</span>
      </button>
      <span class="spacer"></span>
      <div class="menu">
        <button type="button" class="tool" :aria-expanded="exportOpen" aria-label="导出菜单" data-testid="tool-export" @click="exportOpen = !exportOpen">导出 <i class="i-lucide-chevron-down chevron" aria-hidden="true"></i></button>
        <div v-if="exportOpen" class="menu-panel" data-testid="menu-export">
          <button type="button" :disabled="exporting" @click="exportAs('md')">Markdown</button>
          <button type="button" :disabled="exporting" @click="exportAs('html')">HTML</button>
        </div>
      </div>
      <button
        v-for="mode of ['source', 'preview'] as const"
        :key="mode"
        type="button"
        class="tool mode"
        :class="{ active: viewMode === mode }"
        :aria-pressed="viewMode === mode"
        :data-testid="`editor-${mode}`"
        @click="viewMode = mode"
      >
        {{ mode === 'source' ? '源码' : '预览' }}
      </button>
    </div>
    <MarkdownEditorAdapter
      v-if="viewMode === 'source'"
      ref="editor"
      v-model="draft"
      :kb-id="props.kbId"
    />
    <div v-else data-testid="editor-preview-content" class="markdown article preview" @click="copyPreviewCode">
      <template v-for="(part, index) in previewParts" :key="index">
        <div v-if="part.type === 'html'" class="preview-chunk" v-html="part.html"></div>
        <MediaBlock
          v-else
          :key="`media-${index}-${part.media.src}`"
          class="preview-media"
          :media="part.media"
          :resolver="mediaResolver"
          :kb-id="props.kbId"
        />
      </template>
    </div>
    <div class="editor-foot">
      <span class="draft" role="status" data-testid="editor-dirty">{{
        dirty ? '有未保存的修改' : '尚未修改'
      }}</span>
      <div class="buttons">
        <button type="button" class="btn" @click="emit('cancel')">取消</button>
        <button type="button" class="btn" :disabled="!dirty" data-testid="editor-save-draft" @click="save()">
          保存草稿
        </button>
        <button type="button" class="btn primary" :disabled="publishBlocked" :title="publishTitle" data-testid="editor-publish" @click="publishDialogOpen = true">
          发布
        </button>
      </div>
    </div>
    <p v-if="conflict" role="alert" data-testid="editor-conflict">
      页面已被他人修改（409），请重新载入后再试
    </p>
    <p v-if="publishBlocked" class="upload-note" role="status">
      仍有媒体上传进行中，发布前请等待完成或移除失败项。
    </p>
    <p v-if="exportError" class="upload-note" role="alert">{{ exportError }}</p>
    <div v-if="publishDialogOpen" class="dialog-overlay" @click.self="publishDialogOpen = false">
      <section class="publish-dialog" role="dialog" aria-modal="true" aria-label="发布页面">
        <header><h2>发布页面</h2><button type="button" class="tool tool-icon" aria-label="关闭" @click="publishDialogOpen = false"><i class="i-lucide-x" /></button></header>
        <label>发布说明<textarea v-model="publishNote" rows="4" placeholder="留空后由 AI 根据本次内容变更生成"></textarea></label>
        <p>首次发布会概括页面内容，后续发布会根据与上一发布版本的差异生成说明。</p>
        <footer><button type="button" class="btn" @click="publishDialogOpen = false">取消</button><button type="button" class="btn primary" :disabled="publishing" @click="publish">{{ publishing ? '发布中…' : '确认发布' }}</button></footer>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * Page editor toolbar: selection-based formatting commands (heading, bold,
 * fenced code with backtick-aware fences), image/audio/video
 * menus in the fixed order 本地上传 → 已有链接, source-mode toggle, and
 * Markdown/HTML export of the current draft snapshot. Publishing saves the
 * current draft first and is blocked while media uploads are in flight.
 */
import { computed, onMounted, ref, watch } from 'vue';
import MarkdownEditorAdapter from './MarkdownEditorAdapter.vue';
import MediaBlock from './MediaBlock.vue';
import { renderMarkdownParts } from './render';
import { createMediaPreviewResolver } from './mediaResolver';
import type { MediaSourceResolver } from '@kk-2004/ui-components/components/KMediaViewer';
import { api, errorMessage, getAuthToken } from '../api';
import { useWikiStore } from '../store';

const props = defineProps<{ kbId: number; pageId: number }>();
const emit = defineEmits<{ cancel: []; published: [] }>();
const store = useWikiStore();

const initial = computed(() => store.page?.markdown ?? '');
const draft = ref(initial.value);
const savedDraft = ref(initial.value);
const viewMode = ref<'source' | 'preview'>('source');
const conflict = ref(false);
const editor = ref<InstanceType<typeof MarkdownEditorAdapter>>();
const openMenu = ref<'IMAGE' | 'AUDIO' | 'VIDEO' | 'CODE' | null>(null);
const exportOpen = ref(false);
const exporting = ref(false);
const exportError = ref('');
const publishDialogOpen = ref(false);
const publishNote = ref('');
const publishing = ref(false);
/** 共享预览解析器：每个编辑器对每个附件只获取一次已签名 URL。 */
const mediaResolver = ref<MediaSourceResolver>(() => null);
watch(
  () => props.kbId,
  kbId => { mediaResolver.value = createMediaPreviewResolver(kbId); },
  { immediate: true },
);

const mediaMenus = [
  { kind: 'IMAGE' as const, label: '图片' },
  { kind: 'AUDIO' as const, label: '音频' },
  { kind: 'VIDEO' as const, label: '视频' },
];
const codeLanguages = [
  { value: '', label: '纯文本' }, { value: 'javascript', label: 'JavaScript' },
  { value: 'typescript', label: 'TypeScript' }, { value: 'java', label: 'Java' },
  { value: 'python', label: 'Python' }, { value: 'sql', label: 'SQL' },
  { value: 'json', label: 'JSON' }, { value: 'bash', label: 'Shell' },
];

const dirty = computed(() => draft.value !== savedDraft.value);
const pendingUploads = computed(() => editor.value?.pendingUploadCount ?? 0);
const failedUploads = computed(() => editor.value?.hasFailedUploads ?? false);
const publishBlocked = computed(() => pendingUploads.value > 0 || failedUploads.value);
const publishTitle = computed(() =>
  publishBlocked.value ? '等待媒体上传完成后再发布' : '先保存当前草稿，再发布该修订');

/**
 * Preview renders markdown into html/media parts; media parts are real Vue
 * components so players survive typing and only one viewer per media exists.
 */
const previewParts = computed(() => renderMarkdownParts(draft.value));

onMounted(async () => {
  try {
    const saved = await store.loadDraft(props.kbId, props.pageId);
    draft.value = saved.markdown;
    savedDraft.value = saved.markdown;
  } catch { /* 没有独立草稿的页面从已发布内容开始。 */ }
});

// 页面可能在编辑器打开之后才加载完成；在页面未被修改时接受它。
watch(initial, (value) => {
  if (!dirty.value) {
    draft.value = value;
    savedDraft.value = value;
  }
});

function command(name: 'h1' | 'h2' | 'bold' | 'code') {
  viewMode.value = 'source';
  editor.value?.applyCommand(name);
}

function insertCode(language: string) { openMenu.value = null; viewMode.value = 'source'; editor.value?.applyCommand('code', language); }

async function copyPreviewCode(event: MouseEvent) {
  const button = (event.target as HTMLElement).closest<HTMLButtonElement>('[data-copy-code]');
  if (!button) return;
  const code = button.parentElement?.querySelector('code')?.textContent ?? '';
  try {
    await navigator.clipboard.writeText(code);
    button.dataset.copied = 'true';
    button.setAttribute('aria-label', '已复制');
    window.setTimeout(() => {
      button.removeAttribute('data-copied');
      button.setAttribute('aria-label', '复制代码');
    }, 1200);
  } catch {
    button.setAttribute('aria-label', '复制失败');
  }
}

function beginUpload(kind: 'IMAGE' | 'AUDIO' | 'VIDEO' | 'ATTACHMENT') {
  openMenu.value = null;
  viewMode.value = 'source';
  editor.value?.pickFile(kind);
}

function beginExternalLink(kind: 'IMAGE' | 'AUDIO' | 'VIDEO') {
  openMenu.value = null;
  viewMode.value = 'source';
  const url = window.prompt('输入 http(s) 媒体链接');
  if (url && url.trim()) editor.value?.insertExternalLink(kind, url.trim());
}

async function save(changeNote = '') {
  try {
    conflict.value = false;
    await store.saveDraft(props.kbId, props.pageId, draft.value, changeNote);
    savedDraft.value = draft.value;
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

/** 发布总是先保存当前草稿；在上传进行中时被阻止。 */
async function publish() {
  if (publishBlocked.value) return;
  publishing.value = true;
  try {
    let note = publishNote.value.trim();
    if (!note) {
      const generated = await api.post<{ changeNote: string }>(`/knowledge-bases/${props.kbId}/pages/${props.pageId}/publication-note`, { markdown: draft.value });
      note = generated.changeNote;
      publishNote.value = note;
    }
    await save(note);
    await store.publishPage(props.kbId, props.pageId);
    await Promise.all([store.loadPage(props.kbId, props.pageId), store.loadTree(props.kbId)]);
    publishDialogOpen.value = false;
    publishNote.value = '';
    emit('published');
  } finally { publishing.value = false; }
}

async function exportAs(format: 'md' | 'html') {
  exportOpen.value = false;
  exporting.value = true;
  exportError.value = '';
  try {
    const response = await fetch(
      `/api/v1/knowledge-bases/${props.kbId}/pages/${props.pageId}/export`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(getAuthToken() ? { Authorization: `Bearer ${getAuthToken()}` } : {}),
        },
        body: JSON.stringify({ format, markdown: draft.value }),
      },
    );
    if (!response.ok) {
      exportError.value = `导出失败（${response.status}），请稍后重试`;
      return;
    }
    const blob = await response.blob();
    downloadBlob(blob, fileNameOf(response, format));
  } catch (error) {
    exportError.value = errorMessage(error, '导出失败，请稍后重试');
  } finally {
    exporting.value = false;
  }
}

function fileNameOf(response: Response, format: 'md' | 'html'): string {
  const disposition = response.headers.get('Content-Disposition') || '';
  const match = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (match) {
    try {
      return decodeURIComponent(match[1]);
    } catch {
      /* 继续向下执行 */
    }
  }
  const title = (store.page?.title || 'export').replace(/\s+/g, '-');
  return `${title}.${format}`;
}

function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  URL.revokeObjectURL(url);
}
</script>

<style scoped>
.editor {
  margin-top: 22px;
  border: 1px solid #dfe4e2;
  border-radius: 9px;
  overflow: visible;
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
  flex-wrap: wrap;
  position: relative;
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
.tool:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.tool-icon { display: inline-flex; align-items: center; justify-content: center; gap: 5px; }
.chevron { font-size: 11px; vertical-align: -1px; }
.spacer {
  flex: 1;
}
.divider {
  width: 1px;
  height: 18px;
  background: var(--kwiki-line);
  margin: 0 4px;
}
.menu {
  position: relative;
}
.menu-panel {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  z-index: 20;
  background: #fff;
  border: 1px solid #e0e8e2;
  border-radius: 8px;
  box-shadow: 0 10px 30px rgba(27, 52, 40, 0.12);
  display: grid;
  min-width: 150px;
}
.menu-panel button {
  border: 0;
  background: none;
  text-align: left;
  padding: 10px 14px;
  font: inherit;
  font-size: 12px;
  color: #4a5454;
  cursor: pointer;
}
.menu-panel button:hover {
  background: #f0f7f2;
}
.preview {
  min-height: 400px;
  padding: 22px;
}
.preview-chunk:first-child > :first-child,
.preview-chunk + .preview-media + .preview-chunk > :first-child {
  margin-top: 0;
}
.preview-media {
  display: block;
  margin: 9px 0;
}
.preview :deep(.code-block) {
  position: relative;
}
.preview :deep(.code-copy) {
  position: absolute;
  top: 10px;
  right: 10px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 30px;
  padding: 0 9px;
  border: 1px solid #d9e1dc;
  border-radius: 6px;
  opacity: 0;
  background: rgba(255, 255, 255, 0.94);
  color: #53625a;
  cursor: pointer;
  transition: opacity 0.15s, background-color 0.15s;
}
.preview :deep(.code-block:hover .code-copy),
.preview :deep(.code-copy:focus-visible) {
  opacity: 1;
}
.preview :deep(.code-copy[data-copied='true']) {
  color: var(--kwiki-green-dark);
  background: #eff8f2;
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
.upload-note {
  color: #8a6c42;
  font-size: 12px;
  margin: 8px 12px;
}
.dialog-overlay { position: fixed; inset: 0; z-index: 90; display: grid; place-items: center; padding: 20px; background: #19332244; }
.publish-dialog { width: min(520px, 100%); padding: 24px; border-radius: 16px; background: #fff; box-shadow: 0 20px 70px #1a332622; }
.publish-dialog header,.publish-dialog footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.publish-dialog h2 { margin: 0; font-size: 20px; }
.publish-dialog label { display: grid; gap: 8px; margin-top: 22px; color: #506158; font-size: 13px; }
.publish-dialog textarea { resize: vertical; padding: 12px; border: 1px solid #dce5df; border-radius: 8px; font: inherit; }
.publish-dialog p { color: #8a958e; font-size: 12px; line-height: 1.6; }
.publish-dialog footer { justify-content: flex-end; margin-top: 20px; }
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
.markdown :deep(img),
.markdown :deep(video) {
  max-width: 100%;
  height: auto;
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
