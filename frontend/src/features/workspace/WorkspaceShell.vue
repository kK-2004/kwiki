<template>
  <div class="workspace-shell" :class="{ collapsed }">
    <button class="mobile-menu ui-icon" aria-label="打开全局导航" @click="mobileOpen = !mobileOpen"><i class="i-lucide-menu" /></button>
    <aside class="global-nav" :class="{ open: mobileOpen }" aria-label="全局导航" data-testid="global-nav">
      <div class="brand-row"><RouterLink to="/knowledge-bases" class="brand"><img src="/kwiki-logo.svg" width="29" height="29" alt="" /><strong v-if="!collapsed">kwiki</strong></RouterLink><button class="ui-icon collapse-button" :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'" @click="collapsed = !collapsed"><i :class="collapsed ? 'i-lucide-panel-left-open' : 'i-lucide-panel-left-close'" /></button></div>
      <button class="new-chat" @click="newChat"><i class="i-lucide-message-square-plus" /><span v-if="!collapsed">新聊天</span></button>
      <nav aria-label="主要功能"><RouterLink v-for="item in navigation" :key="item.to" :to="item.to" :title="item.label" class="nav-item" :class="{ active: route.path.startsWith(item.to) }"><i :class="item.icon" /><span v-if="!collapsed">{{ item.label }}</span><small v-if="item.to === '/notifications' && wiki.notificationUnread && !collapsed">{{ wiki.notificationUnread }}</small></RouterLink></nav>
      <section v-if="!collapsed" class="recent-list" aria-labelledby="recent-visits-title">
        <div class="recent-heading">
          <p id="recent-visits-title" class="section-label">最近访问</p>
          <span v-if="wiki.recentVisits.length" class="recent-count" aria-hidden="true">{{ wiki.recentVisits.length }}</span>
        </div>
        <p v-if="!wiki.recentVisits.length" class="recent-empty"><i class="i-lucide-history" />打开的文档会显示在这里</p>
        <ol v-else class="recent-items">
          <li v-for="visit in wiki.recentVisits" :key="visit.id">
            <RouterLink
              :to="visit.path"
              :title="visit.title"
              class="recent-item"
              :class="{ current: route.path === visit.path }"
              :aria-current="route.path === visit.path ? 'page' : undefined"
            >
              <span class="recent-icon" aria-hidden="true"><i class="i-lucide-file-text" /></span>
              <span class="recent-title">{{ visit.title }}</span>
              <span v-if="route.path === visit.path" class="current-dot" aria-hidden="true" />
            </RouterLink>
          </li>
        </ol>
      </section>
      <footer class="nav-footer">
        <RouterLink to="/users/me" class="account"><span class="avatar">{{ (auth.user?.username || '?').slice(0, 1).toUpperCase() }}</span><span v-if="!collapsed" class="account-text"><strong>{{ auth.user?.displayName || auth.user?.username }}</strong><small>个人空间</small></span></RouterLink>
        <ThemeSwitcher variant="menu" compact />
      </footer>
    </aside>
    <div v-if="mobileOpen" class="nav-backdrop" @click="mobileOpen = false" />
    <div class="workspace-body"><slot /></div>
    <ConversationLauncher />
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '../auth/store';
import { useWikiStore } from '../wiki/store';
import { useConversationStore } from './conversationStore';
import ConversationLauncher from './ConversationLauncher.vue';
import ThemeSwitcher from '../../theme/ThemeSwitcher.vue';
const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const wiki = useWikiStore(); const conversation = useConversationStore();
const collapsed = ref(false); const mobileOpen = ref(false);
const navigation = [ { to: '/conversations', label: '聊天记录', icon: 'i-lucide-messages-square' }, { to: '/knowledge-bases', label: '知识库', icon: 'i-lucide-library' }, { to: '/trash', label: '回收站', icon: 'i-lucide-archive' }, { to: '/notifications', label: '消息中心', icon: 'i-lucide-bell' } ];
async function newChat() { await conversation.newConversation(); await router.push('/conversations'); }
onMounted(() => { void wiki.loadRecentVisits(); void wiki.loadNotificationCount(); });
watch(() => route.fullPath, () => { mobileOpen.value = false; });
</script>
<style scoped>
.workspace-shell {
  height: 100dvh;
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  overflow: hidden;
  background: var(--k-canvas);
}

.workspace-shell.collapsed {
  grid-template-columns: 68px minmax(0, 1fr);
}

.global-nav {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 16px 12px 12px;
  background: var(--k-surface);
  border-right: 1px solid var(--k-line);
}

.brand-row,
.brand {
  display: flex;
  align-items: center;
  gap: 9px;
}

.brand-row {
  justify-content: space-between;
  padding: 0 4px;
  min-height: 32px;
}

