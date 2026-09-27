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
/* 统一页面骨架 */
.notification-page { min-height: 100vh; max-width: 960px; margin: 0 auto; padding: 40px 40px 80px; color: var(--k-ink); }
header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.eyebrow { margin: 0 0 8px; color: var(--k-muted); font-size: 12px; font-weight: 600; letter-spacing: 0.4px; text-transform: uppercase; }
h1 { margin: 0; font-size: 28px; font-weight: 600; letter-spacing: -0.5px; color: var(--k-ink); }
.sub { margin: 8px 0 0; color: var(--k-muted); font-size: 14px; }
header button, .state button { min-height: 36px; padding: 7px 14px; border: 1px solid var(--k-line); border-radius: var(--k-r); background: var(--k-canvas); color: var(--k-ink); font-weight: 500; box-shadow: var(--k-shadow-sm); cursor: pointer; }
header button:hover:not(:disabled), .state button:hover { border-color: var(--k-line-strong); background: var(--k-surface); }
header button:disabled { opacity: .5; cursor: default; }
/* 空状态 / 加载 / 错误 */
.state { margin-top: 32px; padding: 48px 24px; border: 1px dashed var(--k-line); border-radius: var(--k-r-lg); background: var(--k-surface); color: var(--k-muted); font-size: 13px; text-align: center; }
.error { border-color: var(--k-danger-line); background: var(--k-danger-soft); color: var(--k-danger); }
.state button { margin-left: 8px; }
/* 列表：整体描边，行间细线分隔 */
.notifications { display: grid; margin: 28px 0 0; padding: 0; list-style: none; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); overflow: hidden; }
.notifications li + li { border-top: 1px solid var(--k-line); }
.notification { width: 100%; display: flex; gap: 12px; align-items: center; padding: 14px 16px; text-align: left; border: 0; background: var(--k-canvas); color: var(--k-ink); cursor: pointer; }
.notification:hover { background: var(--k-surface-hover); }
li.unread .notification { background: var(--k-surface); }
li.unread .notification:hover { background: var(--k-surface-hover); }
.dot { width: 8px; height: 8px; flex: none; border-radius: 50%; background: transparent; }
li.unread .dot { background: var(--k-green); }
.copy { display: grid; gap: 3px; flex: 1; }
.copy strong { font-size: 14px; font-weight: 600; color: var(--k-ink); }
.copy span, time { color: var(--k-muted); font-size: 13px; }
time { color: var(--k-faint); font-size: 12px; }
.arrow { color: var(--k-faint); font-size: 22px; }
</style>
