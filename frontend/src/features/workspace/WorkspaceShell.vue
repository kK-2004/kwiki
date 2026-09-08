<template>
  <div class="workspace-shell" :class="{ collapsed }">
    <button class="mobile-menu ui-icon" aria-label="打开全局导航" @click="mobileOpen = !mobileOpen"><i class="i-lucide-menu" /></button>
    <aside class="global-nav" :class="{ open: mobileOpen }" aria-label="全局导航" data-testid="global-nav">
      <div class="brand-row"><RouterLink to="/knowledge-bases" class="brand"><img src="/kwiki-logo.svg" width="29" height="29" alt="" /><strong v-if="!collapsed">kwiki</strong></RouterLink><button class="ui-icon collapse-button" :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'" @click="collapsed = !collapsed"><i :class="collapsed ? 'i-lucide-panel-left-open' : 'i-lucide-panel-left-close'" /></button></div>
      <button class="new-chat" @click="newChat"><i class="i-lucide-message-square-plus" /><span v-if="!collapsed">新聊天</span></button>
      <nav aria-label="主要功能"><RouterLink v-for="item in navigation" :key="item.to" :to="item.to" :title="item.label" class="nav-item" :class="{ active: route.path.startsWith(item.to) }"><i :class="item.icon" /><span v-if="!collapsed">{{ item.label }}</span><small v-if="item.to === '/notifications' && wiki.notificationUnread && !collapsed">{{ wiki.notificationUnread }}</small></RouterLink></nav>
      <div v-if="!collapsed" class="recent-list"><p class="section-label">最近访问</p><p v-if="!wiki.recentVisits.length" class="muted">打开的文档会显示在这里</p><RouterLink v-for="visit in wiki.recentVisits.slice(0, 12)" :key="visit.id" :to="visit.path" :title="visit.title"><i class="i-lucide-file-text" />{{ visit.title }}</RouterLink></div>
      <RouterLink to="/me" class="account"><span class="avatar">{{ (auth.user?.username || '?').slice(0, 1).toUpperCase() }}</span><span v-if="!collapsed"><strong>{{ auth.user?.displayName || auth.user?.username }}</strong><small>个人空间</small></span><i v-if="!collapsed" class="i-lucide-chevron-right" /></RouterLink>
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
const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const wiki = useWikiStore(); const conversation = useConversationStore();
const collapsed = ref(false); const mobileOpen = ref(false);
const navigation = [ { to: '/conversations', label: '聊天记录', icon: 'i-lucide-messages-square' }, { to: '/knowledge-bases', label: '知识库', icon: 'i-lucide-library' }, { to: '/shared', label: '共享空间', icon: 'i-lucide-share-2' }, { to: '/search', label: '搜索', icon: 'i-lucide-search' }, { to: '/notifications', label: '消息中心', icon: 'i-lucide-bell' } ];
async function newChat() { await conversation.newConversation(); await router.push('/conversations'); }
onMounted(() => { void wiki.loadRecentVisits(); void wiki.loadNotificationCount(); });
watch(() => route.fullPath, () => { mobileOpen.value = false; void wiki.loadRecentVisits(); });
</script>
<style scoped>
.workspace-shell{height:100dvh;display:grid;grid-template-columns:224px minmax(0,1fr);overflow:hidden}.workspace-shell.collapsed{grid-template-columns:76px minmax(0,1fr)}.global-nav{min-height:0;display:flex;flex-direction:column;padding:24px 14px 16px;background:#f7f9f8;border-right:1px solid var(--kwiki-line);gap:22px}.brand-row,.brand{display:flex;align-items:center;gap:10px}.brand-row{justify-content:space-between;padding:0 4px}.brand{color:var(--kwiki-ink);text-decoration:none;font-size:24px;letter-spacing:-.8px}.brand-row .ui-icon{width:26px}.collapsed .brand-row{flex-wrap:wrap;justify-content:center}.new-chat{display:flex;align-items:center;justify-content:center;gap:10px;min-height:42px;border:1px solid #cfe3d7;background:#eaf4ee;border-radius:9px;color:#246b48;font-weight:600;cursor:pointer}.new-chat i,.nav-item i{font-size:18px;flex-shrink:0}nav{display:grid;gap:5px}.nav-item{display:flex;align-items:center;gap:12px;padding:11px 12px;border-radius:8px;text-decoration:none;color:#58655e}.nav-item:hover,.nav-item.active{background:#e9eeeb;color:#233e30}.nav-item.active{font-weight:600}.nav-item small{margin-left:auto;color:#267d50}.recent-list{min-height:0;flex:1;overflow:auto}.section-label{font-size:11px;color:#8a958e;letter-spacing:1px;margin:0 10px 12px}.recent-list a{display:flex;align-items:center;gap:8px;padding:9px 10px;font-size:13px;color:#66736b;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;text-decoration:none}.recent-list a:hover{background:#edf1ee;border-radius:7px}.muted{font-size:12px;color:#929b95;margin:10px}.account{margin-top:auto;border-top:1px solid #e6ebe7;padding:18px 4px 0;display:flex;align-items:center;gap:10px;text-decoration:none;color:#34483b}.avatar{width:33px;height:33px;border-radius:50%;background:#ddede2;display:grid;place-items:center;color:#3a7a53;font-weight:700}.account strong,.account small{display:block}.account strong{font-size:13px}.account small{font-size:11px;color:#8b978f;margin-top:3px}.account i{margin-left:auto;color:#8a958e}.workspace-body{min-width:0;min-height:0;overflow:auto;position:relative}.mobile-menu,.nav-backdrop{display:none}@media(max-width:760px){.workspace-shell,.workspace-shell.collapsed{grid-template-columns:minmax(0,1fr)}.global-nav{display:none;position:fixed;inset:0 auto 0 0;width:224px;z-index:80}.global-nav.open{display:flex}.mobile-menu{display:grid!important;position:fixed;top:12px;left:12px;z-index:65;background:white!important;border:1px solid #e0e8e2!important}.nav-backdrop{display:block;position:fixed;inset:0;background:#14271c44;z-index:70}.collapse-button{display:none}.workspace-body{padding-top:48px}}
</style>
