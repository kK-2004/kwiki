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
/* 统一页面骨架：独立落地页，卡片居中 */
.invite-page { min-height: 100vh; max-width: 960px; margin: 0 auto; display: grid; place-items: center; padding: 40px 40px 80px; color: var(--k-ink); }
.card { width: min(460px, 100%); padding: 32px; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas); box-shadow: var(--k-shadow); }
h1 { margin: 10px 0 22px; }
.state { padding: 22px 0; }
.card p { color: var(--k-ink-2); }
.card .hint { color: var(--k-muted); font-size: 13px; }
.card .error { color: var(--k-danger); }
.card .result { color: var(--k-green-deep); }

.eyebrow { margin: 0; color: var(--k-muted); font-size: 12px; font-weight: 600; letter-spacing: 0.4px; text-transform: uppercase; }
h1 { font-size: 28px; font-weight: 600; letter-spacing: -0.5px; color: var(--k-ink); }
.state { color: var(--k-muted); line-height: 1.7; }
.error { color: var(--k-danger); }
/* 主按钮：浅色黑底白字，深色白底黑字 */
button { margin-top: 14px; min-height: 36px; padding: 8px 16px; border: 1px solid var(--k-primary); border-radius: var(--k-r); background: var(--k-primary); color: var(--k-on-primary); font-weight: 500; cursor: pointer; }
button:hover:not(:disabled) { background: var(--k-primary-hover); border-color: var(--k-primary-hover); }
button:disabled { opacity: .5; }
.result { margin-top: 15px; padding: 10px 14px; border-radius: var(--k-r); background: var(--k-green-soft); color: var(--k-green-deep); font-size: 13px; }
</style>
