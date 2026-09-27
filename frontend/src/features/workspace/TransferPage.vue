<template>
  <main class="transfer-page"><section class="card"><p class="eyebrow">kwiki / ownership</p><h1>确认转交创作者权限</h1><p class="hint">此链接仅能由指定受让人使用。确认后，原创作者保留编辑权限。</p><p v-if="loading" class="state">正在确认链接…</p><p v-else-if="error" class="state error" role="alert">{{ error }}</p><template v-else><button type="button" :disabled="accepting" @click="accept">{{ accepting ? '确认中…' : '确认接收' }}</button><p v-if="result" class="result">{{ result }}</p></template></section></main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../wiki/api';
const route = useRoute(); const router = useRouter(); const token = String(route.params.token); const loading = ref(true); const accepting = ref(false); const error = ref(''); const result = ref('');
async function validate() { loading.value = false; }
async function accept() { accepting.value = true; error.value = ''; try { await api.post(`/ownership-transfers/${encodeURIComponent(token)}/accept`); result.value = '权限转交成功，正在返回知识库。'; setTimeout(() => router.push({ name: 'knowledge-bases' }), 500); } catch { error.value = '转交链接无效、已过期，或当前账号不是指定受让人'; } finally { accepting.value = false; } }
onMounted(validate);
</script>

<style scoped>
/* 统一页面骨架：独立落地页，卡片居中 */
.transfer-page { min-height: 100vh; max-width: 960px; margin: 0 auto; display: grid; place-items: center; padding: 40px 40px 80px; color: var(--k-ink); }
.card { width: min(460px, 100%); padding: 32px; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas); box-shadow: var(--k-shadow); }
h1 { margin: 10px 0 15px; }
.hint { color: var(--k-muted); line-height: 1.7; }

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
