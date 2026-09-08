<template>
  <div class="collaboration">
    <button v-if="!embedded" type="button" class="open-button" @click="open">协作</button>
    <div v-if="visible || embedded" class="backdrop" :class="{ embedded }" role="presentation" @click.self="close">
      <section class="modal" role="dialog" aria-modal="true" :aria-label="`${resourceLabel}协作管理`">
        <header v-if="!embedded" class="modal-header">
          <div><p class="eyebrow">kwiki / collaboration</p><h2>{{ resourceLabel }}协作</h2></div>
          <button type="button" class="close" aria-label="关闭协作管理" @click="close">×</button>
        </header>
        <p class="hint">邀请链接只授予只读或编辑权限，链接默认 7 天有效；是否需要审核由资源设置决定。</p>
        <label v-if="resourceType === 'KB'" class="approval-toggle"><input v-model="approvalRequired" type="checkbox" @change="updateApproval" /> 新成员加入需要管理员审核</label>
        <form class="invite-form" @submit.prevent="createInvitation">
          <label>邀请权限<select v-model="form.role"><option value="VIEWER">只读</option><option value="EDITOR">编辑</option></select></label>
          <label>有效期<select v-model="form.ttlDays"><option value="1">1 天</option><option value="7">7 天</option><option value="14">14 天</option><option value="30">30 天</option></select></label>
          <button type="submit" :disabled="saving">{{ saving ? '生成中…' : '生成邀请链接' }}</button>
        </form>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <div v-if="createdLink" class="created-link">
          <strong>邀请链接已生成</strong>
          <div class="link-row"><input :value="createdLink" readonly aria-label="邀请链接" @focus="selectLink" /><button type="button" @click="copyLink">{{ copied ? '已复制' : '复制' }}</button></div>
        </div>
        <div class="section">
          <div class="section-title"><strong>有效邀请</strong><button type="button" @click="reload">刷新</button></div>
          <p v-if="loading" class="muted">正在加载…</p>
          <p v-else-if="!invitations.length" class="muted">暂无邀请链接</p>
          <ul v-else class="rows"><li v-for="item in invitations" :key="item.id"><span><strong>{{ item.role === 'EDITOR' ? '编辑' : '只读' }}</strong><small>有效期至 {{ format(item.expiresAt) }}<em v-if="item.revokedAt"> · 已撤销</em></small></span><button v-if="!item.revokedAt" type="button" class="danger" @click="revoke(item.id)">撤销</button></li></ul>
        </div>
        <div class="section">
          <div class="section-title"><strong>待审核申请</strong><button type="button" @click="reload">刷新</button></div>
          <p v-if="!requests.length" class="muted">暂无待审核申请</p>
          <ul v-else class="rows"><li v-for="request in requests" :key="request.id"><span><strong>{{ request.username }}</strong><small>{{ request.role === 'EDITOR' ? '编辑权限' : '只读权限' }} · {{ format(request.createdAt) }}</small></span><span class="row-actions"><button type="button" @click="review(request.id, true)">批准</button><button type="button" class="danger" @click="review(request.id, false)">拒绝</button></span></li></ul>
        </div>
        <div v-if="!embedded" class="section">
          <div class="section-title"><strong>{{ resourceType === 'KB' ? '知识库成员与管理员' : '文档成员与管理员' }}</strong><button type="button" @click="reload">刷新</button></div>
          <form class="member-form" @submit.prevent="saveMember"><input v-model.number="memberForm.userId" type="number" min="1" required placeholder="用户 ID" aria-label="用户 ID" /><select v-model="memberForm.role" aria-label="成员角色"><option value="VIEWER">只读</option><option value="EDITOR">编辑</option><option value="ADMIN">管理员</option></select><button type="submit">保存角色</button></form>
          <ul class="rows"><li v-for="member in members" :key="member.userId"><span><strong>{{ member.username || `用户 #${member.userId}` }}</strong><small>{{ roleLabel(member.role) }}<em v-if="member.inherited"> · 继承自知识库</em></small></span><button v-if="member.role !== 'OWNER' && !member.inherited" type="button" class="danger" @click="removeMember(member.userId)">移除</button></li></ul>
        </div>
        <div v-if="!embedded" class="section">
          <div class="section-title"><strong>转交创作者权限</strong></div>
          <p class="hint">只能转交给当前资源协作者；原创作者确认后保留编辑权限。</p>
          <form class="member-form" @submit.prevent="createTransfer"><input v-model.number="transferRecipient" type="number" min="1" required placeholder="受让人用户 ID" aria-label="受让人用户 ID" /><button type="submit">生成转交链接</button></form>
          <div v-if="transferLink" class="created-link"><strong>转交确认链接已生成</strong><div class="link-row"><input :value="transferLink" readonly aria-label="转交链接" @focus="selectTransferLink" /><button type="button" @click="copyTransferLink">{{ transferCopied ? '已复制' : '复制' }}</button></div></div>
        </div>
        <footer v-if="!embedded" class="modal-footer"><button type="button" @click="close">完成</button></footer>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue';
