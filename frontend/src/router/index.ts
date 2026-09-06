import { createRouter, createWebHashHistory } from 'vue-router';

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/:kbId?/:pageId?',
      component: () => import('../features/wiki/components/WikiWorkspaceLayout.vue'),
      children: [
        {
          path: '',
          name: 'workspace',
          component: () => import('../features/wiki/components/WorkspacePage.vue'),
          props: true,
        },
      ],
    },
  ],
});
