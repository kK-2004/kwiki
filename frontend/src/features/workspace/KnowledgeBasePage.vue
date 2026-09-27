<template>
  <main class="kb-page">
    <header class="page-heading"><div><p class="eyebrow">我的工作空间</p><h1>知识库<span class="base-count">{{ bases.length }}</span></h1><p class="sub">收藏经验、沉淀方法，让每一份知识都触手可及。</p></div><div class="header-actions"><button type="button" :disabled="!bases.some(base => base.canUpload)" @click="importFor(bases.find(base => base.canUpload)!.id)"><i class="i-lucide-upload" />导入文档</button><button type="button" class="primary-action" @click="modalError = ''; showCreate = true"><i class="i-lucide-plus" />新建知识库</button></div></header>
    <div class="list-toolbar"><span>全部知识库</span><label><i class="i-lucide-search" /><input v-model="baseSearch" placeholder="搜索知识库" aria-label="搜索知识库" /></label></div>
    <p v-if="error" class="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <section v-if="loading" class="state">正在加载知识库…</section>
    <section v-else-if="!bases.length" class="state"><span class="empty-library"><i class="i-lucide-library" /></span><strong>知识，从这里开始</strong><p>新建知识库，把文档与团队经验整理在一起。</p><button type="button" @click="showCreate = true">新建知识库</button></section>
    <section v-else-if="!filteredBases.length" class="state">没有找到匹配的知识库</section>
    <ul v-else class="base-grid"><li v-for="base in filteredBases" :key="base.id" class="base-card" role="link" tabindex="0" @click="open(base.id)" @keydown.enter.self.prevent="open(base.id)" @keydown.space.self.prevent="open(base.id)"><div class="card-top"><span class="library-icon"><i class="i-lucide-library" /></span><span class="role-badge">{{ base.canEditSettings ? '创建者 / 管理' : base.canManage ? '管理员' : base.canUpload ? '可编辑' : '只读' }}</span></div><span class="card-title">{{ base.name }}</span><p class="card-description">{{ base.description || '还没有简介，为这里的知识写一句介绍吧。' }}</p><div class="card-actions"><button type="button" @click.stop="open(base.id)">打开知识库<i class="i-lucide-arrow-right" /></button><span /><button v-if="base.canUpload" type="button" :aria-label="`导入到 ${base.name}`" @click.stop="importFor(base.id)"><i class="i-lucide-upload" /></button><RouterLink v-if="base.canManage" :to="`/knowledge-bases/${base.id}/settings`" class="settings-link" :aria-label="`设置 ${base.name}`" @click.stop><i class="i-lucide-settings-2" />设置</RouterLink></div></li></ul>

    <div v-if="showCreate" class="modal" role="dialog" aria-label="新建知识库"><form class="modal-card" @submit.prevent="create"><header><strong>新建知识库</strong><button type="button" @click="showCreate = false">×</button></header><label>名称<input v-model.trim="createForm.name" required maxlength="200" /></label><label>描述<textarea v-model.trim="createForm.description" maxlength="2000"></textarea></label><p v-if="modalError" class="error" role="alert">{{ modalError }}</p><footer><button type="button" @click="showCreate = false">取消</button><button type="submit" :disabled="saving || !createForm.name">{{ saving ? '创建中…' : '创建' }}</button></footer></form></div>
    <div v-if="showImport" class="modal" role="dialog" aria-label="导入文档"><form class="modal-card import-card" @submit.prevent="upload"><header><strong>导入为 Wiki 文档</strong><button type="button" @click="showImport = false" :disabled="saving">×</button></header><label>所在知识库<select v-model="importForm.kbId" required :disabled="saving"><option v-for="base in bases.filter(base => base.canUpload)" :key="base.id" :value="base.id">{{ base.name }}</option></select></label><label>可见范围<select :disabled="saving" v-model="importForm.audience" @change="scheduleCandidates"><option value="PRIVATE">仅自己</option><option value="SELECTED_MEMBERS">指定知识库成员</option><option value="KB_MEMBERS">知识库成员</option></select></label><section v-if="importForm.audience === 'SELECTED_MEMBERS'" class="audience-picker"><aside><strong>可选知识库</strong><button v-for="base in bases" :key="base.id" type="button" :class="{ selected: sourceKbId === base.id }" @click="sourceKbId = base.id; scheduleCandidates()">{{ base.name }}</button></aside><div class="audience-members"><strong>选择人员</strong><input v-model.trim="memberQuery" placeholder="按用户名搜索" @input="scheduleCandidates" /><div v-if="candidateLoading" class="hint">搜索中…</div><button v-for="candidate in candidates" :key="`${sourceKbId}-${candidate.id}`" type="button" class="candidate" :class="{ checked: isSelected(candidate.id) }" @click="toggleCandidate(candidate)">{{ isSelected(candidate.id) ? '✓ ' : '' }}{{ candidate.username }}<span>{{ candidate.displayName || '' }}</span></button><p v-if="selectedMembers.length" class="selected-list">已选：<span v-for="member in selectedMembers" :key="`${member.sourceKbId}-${member.userId}`" class="chip">{{ member.username }} <button type="button" @click="removeMember(member)">×</button></span></p></div></section><label>文件<input :disabled="saving" type="file" accept=".md,.markdown,.docx,.pdf,application/pdf" required @change="onFile" /></label><p class="hint">支持 Markdown、DOCX、PDF，单个文件不超过 20 MB。上传完成后可离开此页，解析进度会显示在知识库目录中。</p><p v-if="modalError" class="error" role="alert">{{ modalError }}</p><footer><button type="button" @click="showImport = false" :disabled="saving">取消</button><button type="submit" :disabled="saving || !importForm.file"><span v-if="saving" class="busy-spinner" aria-hidden="true"></span>{{ saving ? uploadStage : '开始导入' }}</button></footer></form></div>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { useWikiStore } from '../wiki/store';
