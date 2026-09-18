import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, waitFor } from '@testing-library/vue';
import { defineComponent } from 'vue';
import { createRouter, createMemoryHistory } from 'vue-router';
import WorkspacePage from '../src/features/wiki/components/WorkspacePage.vue';
import { useWikiStore } from '../src/features/wiki/store';
import { createTestingPinia } from './test-pinia';

const page = {
  revisionNo: 1,
  markdown: '# 待归档页面',
  html: '<h1>待归档页面</h1>',
  createdBy: 1,
  createdAt: '2026-09-14T00:00:00Z',
  title: '待归档页面',
  canEdit: true,
  canManage: true,
};

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const data = url.endsWith('/pages/3') ? page : url.endsWith('/tree') ? [] : {};
    return new Response(JSON.stringify({ success: true, code: 200, data }), { status: 200 });
  }));
});

describe('wiki archive navigation', () => {
  it('returns to the knowledge-base overview after archiving the current page', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/knowledge-bases/:kbId/:pageId?', name: 'workspace', component: WorkspacePage, props: true }],
    });
    await router.push('/knowledge-bases/12/3');
    await router.isReady();
    const pinia = createTestingPinia();
    const store = useWikiStore(pinia);
    const screen = render(defineComponent({ template: '<RouterView />' }), {
      global: {
        plugins: [pinia, router],
        stubs: {
          PageReader: { template: '<article data-testid="page-reader">page</article>' },
          WikiInteractionPanel: { template: '<div />' },
          CollaborationPanel: { template: '<div />' },
          PageEditor: { template: '<div />' },
          RevisionHistoryDrawer: { template: '<div />' },
        },
      },
    });

    await waitFor(() => expect(screen.getByTestId('action-archive')).toBeTruthy());
    await fireEvent.click(screen.getByTestId('action-archive'));
    await fireEvent.click(screen.getByTestId('archive-next'));
    await fireEvent.click(screen.getByTestId('archive-confirm-submit'));

    await waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/knowledge-bases/12'));
    expect(store.selectedPageId).toBeNull();
    expect(store.page).toBeNull();
    expect(screen.queryByTestId('action-archive')).toBeNull();
    expect(vi.mocked(fetch).mock.calls.some(([input, init]) =>
      String(input).endsWith('/knowledge-bases/12/pages/3/archive') && init?.method === 'POST')).toBe(true);
  });
});