.brand {
  color: var(--k-ink);
  text-decoration: none;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.5px;
}

.brand img {
  width: 26px;
  height: 26px;
}

.brand-row .ui-icon {
  width: 28px;
  height: 28px;
}

.collapsed .brand-row {
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.new-chat {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 36px;
  border: 1px solid var(--k-primary);
  border-radius: var(--k-r);
  background: var(--k-primary);
  color: var(--k-on-primary);
  font-weight: 500;
  cursor: pointer;
  box-shadow: var(--k-shadow-sm);
}

.new-chat:hover {
  background: var(--k-primary-hover);
}

.new-chat i,
.nav-item i {
  font-size: 16px;
  flex-shrink: 0;
}

nav {
  display: grid;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 34px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: var(--k-r);
  text-decoration: none;
  color: var(--k-ink-2);
  font-size: 13.5px;
}

.nav-item i {
  color: var(--k-muted);
}

.nav-item:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}

.nav-item.active {
  background: var(--k-canvas);
  border-color: var(--k-line);
  box-shadow: var(--k-shadow-sm);
  color: var(--k-ink);
  font-weight: 600;
}

.nav-item.active i {
  color: var(--k-green-deep);
}

.nav-item small {
  margin-left: auto;
  min-width: 18px;
  padding: 0 6px;
  border-radius: var(--k-r-pill);
  background: var(--k-green-soft);
  color: var(--k-green-deep);
  font-size: 11px;
  font-weight: 600;
  text-align: center;
}

.recent-list {
  min-height: 0;
  flex: 1;
  overflow: auto;
}

.recent-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px;
  margin-bottom: 6px;
}

.section-label {
  margin: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--k-faint);
  letter-spacing: 0.6px;
}

.recent-count {
  min-width: 18px;
  padding: 0 6px;
  border-radius: var(--k-r-pill);
  background: var(--k-surface-hover);
  color: var(--k-muted);
  font-size: 10px;
  text-align: center;
}

.recent-items {
  display: grid;
  gap: 1px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.recent-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 30px;
  padding: 4px 10px;
  border-radius: var(--k-r-sm);
  color: var(--k-muted);
  text-decoration: none;
}

.recent-item:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}

.recent-item.current {
  background: var(--k-surface-active);
  color: var(--k-ink);
  font-weight: 500;
}

.recent-icon {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  color: var(--k-faint);
}

.recent-item.current .recent-icon {
  color: var(--k-green-deep);
}

.recent-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 20px;
}

.current-dot {
  width: 5px;
  height: 5px;
  flex: 0 0 5px;
  margin-left: auto;
  border-radius: 50%;
  background: var(--k-green);
}

.recent-empty {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 4px 6px;
  padding: 10px;
  border: 1px dashed var(--k-line-strong);
  border-radius: var(--k-r);
  color: var(--k-faint);
  font-size: 12px;
  line-height: 18px;
}

.recent-empty i {
  margin-top: 1px;
}

.nav-footer {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 4px;
  padding-top: 12px;
  border-top: 1px solid var(--k-line);
}

.collapsed .nav-footer {
  flex-direction: column;
}

.account {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 6px;
  border-radius: var(--k-r);
  text-decoration: none;
  color: var(--k-ink);
}

.account:hover {
  background: var(--k-surface-hover);
}

.avatar {
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  border-radius: 50%;
  background: var(--k-green-soft);
  display: grid;
  place-items: center;
  color: var(--k-green-deep);
  font-size: 12px;
  font-weight: 700;
}

.account-text {
  min-width: 0;
}

.account strong,
.account small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account strong {
  font-size: 13px;
  font-weight: 600;
}

.account small {
  margin-top: 1px;
  font-size: 11px;
  color: var(--k-muted);
}

.workspace-body {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  position: relative;
  background: var(--k-canvas);
}

.mobile-menu,
.nav-backdrop {
  display: none;
}

@media (max-width: 760px) {
  .workspace-shell,
  .workspace-shell.collapsed {
    grid-template-columns: minmax(0, 1fr);
  }

  .global-nav {
    display: none;
    position: fixed;
    inset: 0 auto 0 0;
    width: 232px;
    z-index: 80;
  }

  .global-nav.open {
    display: flex;
  }

  .mobile-menu {
    display: grid !important;
    position: fixed;
    top: 12px;
    left: 12px;
    z-index: 65;
    background: var(--k-canvas) !important;
    border: 1px solid var(--k-line) !important;
  }

  .nav-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    background: var(--k-overlay);
    z-index: 70;
  }

  .collapse-button {
    display: none;
  }

  .workspace-body {
    padding-top: 48px;
  }
}
</style>