import { api, errorMessage } from '../wiki/api';
import { uploadDirect, type UploadAttempt } from '../wiki/directUpload';


interface Base { id: number; uuid: string; name: string; description?: string; canManage?: boolean; canUpload?: boolean; canEditSettings?: boolean; }
interface Candidate { id: number; username: string; displayName?: string; }
interface SelectedMember extends Candidate { sourceKbId: number; userId: number; }
const route = useRoute(); const router = useRouter(); const wiki = useWikiStore();
const baseSearch = ref(''); const bases = ref<Base[]>([]);
const filteredBases = computed(() => bases.value.filter(base => (base.name + (base.description || '')).toLowerCase().includes(baseSearch.value.toLowerCase()))); const loading = ref(false); const saving = ref(false); const error = ref(''); const modalError = ref(''); const showCreate = ref(false); const showImport = ref(false);
const createForm = ref({ name: '', description: '' }); const importForm = ref<{ kbId: number | ''; audience: string; file: File | null }>({ kbId: '', audience: 'PRIVATE', file: null });
const sourceKbId = ref<number | ''>(''); const memberQuery = ref(''); const candidates = ref<Candidate[]>([]); const selectedMembers = ref<SelectedMember[]>([]); const candidateLoading = ref(false);
let candidateTimer: ReturnType<typeof setTimeout> | undefined;
async function load() { loading.value = true; error.value = ''; try { bases.value = await api.json<Base[]>('/knowledge-bases'); wiki.knowledgeBases = bases.value; if (!importForm.value.kbId) importForm.value.kbId = bases.value.find(base => base.canUpload)?.id ?? '';  } catch { error.value = '知识库加载失败，请重试'; } finally { loading.value = false; } }
async function create() { saving.value = true; modalError.value = ''; try { const base = await api.post<Base>('/knowledge-bases', createForm.value); showCreate.value = false; createForm.value = { name: '', description: '' }; await load(); if (base?.id) open(base.id); } catch { modalError.value = '知识库创建失败，请重试'; } finally { saving.value = false; } }
function importFor(id: number) { uploadAttempt = undefined; uploadKey = ''; importForm.value.file = null; importForm.value.kbId = id; showImport.value = true; modalError.value = ''; sourceKbId.value = id; memberQuery.value = ''; candidates.value = []; selectedMembers.value = []; }
function onFile(event: Event) { uploadAttempt = undefined; const file = (event.target as HTMLInputElement).files?.[0] ?? null; modalError.value = ''; uploadKey = ''; importForm.value.file = file; if (file && (file.size > 20 * 1024 * 1024 || file.size === 0 || !/\.(md|markdown|docx|pdf)$/i.test(file.name))) { modalError.value = '请选择非空的 Markdown、DOCX 或 PDF 文件，大小不超过 20 MB'; importForm.value.file = null; } }
function scheduleCandidates() { if (candidateTimer) clearTimeout(candidateTimer); candidateTimer = setTimeout(() => { void searchCandidates(); }, 200); }
async function searchCandidates() { if (!importForm.value.kbId || !sourceKbId.value || importForm.value.audience !== 'SELECTED_MEMBERS') return; candidateLoading.value = true; try { candidates.value = await api.json<Candidate[]>(`/knowledge-bases/${importForm.value.kbId}/audience-candidates?sourceKbId=${sourceKbId.value}&q=${encodeURIComponent(memberQuery.value)}&limit=20`); } catch { candidates.value = []; } finally { candidateLoading.value = false; } }
function isSelected(id: number) { return selectedMembers.value.some((member) => member.sourceKbId === sourceKbId.value && member.userId === id); }
function toggleCandidate(candidate: Candidate) { if (!sourceKbId.value) return; if (isSelected(candidate.id)) selectedMembers.value = selectedMembers.value.filter((member) => !(member.sourceKbId === sourceKbId.value && member.userId === candidate.id)); else selectedMembers.value.push({ ...candidate, sourceKbId: sourceKbId.value, userId: candidate.id }); }
function removeMember(member: SelectedMember) { selectedMembers.value = selectedMembers.value.filter((item) => item !== member); }
let uploadKey = ''; let uploadAttempt: UploadAttempt | undefined; const uploadStage = ref('');
async function upload() {
  if (!importForm.value.file || !importForm.value.kbId || saving.value) return;
  if (importForm.value.audience === 'SELECTED_MEMBERS' && !selectedMembers.value.length) { modalError.value = '指定成员模式至少选择一名成员'; return; }
  saving.value = true; modalError.value = ''; uploadStage.value = '上传并处理文档…';
  try {
    const kb = Number(importForm.value.kbId);
    if (!uploadAttempt || uploadAttempt.file !== importForm.value.file || uploadAttempt.kbId !== kb) {
      uploadAttempt = { file: importForm.value.file, kbId: kb, purpose: 'WIKI_IMPORT_SOURCE' };
      uploadKey = crypto.randomUUID();
    }
    const attachment = await uploadDirect(uploadAttempt, undefined, stage => { uploadStage.value = stage; });
    uploadStage.value = '提交解析任务…';
    await api.post(`/knowledge-bases/${kb}/imports`, {
      attachmentUuid: attachment.uuid,
      audienceMode: importForm.value.audience,
      audienceMembers: selectedMembers.value.map(member => `${member.sourceKbId}:${member.userId}`).join(','),
    }, undefined, { 'Idempotency-Key': uploadKey });
    showImport.value = false;
    await router.push({ name: 'workspace', params: { kbId: kb } });
  } catch(e) { modalError.value = errorMessage(e, '上传未完成，请检查网络后重试'); } finally { saving.value = false; uploadStage.value = ''; }
}
function open(id: number) { void router.push(`/knowledge-bases/${id}`); }
onMounted(async () => { await load(); const target = Number(route.query.import); if (target && bases.value.some(base => base.id === target && base.canUpload)) importFor(target); });
</script>

