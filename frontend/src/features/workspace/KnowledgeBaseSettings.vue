<template>
  <main class="settings-page">
    <nav class="settings-crumb"><RouterLink to="/knowledge-bases">知识库</RouterLink><span>›</span><RouterLink :to="`/knowledge-bases/${kbId}`">{{ base?.name || '加载中…' }}</RouterLink><span>›</span><span>设置</span></nav>
    <header class="settings-heading"><span class="settings-icon"><i class="i-lucide-settings-2" /></span><div><h1>知识库设置</h1><p>管理知识库信息、成员与协作方式。</p></div><RouterLink class="ui-button" :to="`/knowledge-bases/${kbId}`"><i class="i-lucide-arrow-left" />返回知识库</RouterLink></header>
    <p v-if="loading" class="loading">正在加载设置…</p><p v-else-if="error" class="ui-error" role="alert">{{ error }}<button class="ui-button" @click="load">重试</button></p>
    <template v-else-if="base?.canManage"><div class="settings-layout"><nav class="settings-tabs" aria-label="设置分类"><button v-for="item in tabs" :key="item.id" :class="{ active: tab === item.id }" @click="tab = item.id; notice = ''; actionError = ''"><i :class="item.icon" />{{ item.label }}</button></nav><section class="settings-panel"><p v-if="notice" class="ui-notice" role="status">{{ notice }}</p><p v-if="actionError" class="ui-error" role="alert">{{ actionError }}</p>
      <form v-if="tab === 'basic'" @submit.prevent="save"><h2>基本信息</h2><p class="panel-description">一个清晰的名称和简介，能帮助成员快速了解这里的内容。</p><label>知识库名称<input v-model.trim="form.name" class="ui-input" required maxlength="200" :disabled="!base.canEditSettings" /></label><label>简介<textarea v-model.trim="form.description" class="ui-input" rows="4" maxlength="2000" :disabled="!base.canEditSettings" placeholder="介绍这个知识库的用途、内容或适用团队" /></label><p v-if="!base.canEditSettings" class="panel-description">基本信息由知识库创建者维护。</p><footer><button class="ui-button primary" :disabled="saving || !form.name || !base.canEditSettings">{{ saving ? '保存中…' : '保存更改' }}</button></footer></form>
      <section v-if="tab === 'members'"><h2>成员与权限<span class="member-count">{{ members.length }}</span></h2><p class="panel-description">为每位协作者分配合适的角色。</p><div class="add-member"><label>添加成员<div class="candidate-search"><i class="i-lucide-search" /><input v-model="search" class="ui-input" placeholder="输入至少 2 个字搜索用户名或姓名" aria-label="搜索添加成员" /></div></label><p v-if="searching" class="panel-description">搜索中…</p><div v-if="candidates.length" class="candidate-list"><button v-for="candidate in candidates" :key="candidate.userId" @click="chosen = candidate; search = ''; candidates = []"><span class="member-avatar">{{ (candidate.displayName || candidate.username).slice(0,1) }}</span><span>{{ candidate.displayName || candidate.username }}<small>@{{ candidate.username }}</small></span><i class="i-lucide-plus" /></button></div><p v-else-if="search.length >= 2 && !searching" class="panel-description">没有匹配的用户</p><form v-if="chosen" class="chosen-member" @submit.prevent="add"><span>{{ chosen.displayName || chosen.username }}</span><select v-model="newRole" class="ui-input" aria-label="新成员角色"><option value="VIEWER">只读</option><option value="EDITOR">编辑者</option><option v-if="base.canEditSettings" value="ADMIN">管理员</option></select><button class="ui-button primary" :disabled="saving">添加</button><button type="button" class="ui-icon" aria-label="取消选择成员" @click="chosen = null"><i class="i-lucide-x" /></button></form></div><div class="member-table"><div class="table-header"><span>成员</span><span>角色与操作</span></div><div v-for="member in members" :key="member.userId" class="member-row"><span class="member-avatar">{{ (member.displayName || member.username || '?').slice(0,1) }}</span><div class="member-name"><strong>{{ member.displayName || member.username }}<small v-if="member.userId === auth.user?.id">你</small></strong><span>@{{ member.username }}</span></div><template v-if="editable(member)"><select :value="member.role" class="role-select" :aria-label="`${member.username} 的角色`" :disabled="saving" @change="changeRole(member, $event)"><option value="VIEWER">只读</option><option value="EDITOR">编辑者</option><option v-if="base.canEditSettings" value="ADMIN">管理员</option></select><button class="ui-icon" :aria-label="`移除 ${member.username}`" :disabled="saving" @click="removeTarget = member"><i class="i-lucide-user-minus" /></button></template><span v-else class="role-label">{{ roleLabels[member.role] }}</span></div></div><div class="role-help"><p><strong>只读</strong>阅读文档与使用知识库问答</p><p><strong>编辑者</strong>创建、编辑、导入和发布文档</p><p><strong>管理员</strong>管理内容、普通成员、邀请和加入申请</p><p><strong>创建者</strong>管理基本信息、管理员、归档与所有权</p></div></section>
      <section v-if="tab === 'collaboration'"><h2>邀请与审核</h2><p class="panel-description">通过邀请链接与同事共享知识，并管理加入申请。</p><CollaborationPanel :key="kbId" resource-type="KB" :resource-id="Number(kbId)" embedded /></section>
      <section v-if="tab === 'advanced'"><h2>高级设置</h2><p class="panel-description">涉及知识库所有权与可用状态的操作。</p><div v-if="base.canTransfer" class="advanced-box"><h3>转交知识库</h3><p>选择现有成员作为新创建者，对方确认后生效。</p><form class="transfer-form" @submit.prevent="transfer"><select v-model="recipient" class="ui-input" aria-label="新创建者" required><option value="" disabled>选择知识库成员</option><option v-for="member in members.filter(m => m.role !== 'OWNER')" :key="member.userId" :value="member.userId">{{ member.displayName || member.username }}</option></select><button class="ui-button" :disabled="saving || !recipient">生成确认链接</button></form><label v-if="transferLink">转交确认链接<input class="ui-input" readonly :value="transferLink" /><button class="ui-button" @click="copyTransfer">{{ copied ? '已复制' : '复制链接' }}</button></label></div><div v-if="base.canEditSettings" class="advanced-box danger-box"><h3>归档知识库</h3><p>归档后，知识库不再显示在列表中，也无法继续访问其中的内容。</p><button class="ui-button danger" @click="archiveOpen = true">归档知识库</button></div><p v-if="!base.canEditSettings" class="panel-description">只有创建者可以执行这些操作。</p></section>
    </section></div></template><p v-else class="ui-error">你没有管理此知识库的权限。</p>
    <div v-if="removeTarget" class="confirm-overlay"><section class="confirm-dialog" role="dialog" aria-modal="true" aria-label="确认移除成员"><h2>移除成员？</h2><p>移除后，{{ removeTarget?.displayName || removeTarget?.username }} 将失去通过该成员身份获得的访问权限。</p><p v-if="actionError" class="ui-error">{{ actionError }}</p><footer><button class="ui-button" :disabled="saving" @click="removeTarget = null">取消</button><button class="ui-button danger" :disabled="saving" @click="confirmAction">确认移除</button></footer></section></div>
    <ArchiveConfirmDialog
      :open="archiveOpen"
      scope-label="知识库"
      :title="base?.name ?? ''"
      :pending="saving"
      @cancel="archiveOpen = false"
      @confirm="confirmArchive"
    />
  </main>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'; import { RouterLink, useRouter } from 'vue-router'; import { api, errorMessage } from '../wiki/api'; import { useWikiStore } from '../wiki/store'; import { useAuthStore } from '../auth/store'; import CollaborationPanel from './CollaborationPanel.vue'; import ArchiveConfirmDialog from '../wiki/components/ArchiveConfirmDialog.vue';
