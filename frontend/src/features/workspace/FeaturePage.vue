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
.feature-page { min-height: 100vh; max-width: 980px; margin: 0 auto; padding: 56px 28px; color: #26352e; }
header { display: flex; justify-content: space-between; align-items: center; } .eyebrow { margin: 0 0 8px; color: #1b9d61; font-size: 12px; font-weight: 700; text-transform: uppercase; } h1 { margin: 0; font-size: 32px; }
header button, .state button { padding: 8px 14px; border: 1px solid #cedbd3; border-radius: 7px; background: #fff; cursor: pointer; }
.state { margin-top: 38px; padding: 44px; border: 1px dashed #cbd9d1; border-radius: 14px; color: #718078; text-align: center; } .state p { margin-bottom: 0; } .error { color: #b24a4a; }
.items { display: grid; gap: 10px; padding: 30px 0; list-style: none; } .items li { display: grid; gap: 5px; padding: 17px 19px; border: 1px solid #e1e9e4; border-radius: 10px; } .items li a { display:grid; gap:5px; color:inherit; text-decoration:none; } .items span { color: #77847c; font-size: 13px; }
</style>