<style scoped>
.kb-page {
  min-height: 100%;
  max-width: 1200px;
  margin: 0 auto;
  padding: 40px 40px 80px;
  color: var(--k-ink);
}

header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
}

.eyebrow {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--k-green-deep);
}

.page-heading h1 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: -0.5px;
  color: var(--k-ink);
}

.base-count {
  padding: 1px 8px;
  border-radius: var(--k-r-pill);
  background: var(--k-surface-hover);
  color: var(--k-muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0;
}

.sub {
  margin: 6px 0 0;
  color: var(--k-muted);
}

.hint {
  color: var(--k-muted);
}

/* 通用按钮：次级样式，主操作见 .primary-action 与弹窗底部提交按钮 */
button {
  padding: 9px 13px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-canvas);
  color: var(--k-ink);
  cursor: pointer;
}

/* :where 把特异性降到最低，避免盖过下方各处按钮的专属样式 */
button:where(:hover:not(:disabled)) {
  border-color: var(--k-line-strong);
  background: var(--k-surface);
}

button:disabled {
  opacity: 0.5;
  cursor: default;
}

.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 22px;
}

.header-actions button {
  display: flex;
  gap: 7px;
  align-items: center;
  font-size: 12px;
  padding: 10px 14px;
}

.primary-action {
  background: var(--k-primary) !important;
  border-color: var(--k-primary) !important;
  color: var(--k-on-primary) !important;
}

.primary-action:hover {
  background: var(--k-primary-hover) !important;
}

