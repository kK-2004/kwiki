<template>
  <div class="wiki-layout" data-testid="workspace">
    <aside class="tree-column" :class="{ 'mobile-open': treeOpen }" aria-label="Wiki 页面导航" data-testid="middle-column">
      <header class="tree-header"><RouterLink to="/knowledge-bases" class="back-link"><i class="i-lucide-arrow-left" /> 所有知识库</RouterLink><div class="kb-title"><span class="kb-icon"><i class="i-lucide-library" /></span><strong>{{ base?.name || '加载中…' }}</strong><RouterLink v-if="base?.canManage" :to="`/knowledge-bases/${kbId}/trash`" class="ui-icon" aria-label="回收站" data-testid="kb-trash-link"><i class="i-lucide-archive" /></RouterLink><RouterLink v-if="base?.canManage" :to="`/knowledge-bases/${kbId}/settings`" class="ui-icon" aria-label="知识库设置"><i class="i-lucide-settings-2" /></RouterLink></div><p>{{ base?.description || '让知识有序，让协作发生' }}</p></header>
      <div class="tree-tabs" role="tablist" aria-label="Wiki 视图"><button role="tab" :aria-selected="store.middleTab === 'knowledge'" :class="{ active: store.middleTab === 'knowledge' }" @click="store.middleTab = 'knowledge'">目录 <span>{{ pageCount }}</span></button><button role="tab" :aria-selected="store.middleTab === 'summary'" :class="{ active: store.middleTab === 'summary' }" @click="store.middleTab = 'summary'">摘要</button><button v-if="base?.canUpload" class="ui-icon" aria-label="新建页面" @click="beginCreate('PAGE')"><i class="i-lucide-file-plus" /></button><button v-if="base?.canUpload" class="ui-icon" aria-label="新建目录" @click="beginCreate('FOLDER')"><i class="i-lucide-folder-plus" /></button></div>
      <p v-if="treeError" class="ui-error">{{ treeError }} <button class="ui-button" @click="load">重试</button></p>
      <p v-else-if="loading" class="tree-state">正在加载目录…</p>
      <WikiSearch v-else-if="store.middleTab === 'knowledge'" />
      <div v-else class="summary-scroll"><WikiSummaryPanel /></div>
      <RouterLink v-if="base?.canUpload" :to="`/knowledge-bases?import=${kbId}`" class="import-link"><i class="i-lucide-upload" /> 导入文档</RouterLink>
    </aside>
    <section class="wiki-content" data-testid="content-column"><header class="workspace-head"><button class="ui-icon mobile-tree" aria-label="打开页面目录" @click="treeOpen = !treeOpen"><i class="i-lucide-panel-left-open" /></button><nav class="crumbs" aria-label="面包屑"><RouterLink to="/knowledge-bases">知识库</RouterLink><span>›</span><RouterLink :to="`/knowledge-bases/${kbId}`">{{ base?.name || '加载中…' }}</RouterLink><template v-for="node in ancestors" :key="node.id"><span>/</span><RouterLink :to="`/knowledge-bases/${kbId}/${node.id}`" :aria-current="node.id === Number(pageId) ? 'page' : undefined">{{ node.title }}</RouterLink></template></nav><PageHeaderActions v-if="selectedNode?.nodeType === 'PAGE' && kbId && pageId" :kb-id="Number(kbId)" :page-id="Number(pageId)" @edit="invokePageAction('toggleEdit')" @history="invokePageAction('toggleHistory')" @archive="invokePageAction('openArchive')" /></header>
      <main class="document"><div v-if="!pageId || selectedNode?.nodeType === 'FOLDER'" class="overview"><span class="overview-icon"><i :class="selectedNode ? 'i-lucide-folder-open' : 'i-lucide-library'" /></span><p class="eyebrow">{{ selectedNode ? '文档目录' : '知识库概览' }}</p><h1>{{ selectedNode?.title || base?.name || '知识库' }}</h1><p class="description">{{ selectedNode ? '浏览此目录下的文档，或添加新的内容。' : base?.description || '将分散的信息整理为团队共享的知识。' }}</p><div class="overview-actions" v-if="base?.canUpload"><button class="ui-button primary" @click="beginCreate('PAGE')"><i class="i-lucide-plus" />新建文档</button><RouterLink class="ui-button" :to="`/knowledge-bases?import=${kbId}`"><i class="i-lucide-upload" />导入文档</RouterLink></div><div class="contents-header"><h2>{{ selectedNode ? '目录内容' : '内容目录' }}</h2><span>{{ overviewCount }} 项</span></div><p v-if="importError && !selectedNode" class="import-list-error" role="alert">{{ importError }} <button type="button" @click="loadImports">重试</button></p><p v-if="!overviewCount" class="empty-directory">这里还没有内容，创建第一篇文档开始整理知识。</p><div v-for="job in visiblePendingImports" :key="`import-${job.jobId}`" class="overview-row import-row"><i class="i-lucide-file-clock" /><span class="row-main"><strong>{{ job.fileName }}</strong><small v-if="job.state === 'FAILED'">{{ job.error || '文档解析失败，请重试' }}</small></span><span class="import-status" :class="job.state.toLowerCase()"><span v-if="isImportActive(job)" class="status-spinner" aria-hidden="true"></span>{{ importStatus(job.state) }}</span><button v-if="job.state === 'FAILED'" type="button" class="retry-import" :disabled="retryingJobId === job.jobId" @click="retryImport(job)">{{ retryingJobId === job.jobId ? '重试中…' : '重试' }}</button></div><RouterLink v-for="node in overviewNodes" :key="node.id" :to="`/knowledge-bases/${kbId}/${node.id}`" class="overview-row"><i :class="node.nodeType === 'FOLDER' ? 'i-lucide-folder' : 'i-lucide-file-text'" /><span class="row-main"><strong>{{ node.title }}</strong></span><span v-if="importForPage(node.id) && importForPage(node.id)!.state !== 'SUCCEEDED'" class="import-status" :class="importForPage(node.id)!.state.toLowerCase()">{{ importStatus(importForPage(node.id)!.state) }}</span><i class="i-lucide-chevron-right" /></RouterLink></div><RouterView v-else v-slot="{ Component }"><component :is="Component" ref="pageComponent" /></RouterView></main>
    </section>
    <div v-if="creating" class="create-overlay" @click.self="creating = false"><form class="create-dialog" role="dialog" aria-modal="true" :aria-label="nodeType === 'FOLDER' ? '新建目录' : '新建文档'" @submit.prevent="create"><h2>{{ nodeType === 'FOLDER' ? '新建目录' : '新建文档' }}</h2><label>名称<input v-model.trim="title" class="ui-input" required maxlength="200" autofocus /></label><p v-if="createError" class="ui-error">{{ createError }}</p><footer><button type="button" class="ui-button" @click="creating = false">取消</button><button class="ui-button primary" :disabled="saving || !title">{{ saving ? '创建中…' : '创建' }}</button></footer></form></div>
  </div>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { RouterLink, RouterView, useRouter } from 'vue-router';
