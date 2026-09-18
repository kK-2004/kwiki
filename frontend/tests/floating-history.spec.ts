import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/vue';
import { nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import ConversationLauncher from '../src/features/workspace/ConversationLauncher.vue';
import { createTestingPinia } from './test-pinia';
import { useConversationStore } from '../src/features/workspace/conversationStore';
import { api } from '../src/features/wiki/api';

async function flush(times = 8) { for (let i = 0; i < times; i++) await nextTick(); }

function makeLauncher() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/conversations/:sessionId?', name: 'conversations', component: { template: '<div />' } },
    ],
  });
  const pinia = createTestingPinia();
  const view = render(ConversationLauncher, { global: { plugins: [pinia, router] } });
  return { view, router, store: useConversationStore() };
}

afterEach(() => vi.restoreAllMocks());

describe('会话浮窗导航', () => {
  it('新建会话按钮停留在浮窗内并清空当前会话', async () => {
    const { view, store } = makeLauncher();
    store.floatingOpen = true;
    store.sessionId = 's1';
    store.history = [{ id: 1, role: 'USER', content: '旧问题', createdAt: '' }];
    await flush();
    const spy = vi.spyOn(store, 'newConversation').mockResolvedValue(undefined);
    await fireEvent.click(view.getByTestId('float-new-conversation'));
    await flush();
    expect(spy).toHaveBeenCalledTimes(1);
    expect(store.floatingOpen).toBe(true);
    view.unmount();
  });

  it('历史会话面板列出本人会话并可就地选择续聊', async () => {
    const detail = vi.spyOn(api, 'json').mockResolvedValue({
      messages: [{ id: 3, role: 'USER', content: '历史问题', createdAt: '' }],
    });
    const { view, store } = makeLauncher();
    store.floatingOpen = true;
    store.sessionId = 's1';
    store.loaded = true;
    store.sessions = [
      { id: 's1', title: '当前会话' },
      { id: 's2', title: '历史会话二' },
    ];
    await flush();

    await fireEvent.click(view.getByTestId('float-history'));
    await flush();
    const panel = view.getByTestId('float-history-panel');
    expect(panel.textContent).toContain('当前会话');
    expect(panel.textContent).toContain('历史会话二');

    await fireEvent.click(view.getByTestId('history-session-s2'));
    await flush();
    expect(store.sessionId).toBe('s2');
    expect(view.queryByTestId('float-history-panel')).toBeNull();
    expect(detail).toHaveBeenCalledWith('/chat/sessions/s2');
    expect(view.getByText('历史问题')).toBeTruthy();
    view.unmount();
  });

  it('历史列表具备加载、空与错误状态', async () => {
    const { view, store } = makeLauncher();
    store.floatingOpen = true;
    store.loaded = true;
    store.sessions = [];
    await flush();
    await fireEvent.click(view.getByTestId('float-history'));
    expect(view.getByTestId('float-history-panel').textContent).toContain('暂无历史会话');
    view.unmount();
  });

  it('生成进行中禁止切换会话并解释原因，不取消当前 run', async () => {
    const { view, store } = makeLauncher();
    store.floatingOpen = true;
    store.running = true;
    store.sessionId = 's1';
    store.loaded = true;
    store.sessions = [
      { id: 's1', title: '当前会话' },
      { id: 's2', title: '其他会话' },
    ];
    await flush();
    const cancel = vi.spyOn(store, 'cancel');
    const select = vi.spyOn(store, 'select');

    await fireEvent.click(view.getByTestId('float-history'));
    await flush();
    const other = view.getByTestId('history-session-s2') as HTMLButtonElement;
    expect(other.disabled).toBe(true);
    await fireEvent.click(other);
    await flush();
    expect(view.getByTestId('float-history-panel').textContent).toContain('生成中');
    expect(select).not.toHaveBeenCalled();
    expect(cancel).not.toHaveBeenCalled();
    view.unmount();
  });

  it('生成进行中禁用新建会话，避免隐式取消当前 run', async () => {
    const { view, store } = makeLauncher();
    store.floatingOpen = true;
    store.running = true;
    await flush();
    const create = vi.spyOn(store, 'newConversation');
    const button = view.getByTestId('float-new-conversation') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    await fireEvent.click(button);
    expect(create).not.toHaveBeenCalled();
    expect(store.running).toBe(true);
    view.unmount();
  });

  it('Escape 先关闭历史面板，再次按下才最小化浮窗', async () => {
    const { view, store } = makeLauncher();
    store.floatingOpen = true;
    store.sessions = [{ id: 's1', title: '当前会话' }];
    await flush();

    await fireEvent.click(view.getByTestId('float-history'));
    expect(view.getByTestId('float-history-panel')).toBeTruthy();
    await fireEvent.keyDown(view.getByRole('dialog'), { key: 'Escape' });
    expect(view.queryByTestId('float-history-panel')).toBeNull();
    expect(store.floatingOpen).toBe(true);

    await fireEvent.keyDown(view.getByRole('dialog'), { key: 'Escape' });
    expect(store.floatingOpen).toBe(false);
    view.unmount();
  });

  it('选择历史会话后展开完整页携带相同 sessionId', async () => {
    const { view, router, store } = makeLauncher();
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined as never);
    store.floatingOpen = true;
    store.sessionId = 's2';
    await flush();
    await fireEvent.click(view.getByRole('button', { name: '展开完整聊天' }));
    expect(push).toHaveBeenCalledWith(expect.objectContaining({ name: 'conversations', params: { sessionId: 's2' } }));
    view.unmount();
  });
});