.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 38px 0 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--k-line);
}

.list-toolbar > span {
  font-size: 13px;
  font-weight: 500;
  color: var(--k-ink-2);
}

.list-toolbar label {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 10px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  color: var(--k-faint);
}

.list-toolbar label:focus-within {
  border-color: var(--k-green);
  box-shadow: var(--k-focus-ring);
}

.list-toolbar input {
  width: 160px;
  padding: 0;
  border: 0;
  outline: none;
  background: transparent;
  font-size: 12px;
}

.base-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.base-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 188px;
  padding: 18px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  cursor: pointer;
  transition: border-color var(--k-ease), box-shadow var(--k-ease), transform var(--k-ease);
}

.base-card:hover,
.base-card:focus-visible {
  border-color: var(--k-line-strong);
  box-shadow: var(--k-shadow);
  transform: translateY(-1px);
}

.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.library-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: var(--k-r);
  background: var(--k-green-soft);
  color: var(--k-green-deep);
  font-size: 18px;
}

.role-badge {
  padding: 2px 8px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-pill);
  color: var(--k-muted);
  font-size: 11px;
}

.card-title {
  margin-top: 6px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.2px;
  color: var(--k-ink);
  text-decoration: none;
}

.card-description {
  flex: 1;
  margin: 0;
  color: var(--k-muted);
  font-size: 13px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 默认弱化而非隐藏，保证触屏用户也能找到操作；悬停或键盘聚焦时完全显现 */
.card-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-top: 10px;
  border-top: 1px solid var(--k-line);
  opacity: 0.72;
  transition: opacity var(--k-ease);
}

.base-card:hover .card-actions,
.base-card:focus-within .card-actions {
  opacity: 1;
}

.card-actions > span {
  flex: 1;
}

.card-actions button {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 4px 0;
  border: 0;
  background: none;
  color: var(--k-ink-2);
  font-size: 12px;
}

.card-actions button[aria-label] {
  padding: 6px;
  border-radius: var(--k-r-sm);
  color: var(--k-muted);
}

.card-actions button:hover:not(:disabled) {
  background: none;
  color: var(--k-ink);
}

.card-actions button[aria-label]:hover:not(:disabled) {
  background: var(--k-surface-hover);
}

.settings-link {
  display: flex;
  gap: 5px;
  align-items: center;
  padding: 5px;
  border-radius: var(--k-r-sm);
  text-decoration: none;
  font-size: 12px;
  color: var(--k-muted);
}

.settings-link:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}

.state {
  padding: 70px 30px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  color: var(--k-muted);
  text-align: center;
}

.state strong {
  display: block;
  color: var(--k-ink);
  font-size: 19px;
  font-weight: 500;
}

.state p {
  margin-bottom: 20px;
  font-size: 13px;
}

.empty-library {
  display: inline-flex;
  margin-bottom: 24px;
  padding: 18px;
  border-radius: var(--k-r-lg);
  background: var(--k-surface);
  color: var(--k-muted);
  font-size: 32px;
}

.error {
  color: var(--k-danger);
}

.modal {
  position: fixed;
  inset: 0;
  z-index: 95;
  display: grid;
  place-items: center;
  padding: 20px;
  background: var(--k-overlay);
}

.modal-card {
  width: min(520px, 100%);
  display: grid;
  gap: 20px;
  padding: 28px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  box-shadow: var(--k-shadow-float);
}

.modal-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.modal-card header strong {
  font-size: 20px;
  font-weight: 600;
}

.modal-card header button {
  padding: 0;
  border: 0;
  background: none;
  color: var(--k-muted);
  font-size: 22px;
}

.modal-card header button:hover:not(:disabled) {
  background: none;
  color: var(--k-ink);
}

label {
  display: grid;
  gap: 6px;
  color: var(--k-ink-2);
  font-size: 13px;
}

.modal-card label {
  gap: 9px;
  font-size: 12px;
}

input,
textarea,
select {
  box-sizing: border-box;
  width: 100%;
  padding: 9px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-canvas);
  color: var(--k-ink);
  font: inherit;
}

textarea {
  min-height: 80px;
  resize: vertical;
}

.modal-card input,
.modal-card select,
.modal-card textarea {
  min-height: 40px;
  padding: 11px 12px;
}

.modal-card input:hover,
.modal-card select:hover,
.modal-card textarea:hover {
  border-color: var(--k-line-strong);
}

