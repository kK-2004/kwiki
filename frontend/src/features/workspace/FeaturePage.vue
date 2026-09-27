<template>
  <main class="feature-page">
    <header><div><p class="eyebrow">kwiki</p><h1>{{ title }}</h1></div><div class="header-actions"><form v-if="route.name === 'search'" @submit.prevent="submitSearch"><input v-model="search" aria-label="搜索内容" placeholder="搜索标题或正文" /></form><button type="button" @click="reload">刷新</button></div></header>
    <section v-if="loading" class="state">正在加载…</section>
    <section v-else-if="error" class="state error" role="alert">{{ error }} <button type="button" @click="reload">重试</button></section>
    <section v-else-if="items.length === 0" class="state"><strong>暂无{{ title }}内容</strong><p>当前账号还没有可展示的记录。</p></section>
    <ul v-else class="items"><li v-for="item in items" :key="item.id"><a v-if="item.pageId && item.kbId" :href="`#/knowledge-bases/${item.kbId}/${item.pageId}`"><strong>{{ item.title }}</strong><span>{{ item.description ?? '' }}</span></a><template v-else><strong>{{ item.title }}</strong><span>{{ item.description ?? '' }}</span></template></li></ul>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../wiki/api';

const route = useRoute();
const router = useRouter();
const title = computed(() => String(route.meta.title ?? '页面'));
const items = ref<Array<{ id: string | number; title: string; description?: string; pageId?: number; kbId?: number }>>([]);
const loading = ref(false); const error = ref('');
let reloadController: AbortController | null = null;
const search = ref(typeof route.query.q === 'string' ? route.query.q : '');
const endpoint = computed(() => {
  if (route.name === 'knowledge-bases') return '/knowledge-bases';
  if (route.name === 'conversations') return '/chat/sessions';
  if (route.name === 'notifications') return '/notifications';
  if (route.name === 'shared') return '/shared';
  if (route.name === 'search') return `/search${route.query.q ? `?q=${encodeURIComponent(String(route.query.q))}` : ''}`;
  if (route.name === 'profile' || route.name === 'profile-likes') return `/users/${route.params.userId ?? 'me'}/likes`;
  if (route.name === 'profile-favorites') return `/users/${route.params.userId ?? 'me'}/favorites`;
  return '';
});
async function reload() {
  if (!endpoint.value) return;
  reloadController?.abort();
  const controller = new AbortController(); reloadController = controller;
  loading.value = true; error.value = '';
  try {
    const data = await api.json<unknown>(endpoint.value, controller.signal);
    const list = Array.isArray(data) ? data : (data && typeof data === 'object' && 'items' in data ? (data as { items: unknown[] }).items : []);
    items.value = (list as Array<Record<string, unknown>>).map((entry, index) => ({
      id: String(entry.id ?? entry.uuid ?? index),
      title: String(entry.title ?? entry.name ?? entry.username ?? '未命名'),
      description: entry.description == null ? undefined : String(entry.description),
      pageId: entry.pageId == null ? undefined : Number(entry.pageId),
      kbId: entry.kbId == null ? undefined : Number(entry.kbId),
    }));
  } catch (e) {
    if ((e as { name?: string })?.name !== 'AbortError') error.value = e instanceof Error ? e.message : '请求失败，请重试';
  } finally { if (reloadController === controller) { reloadController = null; loading.value = false; } }
}
watch(endpoint, reload); onMounted(reload);
function submitSearch() { void router.replace({ name: 'search', query: search.value.trim() ? { q: search.value.trim() } : {} }); }
</script>

<style scoped>
/* 统一页面骨架 */
.feature-page { min-height: 100vh; max-width: 960px; margin: 0 auto; padding: 40px 40px 80px; color: var(--k-ink); }
header { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.eyebrow { margin: 0 0 8px; color: var(--k-muted); font-size: 12px; font-weight: 600; letter-spacing: 0.4px; text-transform: uppercase; }
h1 { margin: 0; font-size: 28px; font-weight: 600; letter-spacing: -0.5px; color: var(--k-ink); }
.header-actions { display: flex; align-items: center; gap: 8px; }
.header-actions form { margin: 0; }
.header-actions input { width: 240px; min-height: 36px; padding: 7px 12px; border: 1px solid var(--k-line); border-radius: var(--k-r); background: var(--k-canvas); color: var(--k-ink); outline: none; }
.header-actions input:hover { border-color: var(--k-line-strong); }
.header-actions input:focus { border-color: var(--k-green); box-shadow: var(--k-focus-ring); }
header button, .state button { min-height: 36px; padding: 7px 14px; border: 1px solid var(--k-line); border-radius: var(--k-r); background: var(--k-canvas); color: var(--k-ink); font-weight: 500; box-shadow: var(--k-shadow-sm); cursor: pointer; }
header button:hover, .state button:hover { border-color: var(--k-line-strong); background: var(--k-surface); }
/* 空状态 / 加载 / 错误 */
.state { margin-top: 32px; padding: 48px 24px; border: 1px dashed var(--k-line); border-radius: var(--k-r-lg); background: var(--k-surface); color: var(--k-muted); font-size: 13px; text-align: center; }
.state strong { display: block; color: var(--k-ink); font-size: 15px; font-weight: 600; }
.state p { margin: 6px 0 0; color: var(--k-muted); font-size: 13px; }
.error { border-color: var(--k-danger-line); background: var(--k-danger-soft); color: var(--k-danger); }
.state button { margin-left: 8px; }
/* 列表：整体描边，行间细线分隔 */
.items { display: grid; margin: 28px 0 0; padding: 0; list-style: none; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); overflow: hidden; }
.items li { display: grid; gap: 4px; padding: 14px 16px; background: var(--k-canvas); }
.items li + li { border-top: 1px solid var(--k-line); }
.items li:hover { background: var(--k-surface-hover); }
.items li a { display: grid; gap: 4px; color: inherit; text-decoration: none; }
.items strong { font-size: 14px; font-weight: 600; color: var(--k-ink); }
.items span { color: var(--k-muted); font-size: 13px; }
</style>
