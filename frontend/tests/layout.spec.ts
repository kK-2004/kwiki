import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/vue';
import { createRouter, createMemoryHistory } from 'vue-router';
import { defineComponent } from 'vue';
const flushPromises = () => new Promise(resolve => setTimeout(resolve, 0));
import WikiWorkspaceLayout from '../src/features/wiki/components/WikiWorkspaceLayout.vue';
import WorkspaceShell from '../src/features/workspace/WorkspaceShell.vue';
import { createTestingPinia } from './test-pinia';

const kb = { id: 12, name: '产品资料', description: '产品使用文档', canManage: true, canUpload: true };
const tree = [{ id: 2, title: '使用指南', nodeType: 'FOLDER', children: [{ id: 3, title: '安装说明', nodeType: 'PAGE', children: [] }] }];
async function mountWorkspace() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/knowledge-bases/:kbId/:pageId?', component: WikiWorkspaceLayout, props: true }] });
  await router.push('/knowledge-bases/12/3'); await router.isReady();
  const screen = render(defineComponent({ components: { WorkspaceShell }, template: '<WorkspaceShell><RouterView /></WorkspaceShell>' }), { global: { plugins: [createTestingPinia(), router] } });
  await flushPromises(); return screen;
}
beforeEach(() => { vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify({ success: true, code: 200, data: url.endsWith('/tree') ? tree : url.endsWith('/knowledge-bases/12') ? kb : url.endsWith('/unread-count') ? 0 : [] })))); });
describe('workspace navigation', () => {
  it('keeps the shared sidebar and derives the full path from the selected knowledge base and tree', async () => {
    const screen = await mountWorkspace();
    expect(screen.getByTestId('global-nav')).toBeTruthy(); expect(screen.getByTestId('middle-column')).toBeTruthy(); expect(screen.getByTestId('content-column')).toBeTruthy();
    const crumb = screen.getByRole('navigation', { name: '面包屑' });
    expect(crumb.textContent).toBe('知识库›产品资料/使用指南/安装说明');
    expect(screen.queryByRole('link', { name: '智能体' })).toBeNull();
  });
  it('keeps knowledge and summary tabs accessible', async () => {
    const screen = await mountWorkspace(); const tabs = screen.getAllByRole('tab');
    expect(tabs[0].getAttribute('aria-selected')).toBe('true'); await fireEvent.click(tabs[1]);
    expect(tabs[1].getAttribute('aria-selected')).toBe('true');
  });
  it('publishes an initial revision when creating a page', async () => {
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = String(input);
      const data = url.endsWith('/nodes')
        ? { id: 9 }
        : url.endsWith('/tree')
          ? tree
          : url.endsWith('/knowledge-bases/12')
            ? kb
            : url.endsWith('/unread-count')
              ? 0
              : [];
      return new Response(JSON.stringify({ success: true, code: 200, data }));
    });
    const screen = await mountWorkspace();
    await fireEvent.click(screen.getByRole('button', { name: '新建页面' }));
    await fireEvent.update(screen.getByRole('textbox', { name: '名称' }), '发布指南');
    await fireEvent.click(screen.getByRole('button', { name: '创建' }));
    await flushPromises();
    const calls = vi.mocked(fetch).mock.calls.map(([input, init]) => [String(input), init?.method]);
    expect(calls).toContainEqual(['/api/v1/knowledge-bases/12/nodes', 'POST']);
    expect(calls).toContainEqual(['/api/v1/knowledge-bases/12/pages/9/draft', 'PUT']);
    expect(calls).toContainEqual(['/api/v1/knowledge-bases/12/pages/9/publish', 'POST']);
  });
});
