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
.transfer-page { min-height: 100vh; display: grid; place-items: center; padding: 20px; background: #f6faf7; color: #26352e; }
.card { width: min(460px, 100%); padding: 30px; border: 1px solid #e0eae3; border-radius: 14px; background: #fff; box-shadow: 0 14px 50px #1d3d2b12; }
.eyebrow { color: #1b9d61; font-size: 12px; font-weight: 700; text-transform: uppercase; } h1 { margin: 10px 0 15px; font-size: 24px; } .hint, .state { color: #718078; line-height: 1.7; } .error { color: #b24a4a; } button { margin-top: 14px; padding: 10px 16px; border: 0; border-radius: 8px; background: #1b9d61; color: #fff; cursor: pointer; } button:disabled { opacity: .55; } .result { color: #187b4a; }
</style>