const props = defineProps<{ kbId: string }>(); const router = useRouter(); const wiki = useWikiStore(); const auth = useAuthStore();
type Role = 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER'; type Member = { userId: number; role: Role; username: string; displayName?: string }; type Candidate = Omit<Member, 'role'>;
const base = ref<{ name: string; description?: string; canManage: boolean; canEditSettings: boolean; canTransfer: boolean }>();
const loading = ref(false); const error = ref(''); const saving = ref(false); const notice = ref(''); const actionError = ref(''); const form = ref({ name: '', description: '' }); const tab = ref('basic'); const members = ref<Member[]>([]);
const tabs = [{ id:'basic', label:'基本信息', icon:'i-lucide-sliders-horizontal' },{ id:'members', label:'成员与权限', icon:'i-lucide-users' },{ id:'collaboration', label:'邀请与审核', icon:'i-lucide-link' },{ id:'advanced', label:'高级设置', icon:'i-lucide-shield' }];
const roleLabels = { OWNER: '创建者', ADMIN: '管理员', EDITOR: '编辑者', VIEWER: '只读' };
let loadVersion = 0;
async function load() { const version = ++loadVersion; loading.value = true; error.value = ''; try { const data = await api.json<NonNullable<typeof base.value>>(`/knowledge-bases/${props.kbId}`); if (version !== loadVersion) return; base.value = data; form.value = { name:data.name, description:data.description || '' }; if (data.canManage) await loadMembers(); } catch(e) { if (version === loadVersion) error.value = errorMessage(e); } finally { if (version === loadVersion) loading.value = false; } }
async function loadMembers() { members.value = await api.json<Member[]>(`/knowledge-bases/${props.kbId}/members`); }
watch(() => props.kbId, () => { tab.value = 'basic'; search.value = ''; chosen.value = null; void load(); });
async function perform(action: () => Promise<void>, message: string) { saving.value = true; actionError.value = ''; notice.value = ''; try { await action(); notice.value = message; } catch(e) { actionError.value = errorMessage(e); } finally { saving.value = false; } }
async function save() { await perform(async () => { base.value = await api.put(`/knowledge-bases/${props.kbId}`, form.value); await wiki.loadKnowledgeBases(); }, '知识库信息已保存'); }
const search = ref(''); const candidates = ref<Candidate[]>([]); const chosen = ref<Candidate | null>(null); const searching = ref(false); const newRole = ref<Role>('VIEWER'); let timer: ReturnType<typeof setTimeout>; let searchVersion = 0;
watch(search, value => { clearTimeout(timer); const version = ++searchVersion; candidates.value = []; searching.value = value.trim().length >= 2; if (!searching.value) return; timer = setTimeout(async () => { try { const items = await api.json<Candidate[]>(`/knowledge-bases/${props.kbId}/member-candidates?q=${encodeURIComponent(value)}`); if (version === searchVersion) candidates.value = items.filter(c => !members.value.some(m => m.userId === c.userId)); } catch(e) { if (version === searchVersion) actionError.value = errorMessage(e); } finally { if (version === searchVersion) searching.value = false; } }, 250); });
onBeforeUnmount(() => { clearTimeout(timer); ++searchVersion; ++loadVersion; });
function editable(member: Member) { return member.role !== 'OWNER' && (base.value?.canEditSettings || member.role !== 'ADMIN'); }
async function add() { if (!chosen.value) return; await perform(async () => { await api.put(`/knowledge-bases/${props.kbId}/members`, { userId:chosen.value!.userId, role:newRole.value }); chosen.value = null; await loadMembers(); }, '成员已添加'); }
async function changeRole(member: Member, event: Event) { const select = event.target as HTMLSelectElement; const role = select.value; await perform(async () => { await api.put(`/knowledge-bases/${props.kbId}/members`, { userId:member.userId, role }); await loadMembers(); }, '成员角色已更新'); select.value = members.value.find(m => m.userId === member.userId)?.role || member.role; }
const removeTarget = ref<Member | null>(null); const archiveOpen = ref(false);
async function confirmAction() { await perform(async () => { if (removeTarget.value) { await api.delete(`/knowledge-bases/${props.kbId}/members/${removeTarget.value.userId}`); removeTarget.value = null; await loadMembers(); } }, '操作已完成'); }
/** 两步归档：仅从最终弹窗触发，通过 saving 标志实现防抖。 */
async function confirmArchive() { await perform(async () => { await api.post(`/knowledge-bases/${props.kbId}/archive`); archiveOpen.value = false; await wiki.loadKnowledgeBases(); await router.push('/knowledge-bases'); }, '知识库已移入回收站'); }
const recipient = ref<number | ''>(''); const transferLink = ref(''); const copied = ref(false);
async function transfer() { await perform(async () => { const result = await api.post<{ token: string }>('/ownership-transfers', { resourceType:'KB', resourceId:Number(props.kbId), recipientId:recipient.value }); transferLink.value = `${location.origin}${location.pathname}#/transfer/${encodeURIComponent(result.token)}`; }, '转交链接已生成，交由受让人确认后生效'); }
async function copyTransfer() { try { await navigator.clipboard.writeText(transferLink.value); copied.value = true; } catch { actionError.value = '无法自动复制，请选中链接复制'; } }
void load();
</script>
<style scoped>
.settings-page {
  max-width: 760px;
  margin: 0 auto;
  padding: 40px;
  color: var(--k-ink);
}

