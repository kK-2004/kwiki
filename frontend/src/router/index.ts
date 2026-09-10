import { createRouter, createWebHashHistory } from 'vue-router';
import { getAuthToken } from '../features/wiki/api';

const routes = [
  { path: '/', redirect: '/knowledge-bases' },
  { path: '/login', name: 'login', component: () => import('../features/auth/AuthPage.vue'), props: { mode: 'login' } },
  { path: '/register', name: 'register', component: () => import('../features/auth/AuthPage.vue'), props: { mode: 'register' } },
  { path: '/invite/:token', name: 'invite', component: () => import('../features/workspace/InvitePage.vue'), meta: { auth: true, title: '加入共享' } },
  { path: '/transfer/:token', name: 'transfer', component: () => import('../features/workspace/TransferPage.vue'), meta: { auth: true, title: '确认转交' } },
  { path: '/knowledge-bases', name: 'knowledge-bases', component: () => import('../features/workspace/KnowledgeBasePage.vue'), meta: { auth: true, title: '知识库' } },
  { path: '/knowledge-bases/:kbId/settings', name: 'knowledge-base-settings', component: () => import('../features/workspace/KnowledgeBaseSettings.vue'), props: true, meta: { auth: true, title: '知识库设置' } },
  { path: '/knowledge-bases/:kbId/trash', name: 'knowledge-base-trash', component: () => import('../features/workspace/TrashPage.vue'), props: true, meta: { auth: true, title: '回收站' } },
  { path: '/trash', name: 'workspace-trash', component: () => import('../features/workspace/TrashPage.vue'), meta: { auth: true, title: '已归档的知识库' } },
  {
    path: '/knowledge-bases/:kbId/:pageId?',
    name: 'wiki-shell',
    component: () => import('../features/wiki/components/WikiWorkspaceLayout.vue'),
    meta: { auth: true },
    props: true,
    children: [{ path: '', name: 'workspace', component: () => import('../features/wiki/components/WorkspacePage.vue'), props: true }],
  },
  { path: '/conversations/:sessionId?', name: 'conversations', component: () => import('../features/workspace/ConversationPage.vue'), meta: { auth: true, title: '会话' } },
  { path: '/agents/:agentId?', redirect: '/conversations' },
  { path: '/notifications', name: 'notifications', component: () => import('../features/workspace/NotificationCenter.vue'), meta: { auth: true, title: '消息中心' } },
  { path: '/me', redirect: '/users/me' },
  { path: '/users/:userId/likes', name: 'profile-likes', component: () => import('../features/workspace/FeaturePage.vue'), meta: { auth: true, title: '我的点赞' } },
  { path: '/users/:userId/favorites', name: 'profile-favorites', component: () => import('../features/workspace/FeaturePage.vue'), meta: { auth: true, title: '我的收藏' } },
  { path: '/users/:userId?', name: 'profile', component: () => import('../features/workspace/FeaturePage.vue'), meta: { auth: true, title: '个人主页' } },
  { path: '/:kbId(\\d+)/:pageId(\\d+)', redirect: (to: any) => `/knowledge-bases/${to.params.kbId}/${to.params.pageId}` },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../features/workspace/FeaturePage.vue'), meta: { title: '页面不存在' } },
];

export const router = createRouter({ history: createWebHashHistory(), routes });

router.beforeEach(async (to) => {
  if (to.meta.auth && !getAuthToken()) {
    return { name: 'login', query: { returnTo: to.fullPath } };
  }
  if ((to.name === 'login' || to.name === 'register') && getAuthToken()) {
    return '/knowledge-bases';
  }
  return true;
});
