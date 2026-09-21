import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/vue';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import { defineComponent } from 'vue';
import ConversationMessages from '../src/features/workspace/ConversationMessages.vue';
import ChunkPreview from '../src/features/wiki/components/ChunkPreview.vue';
import { useConversationStore } from '../src/features/workspace/conversationStore';
import { api } from '../src/features/wiki/api';

const missingToast = vi.hoisted(() => ({ showMissingWikiToast: vi.fn() }));
vi.mock('../src/features/wiki/toast', () => missingToast);

const flushPromises = () => new Promise(resolve => setTimeout(resolve, 0));

describe('失效 Wiki 引用', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.restoreAllMocks();
    missingToast.showMissingWikiToast.mockClear();
  });

  it('引用页面预检失败时弹 toast 且不跳转', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/conversations', name: 'conversations', component: ConversationMessages },
        { path: '/knowledge-bases/:kbId/:pageId?', name: 'workspace', component: defineComponent({ template: '<div />' }) },
      ],
    });
    await router.push('/conversations');
    await router.isReady();

    const store = useConversationStore();
    store.history = [{ id: 1, role: 'ASSISTANT', content: '答案', createdAt: '', requestId: 'run-1' }];
    store.runCitations = {
      'run-1': [{
        childChunkKey: 'chunk-1',
        parentChunkKey: 'parent-1',
        resourceType: 'PAGE',
        resourceId: 404,
        revisionId: 1,
        headingPath: '已删除页面',
        charStart: 0,
        charEnd: 3,
        excerpt: '已删除页面',
        kbId: 12,
        displayName: '已删除页面',
      }],
    };
    vi.spyOn(api, 'json').mockRejectedValue({ status: -100, code: 'request_failed' });

    const screen = render(ConversationMessages, { global: { plugins: [router] } });
    await flushPromises();
    await fireEvent.click(screen.getByRole('button', { name: '[0] 已删除页面' }));
    await flushPromises();

    expect(missingToast.showMissingWikiToast).toHaveBeenCalledOnce();
    expect(router.currentRoute.value.fullPath).toBe('/conversations');
  });

  it('小窗检索来源跳转时页面不存在也只弹 toast', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/knowledge-bases', name: 'knowledge-bases', component: defineComponent({ template: '<div />' }) },
        { path: '/knowledge-bases/:kbId/:pageId?', name: 'workspace', component: defineComponent({ template: '<div />' }) },
      ],
    });
    await router.push('/knowledge-bases');
    await router.isReady();

    vi.spyOn(api, 'json').mockRejectedValue({ status: 404, code: 'http_404' });
    const screen = render(ChunkPreview, {
      props: {
        source: {
          childChunkKey: 'chunk-2',
          parentChunkKey: 'parent-2',
          resourceType: 'PAGE',
          resourceId: 405,
          revisionId: 1,
          headingPath: '已删除页面',
          charStart: 0,
          charEnd: 3,
          excerpt: '已删除页面',
          kbId: 12,
        },
      },
      global: {
        plugins: [router],
        stubs: {
          NModal: defineComponent({
            props: { show: Boolean },
            template: '<div v-if="show"><slot /><slot name="footer" /></div>',
          }),
        },
      },
    });
    await flushPromises();
    await fireEvent.click(screen.getByRole('button'));
    await fireEvent.click(screen.getByRole('button', { name: '跳转至 Wiki 并定位片段 ↗' }));

    expect(missingToast.showMissingWikiToast).toHaveBeenCalledOnce();
    expect(router.currentRoute.value.fullPath).toBe('/knowledge-bases');
  });
});
