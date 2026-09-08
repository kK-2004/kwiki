<template>
  <main class="notification-page">
    <header><div><p class="eyebrow">kwiki</p><h1>消息中心</h1><p class="sub">只展示当前账号仍有权限访问的评论提及。</p></div><button type="button" :disabled="!unread" @click="markAll">全部已读</button></header>
    <section v-if="loading" class="state">正在加载消息…</section>
    <section v-else-if="error" class="state error" role="alert">{{ error }} <button type="button" @click="load">重试</button></section>
    <section v-else-if="!items.length" class="state">暂无消息</section>
    <ul v-else class="notifications">
      <li v-for="item in items" :key="item.id" :class="{ unread: !item.readAt }">
        <button type="button" class="notification" @click="open(item)">
          <span class="dot" aria-hidden="true"></span><span class="copy"><strong>{{ item.type === 'MENTION' ? '有人在评论中提到了你' : item.type }}</strong><span>{{ item.pageTitle ? `文档：${item.pageTitle}` : '文档消息' }}</span><time>{{ formatTime(item.createdAt) }}</time></span><span class="arrow">›</span>
        </button>
      </li>
    </ul>
  </main>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { api } from '../wiki/api';
import { useWikiStore } from '../wiki/store';

interface NotificationItem { id: number; type: string; pageId: number | null; kbId: number | null; commentId: number | null; anchorId: number | null; readAt: string | null; createdAt: string; pageTitle: string | null; }
const store = useWikiStore(); const items = ref<NotificationItem[]>([]); const loading = ref(false); const error = ref('');
const unread = ref(0);
async function load() { loading.value = true; error.value = ''; try { const [rows, count] = await Promise.all([api.json<NotificationItem[]>('/notifications?limit=100'), api.json<number>('/notifications/unread-count')]); items.value = rows; unread.value = count; store.notificationUnread = count; } catch { error.value = '消息加载失败，请重试'; } finally { loading.value = false; } }
async function markAll() { await api.post('/notifications/read-all'); items.value = items.value.map((item) => ({ ...item, readAt: item.readAt ?? new Date().toISOString() })); unread.value = 0; store.notificationUnread = 0; }
async function open(item: NotificationItem) { if (!item.readAt) { await api.post(`/notifications/${item.id}/read`); item.readAt = new Date().toISOString(); unread.value = Math.max(0, unread.value - 1); store.notificationUnread = unread.value; } if (item.kbId && item.pageId) { const query = new URLSearchParams(); if (item.commentId) query.set('comment', String(item.commentId)); if (item.anchorId) query.set('anchor', String(item.anchorId)); window.location.hash = `#/knowledge-bases/${item.kbId}/${item.pageId}${query.toString() ? `?${query}` : ''}`; } }
function formatTime(value: string) { try { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)); } catch { return value; } }
let refreshTimer: ReturnType<typeof setInterval> | undefined;
function refreshWhenVisible() { if (document.visibilityState === 'visible') void load(); }
onMounted(() => { void load(); refreshTimer = setInterval(refreshWhenVisible, 60000); document.addEventListener('visibilitychange', refreshWhenVisible); });
onBeforeUnmount(() => { if (refreshTimer) clearInterval(refreshTimer); document.removeEventListener('visibilitychange', refreshWhenVisible); });
</script>

<style scoped>
.notification-page { min-height:100vh; max-width:900px; margin:0 auto; padding:56px 28px; color:#26352e; } header { display:flex; justify-content:space-between; align-items:flex-start; } .eyebrow { margin:0 0 8px; color:#1b9d61; font-size:12px; font-weight:700; text-transform:uppercase; } h1 { margin:0; font-size:32px; } .sub { color:#77847c; } header button { padding:8px 14px; border:1px solid #cedbd3; border-radius:7px; background:#fff; cursor:pointer; } header button:disabled { opacity:.5; cursor:default; } .state { margin-top:38px; padding:44px; border:1px dashed #cbd9d1; border-radius:14px; color:#718078; text-align:center; } .error { color:#b24a4a; } .notifications { display:grid; gap:8px; margin:28px 0 0; padding:0; list-style:none; } .notification { width:100%; display:flex; gap:12px; align-items:center; padding:15px 16px; text-align:left; border:1px solid #e1e9e4; border-radius:10px; background:#fff; cursor:pointer; } li.unread .notification { border-color:#bce4cc; background:#f6fcf8; } .dot { width:8px; height:8px; flex:none; border-radius:50%; background:transparent; } li.unread .dot { background:#1b9d61; } .copy { display:grid; gap:3px; flex:1; } .copy span, time { color:#77847c; font-size:13px; } .arrow { color:#92a198; font-size:22px; }
</style>