.settings-crumb {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 12px;
  color: var(--k-muted);
}

.settings-crumb a {
  text-decoration: none;
}

.settings-crumb a:hover {
  color: var(--k-ink);
}

.settings-crumb > span:last-child {
  color: var(--k-ink-2);
}

.settings-heading {
  display: flex;
  align-items: center;
  gap: 16px;
  margin: 32px 0 40px;
}

.settings-icon {
  display: flex;
  padding: 14px;
  border-radius: var(--k-r-lg);
  background: var(--k-green-soft);
  color: var(--k-green-deep);
  font-size: 23px;
}

.settings-heading h1 {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.4px;
  color: var(--k-ink);
}

.settings-heading p {
  margin: 0;
  font-size: 12px;
  color: var(--k-muted);
}

.settings-heading > .ui-button {
  margin-left: auto;
  font-size: 12px;
}

.settings-layout {
  display: grid;
  grid-template-columns: 170px minmax(0, 1fr);
  gap: 34px;
}

.settings-tabs {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.settings-tabs button {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 13px;
  border: 0;
  border-radius: var(--k-r);
  background: none;
  color: var(--k-muted);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.settings-tabs button:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}

.settings-tabs button.active {
  background: var(--k-green-soft);
  color: var(--k-green-deep);
  font-weight: 500;
}

.settings-panel {
  min-width: 0;
  padding: 30px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
}

.settings-panel h2 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 10px;
  font-size: 18px;
  font-weight: 600;
}

