<template>
  <div v-if="invite" class="backdrop" role="presentation" @click.self="dismiss">
    <section class="modal" role="dialog" aria-modal="true" aria-labelledby="invite-prompt-title">
      <p class="eyebrow">kwiki / collaboration</p>
      <h2 id="invite-prompt-title">检测到邀请链接</h2>
      <p>你复制了一个协作邀请，可以直接加入，无需打开链接。</p>
      <dl>
        <div><dt>资源</dt><dd>{{ invite.resourceType === 'KB' ? '知识库' : 'Wiki 文档' }} #{{ invite.resourceId }}</dd></div>
        <div><dt>权限</dt><dd>{{ invite.role === 'EDITOR' ? '可编辑' : '只读' }}</dd></div>
        <div><dt>有效期至</dt><dd>{{ format(invite.expiresAt) }}</dd></div>
      </dl>
      <p v-if="result" class="result" role="status">{{ result }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <footer>
        <button type="button" class="secondary" @click="dismiss">{{ result ? '关闭' : '忽略' }}</button>
        <button v-if="!result" type="button" class="primary" :disabled="accepting" @click="accept">{{ accepting ? '加入中…' : '直接加入' }}</button>
      </footer>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../wiki/api';
import { isInviteHandled, markInviteHandled, parseInviteToken } from './invitationLink';

interface Invite { resourceType: 'KB' | 'PAGE'; resourceId: number; role: string; expiresAt: string; }
const route = useRoute(); const router = useRouter();
const invite = ref<Invite | null>(null); const token = ref(''); const accepting = ref(false); const result = ref(''); const error = ref('');
let checking = false;

async function consider(text: string | null | undefined) {
  const candidate = parseInviteToken(text);
  if (!candidate || checking || invite.value || isInviteHandled(candidate)) return;
  // 正在邀请落地页上处理同一个链接时不重复弹窗
  if (route.name === 'invite' && String(route.params.token) === candidate) return;
  checking = true;
  try {
    const preview = await api.json<Invite>(`/invitations/${encodeURIComponent(candidate)}`);
    token.value = candidate; invite.value = preview; result.value = ''; error.value = '';
  } catch {
    markInviteHandled(candidate); // 无效、过期或已撤销的链接不再反复请求
  } finally { checking = false; }
}

// 仅在浏览器支持且未拒绝 clipboard-read 权限时主动读取（Chromium 系）；
// Safari/Firefox 主动读取会弹出系统粘贴确认，这些浏览器只依赖下方的 paste 事件。
async function readClipboard() {
  if (document.visibilityState !== 'visible' || !document.hasFocus() || !navigator.clipboard?.readText || !navigator.permissions?.query) return;
  try {
    const status = await navigator.permissions.query({ name: 'clipboard-read' as PermissionName });
    if (status.state === 'denied') return;
    await consider(await navigator.clipboard.readText());
  } catch { /* 不支持该权限名或读取被拒绝 */ }
}

function onPaste(event: ClipboardEvent) { void consider(event.clipboardData?.getData('text/plain')); }
function onVisibility() { if (document.visibilityState === 'visible') void readClipboard(); }
function onFocus() { void readClipboard(); }

async function accept() {
  accepting.value = true; error.value = '';
  try {
    const value = await api.post<{ status: string }>(`/invitations/${encodeURIComponent(token.value)}/accept`);
    markInviteHandled(token.value);
    result.value = value.status === 'PENDING' ? '已提交申请，等待管理员审核。' : '已加入。';
    if (value.status === 'ACCEPTED' && invite.value?.resourceType === 'KB') setTimeout(() => { invite.value = null; void router.push({ name: 'knowledge-bases' }); }, 600);
  } catch { error.value = '接受邀请失败，请稍后重试'; }
  finally { accepting.value = false; }
}

function dismiss() { if (token.value) markInviteHandled(token.value); invite.value = null; token.value = ''; }
function format(value: string) { try { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)); } catch { return value; } }

onMounted(() => {
  window.addEventListener('focus', onFocus);
  document.addEventListener('visibilitychange', onVisibility);
  document.addEventListener('paste', onPaste);
  void readClipboard();
});
onBeforeUnmount(() => {
  window.removeEventListener('focus', onFocus);
  document.removeEventListener('visibilitychange', onVisibility);
  document.removeEventListener('paste', onPaste);
});
</script>

<style scoped>
.backdrop { position: fixed; inset: 0; z-index: 90; display: grid; place-items: center; padding: 20px; background: var(--k-overlay); }
.modal { width: min(420px, 100%); box-sizing: border-box; padding: 24px; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas); color: var(--k-ink); box-shadow: var(--k-shadow-float); }
.eyebrow { margin: 0; color: var(--k-muted); font-size: 12px; font-weight: 600; letter-spacing: 0.4px; text-transform: uppercase; }
h2 { margin: 8px 0 10px; font-size: 20px; font-weight: 600; letter-spacing: -0.3px; }
p { margin: 0 0 14px; color: var(--k-ink-2); font-size: 14px; line-height: 1.6; }
dl { display: grid; gap: 8px; margin: 0 0 16px; padding: 12px 14px; border: 1px solid var(--k-line); border-radius: var(--k-r); background: var(--k-surface); }
dl div { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; }
dt { color: var(--k-muted); }
dd { margin: 0; color: var(--k-ink); font-weight: 500; }
.result { padding: 10px 14px; border-radius: var(--k-r); background: var(--k-green-soft); color: var(--k-green-deep); font-size: 13px; }
.error { color: var(--k-danger); font-size: 13px; }
footer { display: flex; justify-content: flex-end; gap: 10px; }
button { min-height: 36px; padding: 8px 16px; border: 1px solid var(--k-line); border-radius: var(--k-r); background: var(--k-canvas); color: var(--k-ink); font-weight: 500; cursor: pointer; }
button:focus-visible { outline: none; box-shadow: var(--k-focus-ring); }
.secondary:hover { background: var(--k-surface-hover); }
.primary { border-color: var(--k-primary); background: var(--k-primary); color: var(--k-on-primary); }
.primary:hover:not(:disabled) { background: var(--k-primary-hover); border-color: var(--k-primary-hover); }
button:disabled { opacity: .5; cursor: default; }
</style>
