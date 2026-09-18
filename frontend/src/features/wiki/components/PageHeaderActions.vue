<template>
  <nav v-if="pageId" class="page-toolbar" aria-label="页面操作">
    <div class="toolbar-actions">
      <CollaborationPanel
        v-if="page?.canManage"
        resource-type="PAGE"
        :resource-id="pageId"
        :knowledge-base-id="kbId"
      />
      <div class="action-group" aria-label="页面操作组">
        <button
          v-if="page?.canEdit"
          type="button"
          class="toolbar-icon"
          title="编辑页面"
          aria-label="编辑页面"
          data-testid="action-edit"
          @click="emit('edit')"
        >
          <i class="i-lucide-square-pen" aria-hidden="true"></i>
        </button>
        <button
          type="button"
          class="toolbar-icon"
          title="历史版本"
          aria-label="查看版本历史"
          data-testid="action-history"
          @click="emit('history')"
        >
          <i class="i-lucide-history" aria-hidden="true"></i>
        </button>
        <button
          v-if="page?.canManage"
          type="button"
          class="toolbar-icon"
          title="归档页面"
          aria-label="归档页面"
          data-testid="action-archive"
          @click="emit('archive')"
        >
          <i class="i-lucide-archive" aria-hidden="true"></i>
        </button>
      </div>
      <div class="toolbar-export" @click.stop>
        <button
          type="button"
          class="export-toggle"
          :aria-expanded="exportOpen"
          aria-label="导出当前修订"
          data-testid="reader-export"
          @click="exportOpen = !exportOpen"
        >
          <i class="i-lucide-download" aria-hidden="true"></i>
          导出
          <i class="i-lucide-chevron-down" aria-hidden="true"></i>
        </button>
        <div v-if="exportOpen" class="export-panel" role="menu">
          <button type="button" role="menuitem" :disabled="exporting" @click="exportRevision('md')">
            <i class="i-lucide-file-text" aria-hidden="true"></i>导出为 Markdown
          </button>
          <button type="button" role="menuitem" :disabled="exporting" @click="exportRevision('html')">
            <i class="i-lucide-file-code-2" aria-hidden="true"></i>导出为 HTML
          </button>
        </div>
      </div>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import CollaborationPanel from '../../workspace/CollaborationPanel.vue';
import { getAuthToken } from '../api';
import { useWikiStore } from '../store';

const props = defineProps<{ kbId: number; pageId?: number }>();
const emit = defineEmits<{ edit: []; history: []; archive: [] }>();
const store = useWikiStore();
const page = computed(() => store.page);
const exportOpen = ref(false);
const exporting = ref(false);

function closeExport() { exportOpen.value = false; }

async function exportRevision(format: 'md' | 'html') {
  if (!props.pageId || !store.page || exporting.value) return;
  exportOpen.value = false;
  exporting.value = true;
  try {
    const response = await fetch(`/api/v1/knowledge-bases/${props.kbId}/pages/${props.pageId}/export`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(getAuthToken() ? { Authorization: `Bearer ${getAuthToken()}` } : {}) },
      body: JSON.stringify({ format, revisionNo: store.page.revisionNo }),
    });
    if (!response.ok) return;
    const blob = await response.blob();
    const disposition = response.headers.get('Content-Disposition') || '';
    const match = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
    let name = `${store.page.title || 'export'}.${format}`;
    if (match) {
      try { name = decodeURIComponent(match[1]); } catch { /* 保留兜底文件名 */ }
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

onMounted(() => document.addEventListener('click', closeExport));
onBeforeUnmount(() => document.removeEventListener('click', closeExport));
</script>

<style scoped>
.page-toolbar { display:flex; align-items:center; margin-left:auto; flex-shrink:0; }
.toolbar-actions { display:flex; align-items:center; justify-content:flex-end; gap:8px; }
.action-group { display:flex; align-items:center; height:38px; padding:0 4px; border:1px solid #e1e7e3; border-radius:11px; background:#fbfcfb; }
.toolbar-icon { width:32px; height:32px; display:inline-flex; align-items:center; justify-content:center; border:1px solid transparent; border-radius:9px; background:transparent; color:#6f7b74; cursor:pointer; font-size:16px; transition:.16s ease; }
.toolbar-icon:hover, .toolbar-icon:focus-visible { border-color:#e4eae6; background:#f4f7f5; color:#2e4237; outline:none; }
.toolbar-export { position:relative; flex-shrink:0; }
.export-toggle { height:38px; display:inline-flex; align-items:center; gap:7px; padding:0 14px; border:1px solid #d8dfdb; border-radius:11px; background:#fff; color:#45534c; font:inherit; font-size:13px; cursor:pointer; transition:.16s ease; }
.export-toggle:hover, .export-toggle:focus-visible { border-color:#c4d3c9; background:#f7faf8; outline:none; }
.export-toggle i:first-child { color:#537663; }
.export-toggle i:last-child { font-size:13px; }
.export-panel { position:absolute; top:calc(100% + 8px); right:0; z-index:30; min-width:180px; display:grid; gap:2px; padding:6px; border:1px solid #e2e7e4; border-radius:12px; background:#fff; box-shadow:0 14px 45px rgba(28,51,39,.12); }
.export-panel button { width:100%; height:36px; display:flex; align-items:center; gap:8px; padding:0 10px; border:0; border-radius:8px; background:transparent; color:#45534c; font:inherit; font-size:12px; text-align:left; cursor:pointer; }
.export-panel button:hover { background:#f4f7f5; }
.export-panel button:disabled { opacity:.55; cursor:default; }
@media (max-width:900px) { .page-toolbar { margin-left:0; } }
@media (max-width:560px) { .toolbar-actions { flex-wrap:wrap; justify-content:flex-start; } .toolbar-export { margin-left:auto; } }
</style>