.panel-description {
  margin: 0 0 27px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--k-muted);
}

.settings-panel label {
  display: grid;
  gap: 10px;
  margin: 22px 0;
  font-size: 12px;
  color: var(--k-ink-2);
}

.settings-panel textarea {
  min-height: 120px;
  resize: vertical;
  line-height: 1.8;
}

.settings-panel footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 30px;
  padding-top: 22px;
  border-top: 1px solid var(--k-line);
}

.member-count {
  padding: 3px 7px;
  border-radius: var(--k-r-sm);
  background: var(--k-surface-hover);
  color: var(--k-muted);
  font-size: 11px;
}

.add-member {
  margin-bottom: 24px;
  padding: 0 18px 10px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-surface);
}

.candidate-search {
  position: relative;
}

.candidate-search > i {
  position: absolute;
  left: 12px;
  top: 12px;
  color: var(--k-faint);
}

.candidate-search input {
  padding-left: 35px;
}

.candidate-list {
  display: grid;
  max-height: 220px;
  margin-bottom: 12px;
  overflow: auto;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-canvas);
}

.candidate-list button {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border: 0;
  background: var(--k-canvas);
  text-align: left;
  cursor: pointer;
}

.candidate-list button:hover {
  background: var(--k-surface-hover);
}

.candidate-list small {
  display: block;
  margin-top: 4px;
  font-size: 10px;
  color: var(--k-muted);
}

.candidate-list button > i {
  margin-left: auto;
  color: var(--k-muted);
}