import { api } from '../wiki/api';

type ResourceType = 'KB' | 'PAGE';
interface Invitation { id: number; role: 'VIEWER' | 'EDITOR'; expiresAt: string; revokedAt?: string | null; createdAt: string; }
interface JoinRequest { id: number; userId: number; username: string; role: 'VIEWER' | 'EDITOR'; status: string; createdAt: string; }
interface Member { userId: number; username?: string; role: 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER'; inherited?: boolean; }
const props = defineProps<{ resourceType: ResourceType; resourceId: number; knowledgeBaseId?: number; embedded?: boolean; }>();
const visible = ref(false); const loading = ref(false); const saving = ref(false); const error = ref(''); const copied = ref(false); const createdLink = ref('');
const invitations = ref<Invitation[]>([]); const requests = ref<JoinRequest[]>([]); const members = ref<Member[]>([]);
const form = ref({ role: 'VIEWER' as 'VIEWER' | 'EDITOR', ttlDays: '7' });
const memberForm = ref({ userId: 0, role: 'VIEWER' as 'VIEWER' | 'EDITOR' | 'ADMIN' });
const transferRecipient = ref(0); const transferLink = ref(''); const transferCopied = ref(false);
const approvalRequired = ref(true);
const resourceLabel = computed(() => props.resourceType === 'KB' ? '知识库' : '文档');
function endpoint() { return `/invitations?resourceType=${props.resourceType}&resourceId=${props.resourceId}`; }
onMounted(() => { if (props.embedded) void reload(); });
async function open() { visible.value = true; createdLink.value = ''; copied.value = false; await reload(); }
function close() { visible.value = false; }
async function reload() {
  loading.value = true; error.value = '';
  try {
    const [invites, pending] = await Promise.all([
      api.json<Invitation[]>(endpoint()),
      api.json<JoinRequest[]>(`/invitations/requests?resourceType=${props.resourceType}&resourceId=${props.resourceId}`),
    ]);
    invitations.value = invites; requests.value = pending.filter((item) => item.status === 'PENDING');
    members.value = props.resourceType === 'KB'
      ? await api.json<Member[]>(`/knowledge-bases/${props.resourceId}/members`)
      : await api.json<Member[]>(`/knowledge-bases/${props.knowledgeBaseId}/pages/${props.resourceId}/members`);
    if (props.resourceType === 'KB') {
      const settings = await api.json<{ joinApprovalRequired: boolean }>(`/knowledge-bases/${props.resourceId}/collaboration-settings`);
      approvalRequired.value = settings.joinApprovalRequired;
    }
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '协作数据加载失败'; }
  finally { loading.value = false; }
}
async function createInvitation() {
  saving.value = true; error.value = ''; copied.value = false;
  try {
    const result = await api.post<{ token: string }>('/invitations', { resourceType: props.resourceType, resourceId: props.resourceId, role: form.value.role, ttlSeconds: Number(form.value.ttlDays) * 86400 });
    createdLink.value = `${window.location.origin}${window.location.pathname}#/invite/${encodeURIComponent(result.token)}`;
    await reload();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '邀请链接生成失败'; }
  finally { saving.value = false; }
}
async function updateApproval() {
  try { await api.put(`/knowledge-bases/${props.resourceId}/collaboration-settings`, { joinApprovalRequired: approvalRequired.value }); }
  catch { approvalRequired.value = !approvalRequired.value; error.value = '审核开关更新失败，请重试'; }
}
async function revoke(id: number) { if (!window.confirm('撤销后该链接立即失效，继续吗？')) return; try { await api.delete(`/invitations/${id}`); await reload(); } catch { error.value = '邀请撤销失败，请重试'; } }
async function review(id: number, approve: boolean) { try { await api.put(`/invitations/requests/${id}`, { approve }); await reload(); } catch { error.value = '审核操作失败，请刷新后重试'; } }
async function saveMember() { if (!memberForm.value.userId) return; try { const base = props.resourceType === 'KB' ? `/knowledge-bases/${props.resourceId}/members` : `/knowledge-bases/${props.knowledgeBaseId}/pages/${props.resourceId}/members`; await api.put(base, { userId: memberForm.value.userId, role: memberForm.value.role }); await reload(); } catch { error.value = '成员角色更新失败，请检查管理权限'; } }
async function removeMember(userId: number) { if (!window.confirm(`移除用户 #${userId}？`)) return; try { const base = props.resourceType === 'KB' ? `/knowledge-bases/${props.resourceId}/members/${userId}` : `/knowledge-bases/${props.knowledgeBaseId}/pages/${props.resourceId}/members/${userId}`; await api.delete(base); await reload(); } catch { error.value = '成员移除失败，请重试'; } }
async function createTransfer() { if (!transferRecipient.value) return; try { const result = await api.post<{ token: string }>('/ownership-transfers', { resourceType: props.resourceType, resourceId: props.resourceId, recipientId: transferRecipient.value, ttlSeconds: 7 * 86400 }); transferLink.value = `${window.location.origin}${window.location.pathname}#/transfer/${encodeURIComponent(result.token)}`; transferCopied.value = false; } catch { error.value = '转交链接生成失败，请确认受让人是当前协作者'; } }
async function copyTransferLink() { if (!transferLink.value) return; try { await navigator.clipboard.writeText(transferLink.value); transferCopied.value = true; } catch { selectTransferLink(); document.execCommand('copy'); transferCopied.value = true; } }
function selectTransferLink(event?: Event) { const input = (event?.target ?? document.querySelector<HTMLInputElement>('.created-link input')) as HTMLInputElement | null; input?.select(); }
function roleLabel(role: Member['role']) { return role === 'OWNER' ? '创作者' : role === 'ADMIN' ? '管理员' : role === 'EDITOR' ? '编辑' : '只读'; }
async function copyLink() { if (!createdLink.value) return; try { await navigator.clipboard.writeText(createdLink.value); copied.value = true; } catch { selectLink(); document.execCommand('copy'); copied.value = true; } }
function selectLink(event?: Event) { const input = (event?.target ?? document.querySelector<HTMLInputElement>('.link-row input')) as HTMLInputElement | null; input?.select(); }
function format(value: string) { try { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)); } catch { return value; } }
</script>