/* 下拉箭头用两段渐变绘制，颜色随主题变化 */
.modal-card select {
  appearance: none;
  padding-right: 34px;
  background-image:
    linear-gradient(45deg, transparent 50%, var(--k-muted) 50%),
    linear-gradient(135deg, var(--k-muted) 50%, transparent 50%);
  background-position:
    calc(100% - 18px) 50%,
    calc(100% - 13px) 50%;
  background-size: 5px 5px;
  background-repeat: no-repeat;
}

.modal-card input[type='file'] {
  padding: 20px;
  border-style: dashed;
  background: var(--k-surface);
}

.modal-card input::file-selector-button {
  margin-right: 12px;
  padding: 8px 12px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-sm);
  background: var(--k-canvas);
  color: var(--k-ink-2);
  cursor: pointer;
}

.modal-card input:focus,
.modal-card textarea:focus,
.modal-card select:focus {
  outline: none;
  border-color: var(--k-green);
  box-shadow: var(--k-focus-ring);
}

.modal-card .hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
}

.modal-card .error {
  margin: 0;
  padding: 12px;
  border: 1px solid var(--k-danger-line);
  border-radius: var(--k-r);
  background: var(--k-danger-soft);
  font-size: 13px;
  line-height: 1.8;
}

footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.modal-card footer {
  padding-top: 20px;
  border-top: 1px solid var(--k-line);
}

/* 弹窗底部最后一个按钮为提交主操作 */
footer button:last-child {
  border-color: var(--k-primary);
  background: var(--k-primary);
  color: var(--k-on-primary);
}

footer button:last-child:hover:not(:disabled) {
  border-color: var(--k-primary-hover);
  background: var(--k-primary-hover);
}

.busy-spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  margin-right: 6px;
  border: 2px solid color-mix(in srgb, currentColor 30%, transparent);
  border-top-color: currentColor;
  border-radius: 50%;
  vertical-align: -2px;
  animation: kb-spin 0.8s linear infinite;
}

@keyframes kb-spin {
  to {
    transform: rotate(360deg);
  }
}

.import-card {
  width: min(720px, 100%);
  max-height: 90vh;
  overflow: auto;
}

.audience-picker {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  min-height: 190px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  overflow: hidden;
}

.audience-picker aside {
  display: grid;
  align-content: start;
  gap: 4px;
  padding: 10px;
  border-right: 1px solid var(--k-line);
  background: var(--k-surface);
}

.audience-picker aside button {
  padding: 8px;
  border: 0;
  border-radius: var(--k-r-sm);
  text-align: left;
  background: transparent;
}

.audience-picker aside button:hover:not(:disabled) {
  background: var(--k-surface-hover);
}

.audience-picker aside button.selected {
  background: var(--k-green-soft);
  color: var(--k-green-deep);
}

.audience-members {
  display: grid;
  align-content: start;
  gap: 7px;
  padding: 10px;
}

.candidate {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 7px 8px;
  border: 0;
  border-radius: var(--k-r-sm);
  background: var(--k-canvas);
  text-align: left;
}

.candidate span {
  margin-left: auto;
  color: var(--k-muted);
  font-size: 12px;
}

.candidate.checked {
  background: var(--k-green-soft);
  color: var(--k-green-deep);
}

.selected-list {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--k-ink-2);
}

.chip {
  display: inline-flex;
  gap: 3px;
  align-items: center;
  margin: 3px 3px 0 0;
  padding: 3px 7px;
  border-radius: var(--k-r-pill);
  background: var(--k-green-soft);
  color: var(--k-green-deep);
}

.chip button {
  padding: 0;
  border: 0;
  background: none;
  color: var(--k-muted);
}

@media (max-width: 1100px) {
  .kb-page {
    padding: 36px 28px 80px;
  }

  .page-heading {
    flex-wrap: wrap;
  }

  .header-actions {
    margin-top: 0;
  }
}

@media (max-width: 640px) {
  header {
    display: block;
  }

  .header-actions {
    margin-top: 16px;
  }
}

@media (max-width: 600px) {
  .kb-page {
    padding: 24px 18px 80px;
  }

  .header-actions {
    margin-top: 20px;
  }

  .list-toolbar label input {
    width: 110px;
  }

  .base-grid {
    grid-template-columns: 1fr;
  }

  .modal-card {
    padding: 22px;
  }

  .audience-picker {
    grid-template-columns: 110px minmax(0, 1fr);
  }
}
</style>