import WikiSearch from './WikiSearch.vue'; import WikiSummaryPanel from './WikiSummaryPanel.vue'; import PageHeaderActions from './PageHeaderActions.vue';
import { useWikiStore } from '../store'; import { api, errorMessage, type TreeNodeDto } from '../api'; import { showMissingWikiToast } from '../toast';
const props = defineProps<{ kbId?: string; pageId?: string }>(); const store = useWikiStore(); const router = useRouter();
type PageActionTarget = { toggleEdit?: () => void; toggleHistory?: () => void; openArchive?: () => void };
const pageComponent = ref<PageActionTarget | null>(null);
function invokePageAction(action: keyof PageActionTarget) { pageComponent.value?.[action]?.(); }
const base = ref<{ name: string; description?: string; canManage: boolean; canUpload: boolean }>();
const treeOpen = ref(false); const loading = ref(false); const treeError = ref(''); let version = 0;
function pathTo(nodes: TreeNodeDto[], id: number): TreeNodeDto[] { for (const node of nodes) { if (node.id === id) return [node]; const path = pathTo(node.children || [], id); if (path.length) return [node, ...path]; } return []; }
const ancestors = computed(() => pathTo(store.tree, Number(props.pageId))); const selectedNode = computed(() => ancestors.value.at(-1));
const overviewNodes = computed(() => selectedNode.value?.nodeType === 'FOLDER' ? selectedNode.value.children : store.tree);
type ImportJob = { jobId: number; state: string; pageId?: number | null; fileName: string; warnings?: string[]; error?: string | null };
const importJobs = ref<ImportJob[]>([]); const importError = ref(''); const retryingJobId = ref<number | null>(null);
const visiblePendingImports = computed(() => selectedNode.value ? [] : importJobs.value.filter(job => job.state !== 'SUCCEEDED'));
const importsByPageId = computed(() => new Map(importJobs.value.filter(job => job.pageId).map(job => [Number(job.pageId), job])));
const overviewCount = computed(() => overviewNodes.value.length + visiblePendingImports.value.length);
function importForPage(pageId: number) { return importsByPageId.value.get(pageId); }
function isImportActive(job: ImportJob) { return job.state === 'STORED' || job.state === 'PARSING'; }
function importStatus(state: string) { return ({ STORED: '等待解析', PARSING: '解析中', FAILED: '解析失败', SUCCEEDED: '已完成' } as Record<string, string>)[state] || state; }
function count(nodes: TreeNodeDto[]): number { return nodes.reduce((n, node) => n + (node.nodeType === 'PAGE' ? 1 : 0) + count(node.children || []), 0); }
const pageCount = computed(() => count(store.tree));
let importPoll: ReturnType<typeof setTimeout> | undefined;
function stopImportPoll() { if (importPoll) clearTimeout(importPoll); importPoll = undefined; }
function treeContainsPage(nodes: TreeNodeDto[], pageId: number): boolean { return nodes.some(node => node.id === pageId || treeContainsPage(node.children || [], pageId)); }
function scheduleImportPoll() { stopImportPoll(); if (importJobs.value.some(isImportActive)) importPoll = setTimeout(() => { void loadImports(); }, 1500); }
async function loadImports() {
  const kb = Number(props.kbId); if (!kb) return;
  try {
    const jobs = await api.json<ImportJob[]>(`/knowledge-bases/${kb}/imports?limit=100`);
    if (kb !== Number(props.kbId)) return;
    importJobs.value = jobs; importError.value = '';
    if (jobs.some(job => job.state === 'SUCCEEDED' && job.pageId && !treeContainsPage(store.tree, Number(job.pageId)))) await store.loadTree(kb);
  } catch (e) { if ((e as { name?: string })?.name !== 'AbortError') importError.value = errorMessage(e, '导入状态加载失败'); }
  finally { scheduleImportPoll(); }
}
async function load() { const current = ++version; stopImportPoll(); loading.value = true; treeError.value = ''; importError.value = ''; importJobs.value = []; base.value = undefined; store.tree = []; store.selectPage(null); try { const [value] = await Promise.all([api.json<NonNullable<typeof base.value>>(`/knowledge-bases/${props.kbId}`), store.loadTree(Number(props.kbId))]); if (current === version) { base.value = value; await loadImports(); if (props.pageId && !treeContainsPage(store.tree, Number(props.pageId))) { showMissingWikiToast(); await router.replace(`/knowledge-bases/${props.kbId}`); } } } catch (e) { if (current === version) treeError.value = errorMessage(e, '知识库加载失败'); } finally { if (current === version) loading.value = false; } }
watch(() => props.kbId, load, { immediate: true }); watch(() => props.pageId, () => { treeOpen.value = false; });
onBeforeUnmount(stopImportPoll);
const creating = ref(false); const nodeType = ref<'PAGE' | 'FOLDER'>('PAGE'); const title = ref(''); const createError = ref(''); const saving = ref(false);
function beginCreate(type: 'PAGE' | 'FOLDER') { nodeType.value = type; title.value = ''; createError.value = ''; creating.value = true; }
async function create() {
  saving.value = true;
  try {
    const node = await api.post<{ id: number }>(`/knowledge-bases/${props.kbId}/nodes`, {
      title: title.value,
      nodeType: nodeType.value,
      parentId: selectedNode.value?.nodeType === 'FOLDER' ? selectedNode.value.id : null,
    });
    if (nodeType.value === 'PAGE') {
      await api.put(`/knowledge-bases/${props.kbId}/pages/${node.id}/draft`, {
        markdown: `# ${title.value}`,
      });
      await api.post(`/knowledge-bases/${props.kbId}/pages/${node.id}/publish`, {});
    }
    await store.loadTree(Number(props.kbId));
    creating.value = false;
    await router.push(`/knowledge-bases/${props.kbId}/${node.id}`);
  } catch (e) {
    createError.value = errorMessage(e, '创建失败');
  } finally {
    saving.value = false;
  }
}
async function retryImport(job: ImportJob) {
  if (retryingJobId.value) return;
  retryingJobId.value = job.jobId; importError.value = '';
  try {
    const updated = await api.post<ImportJob>(`/knowledge-bases/${props.kbId}/imports/${job.jobId}/retry`);
    importJobs.value = importJobs.value.map(item => item.jobId === updated.jobId ? updated : item);
    scheduleImportPoll();
  } catch (e) { importError.value = errorMessage(e, '重试失败，请稍后再试'); }
  finally { retryingJobId.value = null; }
}
</script>
<style scoped>
.wiki-layout{display:grid;grid-template-columns:292px minmax(0,1fr);height:100%;min-height:0}.tree-column{min-height:0;min-width:0;display:flex;flex-direction:column;padding:24px 16px 16px;border-right:1px solid var(--kwiki-line)}.back-link{font-size:12px;color:#89968c;text-decoration:none;display:flex;gap:5px;align-items:center;margin-bottom:24px}.kb-title{display:flex;align-items:center;gap:10px}.kb-title strong{font-size:17px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.kb-title .ui-icon{margin-left:auto;flex-shrink:0}.kb-icon{color:#38875b;background:#edf5f0;padding:9px;border-radius:8px;display:flex}.tree-header p{font-size:12px;line-height:1.6;color:#8b978f;margin-bottom:23px}.tree-tabs{display:flex;align-items:center;gap:4px;margin-bottom:16px;border-bottom:1px solid #edf0ed;padding-bottom:8px}.tree-tabs button:not(.ui-icon){padding:6px 9px;border:0;background:none;color:#8b958f;cursor:pointer}.tree-tabs button.active{color:#317c51;font-weight:600}.tree-tabs span{font-size:11px;margin-left:3px}.tree-tabs .ui-icon:first-of-type{margin-left:auto}.tree-state{color:#8b958f;font-size:13px}.summary-scroll{min-height:0;flex:1;overflow:auto}.import-link{margin-top:12px;display:flex;align-items:center;gap:8px;justify-content:center;padding:10px;border:1px dashed #d6e2da;border-radius:8px;color:#6c8274;font-size:12px;text-decoration:none}.wiki-content{min-width:0;min-height:0;display:flex;flex-direction:column}.workspace-head{min-height:70px;padding:22px 32px;border-bottom:1px solid #edf1ee;display:flex;align-items:center;gap:12px}.crumbs{display:flex;align-items:center;gap:10px;flex-wrap:wrap;font-size:12px;color:#8b978f}.crumbs a{text-decoration:none}.crumbs a:last-child{color:#375742;font-weight:500}.document{flex:1;min-height:0;overflow:auto;padding:28px 36px 80px}.overview{max-width:800px;margin:25px auto}.overview-icon{display:inline-flex;background:#eef6f0;color:#43875c;border-radius:14px;padding:17px;font-size:26px}.eyebrow{color:#7e9586;font-size:11px;margin-top:26px;letter-spacing:1px}h1{font-size:30px;font-weight:650;letter-spacing:-.6px;margin:10px 0 12px}.description{color:#849087;line-height:1.8}.overview-actions{display:flex;gap:10px;margin:26px 0 40px}.contents-header{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #e9eeea;margin-top:30px;padding-bottom:12px}.contents-header h2{font-size:14px;font-weight:600}.contents-header span{font-size:12px;color:#929b95}.overview-row{display:flex;align-items:center;gap:12px;padding:17px 10px;border-bottom:1px solid #f0f3f0;text-decoration:none;color:#557060}.overview-row:hover{background:#f8faf8}.overview-row>i:last-child{margin-left:4px;color:#a0aaa2}.row-main{min-width:0;flex:1;display:grid;gap:4px}.row-main strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:inherit;font-weight:500}.row-main small{color:#a45b5b;line-height:1.45}.import-row{color:#63746a}.import-row:hover{background:transparent}.import-status{flex:none;display:inline-flex;align-items:center;gap:6px;padding:4px 9px;border-radius:999px;background:#f0f5f1;color:#728178;font-size:11px}.import-status.parsing,.import-status.stored{background:#eef7f1;color:#4d8060}.import-status.failed{background:#fff0f0;color:#a54b4b}.import-status.succeeded{background:#eef7f1;color:#4d8060}.status-spinner{width:10px;height:10px;border:2px solid #bad3c2;border-top-color:#4d8060;border-radius:50%;animation:import-spin .8s linear infinite}.retry-import,.import-list-error button{padding:5px 9px;border:1px solid #dfcaca;border-radius:6px;background:#fff;color:#9c4c4c;cursor:pointer}.retry-import:disabled{opacity:.55;cursor:default}.import-list-error{margin:12px 0 0;padding:9px 10px;border-radius:7px;background:#fff7f7;color:#a45454;font-size:12px}@keyframes import-spin{to{transform:rotate(360deg)}}.empty-directory{padding:30px 0;color:#98a298;font-size:13px}.mobile-tree{display:none}.create-overlay{position:fixed;inset:0;display:grid;place-items:center;background:#182c2244;z-index:90}.create-dialog{width:min(440px,90vw);padding:28px;background:white;border-radius:16px;box-shadow:0 24px 80px #172e2026}.create-dialog h2{margin-top:0;font-size:20px}.create-dialog label{display:grid;gap:10px;color:#667c6d}.create-dialog footer{display:flex;justify-content:flex-end;gap:8px;margin-top:24px}@media(max-width:1100px){.wiki-layout{grid-template-columns:245px minmax(0,1fr)}.document{padding:24px}}@media(max-width:900px){.wiki-layout{grid-template-columns:minmax(0,1fr)}.tree-column{display:none}.tree-column.mobile-open{display:flex;position:absolute;inset:0 auto 0 0;width:280px;background:white;z-index:30;box-shadow:12px 0 40px #16322322}.mobile-tree{display:inline-grid}.workspace-head{padding:16px}.document{padding:20px}.overview{margin:10px auto}h1{font-size:25px}.import-row{align-items:flex-start;flex-wrap:wrap}.import-status{margin-left:auto}}
</style>