.chosen-member {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.chosen-member > span {
  margin-right: auto;
  font-size: 12px;
}

.chosen-member select {
  width: 110px;
  font-size: 12px;
}

.table-header {
  display: flex;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid var(--k-line);
  color: var(--k-faint);
  font-size: 10px;
}

.member-row {
  display: flex;
  gap: 11px;
  align-items: center;
  padding: 16px 0;
  border-bottom: 1px solid var(--k-line);
}

.member-avatar {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 33px;
  height: 33px;
  border-radius: 50%;
  background: var(--k-green-soft);
  color: var(--k-green-deep);
  font-size: 13px;
}

.member-name {
  min-width: 0;
  flex: 1;
}

.member-name strong {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  font-weight: 500;
}

.member-name strong small {
  padding: 2px 5px;
  border-radius: var(--k-r-sm);
  background: var(--k-surface-hover);
  color: var(--k-muted);
  font-size: 9px;
}

.member-name > span {
  display: block;
  margin-top: 4px;
  font-size: 10px;
  color: var(--k-faint);
}

.role-select {
  appearance: none;
  width: 95px;
  padding: 7px 9px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-sm);
  background: var(--k-canvas);
  color: var(--k-ink-2);
  font-size: 11px;
}

.role-select:hover {
  border-color: var(--k-line-strong);
}

.role-select:focus {
  outline: none;
  border-color: var(--k-green);
  box-shadow: var(--k-focus-ring);
}

.role-label {
  margin-right: 12px;
  font-size: 11px;
  color: var(--k-muted);
}

.role-help {
  margin-top: 24px;
  padding: 10px 16px;
  border-radius: var(--k-r);
  background: var(--k-surface);
}

.role-help p {
  font-size: 11px;
  line-height: 1.8;
  color: var(--k-muted);
}

.role-help strong {
  display: inline-block;
  min-width: 64px;
  color: var(--k-ink-2);
  font-weight: 500;
}

.advanced-box {
  margin-top: 24px;
  padding: 22px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
}

.advanced-box h3 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
}

.advanced-box p {
  font-size: 12px;
  line-height: 1.8;
  color: var(--k-muted);
}

.transfer-form {
  display: flex;
  gap: 10px;
  margin-top: 20px;
}

.transfer-form select {
  flex: 1;
  min-width: 0;
}

.transfer-form button {
  flex-shrink: 0;
}

.danger-box {
  border-color: var(--k-danger-line);
}

.danger-box h3 {
  color: var(--k-danger);
}

.confirm-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 20px;
  background: var(--k-overlay);
}

.confirm-dialog {
  width: min(460px, 100%);
  padding: 28px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  box-shadow: var(--k-shadow-float);
}

.confirm-dialog h2 {
  font-size: 20px;
}

.confirm-dialog p {
  font-size: 13px;
  line-height: 1.8;
  color: var(--k-muted);
}

.confirm-dialog footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 28px;
}

.loading {
  font-size: 13px;
  color: var(--k-muted);
}

@media (max-width: 1000px) {
  .settings-page {
    padding: 24px 26px 80px;
  }

  .settings-layout {
    grid-template-columns: 145px minmax(0, 1fr);
    gap: 20px;
  }

  .settings-panel {
    padding: 22px;
  }

  .settings-heading > .ui-button {
    font-size: 11px;
  }
}

@media (max-width: 650px) {
  .settings-page {
    padding: 20px 16px 80px;
  }

  .settings-heading {
    flex-wrap: wrap;
    margin: 26px 0;
  }

  .settings-heading > .ui-button {
    margin-left: 0;
  }

  .settings-layout {
    grid-template-columns: 1fr;
    gap: 16px;
  }

  .settings-tabs {
    flex-direction: row;
    overflow: auto;
  }

  .settings-tabs button {
    padding: 10px;
    white-space: nowrap;
    font-size: 11px;
  }

  .settings-panel {
    padding: 18px;
  }

  .chosen-member {
    flex-wrap: wrap;
  }

  .transfer-form {
    flex-direction: column;
  }
}
</style>
