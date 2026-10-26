import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useConversationStore } from '../src/features/workspace/conversationStore';
import { api } from '../src/features/wiki/api';
import { openStream } from '../src/features/wiki/sse';
vi.mock('../src/features/wiki/sse', async original => ({ ...await original<object>(), openStream: vi.fn(() => vi.fn()) }));
beforeEach(() => { setActivePinia(createPinia()); vi.mocked(openStream).mockClear(); vi.spyOn(api, 'json').mockResolvedValue([]); });

describe('划线引用并入正常对话', () => {
  const quote = { kbId: 3, pageId: 4, pageTitle: '相机标定', text: ' 水平视场的连续性 ' };

  it('问 AI 展开浮窗并把引用挂到输入框上方', () => {
    const store = useConversationStore();
    store.askWithQuote(quote);
    expect(store.floatingOpen).toBe(true);
    expect(store.quote).toEqual({ ...quote, text: '水平视场的连续性' });
  });

  it('引用随下一条提问发出，用户消息带引用且发出后清空', () => {
    const store = useConversationStore();
    store.askWithQuote(quote); store.query = '怎么理解'; store.send();
    const options = vi.mocked(openStream).mock.calls[0][2];
    expect(vi.mocked(openStream).mock.calls[0][0]).toBe('怎么理解');
    expect(options?.quote).toEqual({ kbId: 3, pageId: 4, text: '水平视场的连续性' });
    expect(store.history[0].quote?.text).toBe('水平视场的连续性');
    expect(store.quote).toBeNull();
    expect(store.lastQuote?.text).toBe('水平视场的连续性');
  });

  it('只带引用未输入问题时按解释这段内容提问', () => {
    const store = useConversationStore();
    store.askWithQuote(quote); store.send();
    expect(vi.mocked(openStream).mock.calls[0][0]).toBe('请解释这段内容。');
  });

  it('没有引用时不发 quote 字段', () => {
    const store = useConversationStore();
    store.query = '普通问题'; store.send();
    expect(vi.mocked(openStream).mock.calls[0][2]?.quote).toBeUndefined();
  });
});
