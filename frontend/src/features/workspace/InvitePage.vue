<template>
  <main class="invite-page"><section class="card"><p class="eyebrow">kwiki / collaboration</p><h1>加入协作</h1><section v-if="loading" class="state">正在验证邀请链接…</section><section v-else-if="error" class="state error" role="alert">{{ error }}</section><section v-else-if="invite"><p>资源：{{ invite.resourceType === 'KB' ? '知识库' : 'Wiki 文档' }} #{{ invite.resourceId }}</p><p>权限：{{ invite.role === 'EDITOR' ? '可编辑' : '只读' }}</p><p class="hint">有效期至 {{ format(invite.expiresAt) }}</p><button type="button" :disabled="accepting" @click="accept">{{ accepting ? '加入中…' : '接受邀请' }}</button><p v-if="result" class="result">{{ result }}</p></section></section></main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../wiki/api';
interface Invite { resourceType: string; resourceId: number; role: string; expiresAt: string; }
const route = useRoute(); const router = useRouter(); const invite = ref<Invite | null>(null); const loading = ref(false); const accepting = ref(false); const error = ref(''); const result = ref(''); const token = String(route.params.token);
async function load() { loading.value = true; try { invite.value = await api.json<Invite>(`/invitations/${encodeURIComponent(token)}`); } catch { error.value = '邀请链接无效、已过期或已撤销'; } finally { loading.value = false; } }
async function accept() { accepting.value = true; try { const value = await api.post<{ status: string }>(`/invitations/${encodeURIComponent(token)}/accept`); result.value = value.status === 'PENDING' ? '已提交申请，等待管理员审核。' : '已加入，正在打开知识库。'; if (value.status === 'ACCEPTED' && invite.value?.resourceType === 'KB') setTimeout(() => router.push({ name: 'knowledge-bases' }), 300); } catch { error.value = '接受邀请失败，请稍后重试'; } finally { accepting.value = false; } }
function format(value: string) { try { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)); } catch { return value; } }
onMounted(load);
</script>

<style scoped>
.invite-page { min-height:100vh; display:grid; place-items:center; padding:20px; background:#f6faf7; color:#26352e; } .card { width:min(460px,100%); padding:30px; border:1px solid #e0eae3; border-radius:14px; background:#fff; box-shadow:0 14px 50px #1d3d2b12; } .eyebrow { color:#1b9d61; font-size:12px; font-weight:700; text-transform:uppercase; } h1 { margin:10px 0 22px; } .state { padding:22px 0; color:#718078; } .error { color:#b24a4a; } .hint { color:#77847c; font-size:13px; } button { margin-top:12px; padding:10px 16px; border:0; border-radius:8px; background:#1b9d61; color:#fff; cursor:pointer; } button:disabled { opacity:.5; } .result { margin-top:15px; color:#187b4a; }
</style>