<style scoped>
.open-button { padding: 8px 13px; border: 1px solid #cedbd3; border-radius: 7px; background: #fff; color: #26352e; cursor: pointer; }
.backdrop { position: fixed; inset: 0; z-index: 70; display: grid; place-items: center; padding: 20px; background: #1c282255; }
.modal { width: min(620px, 100%); max-height: min(760px, 92vh); overflow: auto; box-sizing: border-box; padding: 23px; border: 1px solid #e0eae3; border-radius: 14px; background: #fff; color: #26352e; box-shadow: 0 24px 70px #17261d33; }
.modal-header, .section-title, .link-row, .row-actions, .modal-footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.eyebrow { margin: 0 0 5px; color: #1b9d61; font-size: 11px; font-weight: 700; text-transform: uppercase; }
h2 { margin: 0; font-size: 22px; } .close { border: 0; background: none; font-size: 25px; cursor: pointer; color: #758078; }
.hint, .muted { color: #77847c; font-size: 13px; line-height: 1.6; } .invite-form { display: grid; grid-template-columns: 1fr 1fr auto; gap: 10px; align-items: end; margin: 18px 0; }
.approval-toggle { display: flex; align-items: center; gap: 7px; margin: 10px 0; color: #526158; font-size: 13px; }
.approval-toggle input { width: auto; }
label { display: grid; gap: 5px; color: #526158; font-size: 12px; } select, input { width: 100%; box-sizing: border-box; padding: 9px; border: 1px solid #dfe7e2; border-radius: 7px; background: #fff; font: inherit; }
button { padding: 8px 12px; border: 1px solid #cedbd3; border-radius: 7px; background: #fff; cursor: pointer; } .invite-form > button, .modal-footer button { background: #1b9d61; border-color: #1b9d61; color: #fff; } button:disabled { opacity: .55; cursor: default; }
.error { color: #b24a4a; } .created-link { padding: 12px; border-radius: 9px; background: #f0faf4; } .created-link strong { display: block; margin-bottom: 8px; color: #187b4a; } .link-row input { min-width: 0; color: #526158; font-size: 12px; } .link-row button { flex: 0 0 auto; }
.section { margin-top: 22px; } .section-title { padding-bottom: 7px; border-bottom: 1px solid #e5ece7; } .section-title button { padding: 4px 8px; color: #527361; font-size: 12px; }
.member-form { display: grid; grid-template-columns: minmax(0, 1fr) 130px auto; gap: 8px; margin: 10px 0; }
.rows { display: grid; gap: 7px; margin: 8px 0 0; padding: 0; list-style: none; } .rows li { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px; border: 1px solid #e7eee9; border-radius: 8px; } .rows span:first-child { display: grid; gap: 3px; min-width: 0; } .rows small { color: #7b877f; font-size: 12px; } .rows em { color: #ad5a5a; font-style: normal; } .danger { color: #a44141; }
.modal-footer { justify-content: flex-end; margin-top: 20px; padding-top: 14px; border-top: 1px solid #e5ece7; }
@media (max-width: 600px) { .invite-form, .member-form { grid-template-columns: 1fr 1fr; } .invite-form > button, .member-form > button { grid-column: 1 / -1; } .modal { padding: 17px; } }
</style>

<style scoped>
.backdrop.embedded{position:static;display:block;background:none;padding:0;backdrop-filter:none}.embedded .modal{width:100%;max-width:none;max-height:none;padding:0;border:0;box-shadow:none;border-radius:0;overflow:visible}.embedded .hint{color:#8b9a8f;font-size:12px;line-height:1.8}.embedded .section{padding:22px 0}.embedded input,.embedded select{appearance:none;border:1px solid #dce7df;border-radius:8px;padding:10px 12px;background:white;font:inherit;color:#5b7162}.embedded input[type=checkbox]{appearance:auto}.embedded button{border-radius:7px}.embedded .modal{font-size:13px}
</style>
