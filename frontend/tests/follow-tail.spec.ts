import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/vue';
import { nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { useFollowTail } from '../src/features/wiki/components/followTail';
import ThinkingStream from '../src/features/wiki/components/ThinkingStream.vue';
import ConversationMessages from '../src/features/workspace/ConversationMessages.vue';
import { createTestingPinia } from './test-pinia';
import { useConversationStore } from '../src/features/workspace/conversationStore';

/** jsdom 没有布局：手动声明滚动几何。 */
function metrics(el: Element, scrollHeight: number, clientHeight: number) {
  Object.defineProperties(el, {
    scrollHeight: { value: scrollHeight, configurable: true },
    clientHeight: { value: clientHeight, configurable: true },
  });
  return el as HTMLElement;
}

function installResizeObserver() {
  const observers: Array<{ trigger: () => void }> = [];
  class ResizeObserverStub {
    private cb: ResizeObserverCallback;
    constructor(cb: ResizeObserverCallback) {
      this.cb = cb;
      observers.push({ trigger: () => this.cb([], this as unknown as ResizeObserver) });
    }
    observe = vi.fn(); unobserve = vi.fn(); disconnect = vi.fn();
  }
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
  return observers;
}

function installMatchMedia(reducedMotion: boolean) {
  vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
    matches: reducedMotion && query.includes('prefers-reduced-motion'),
    media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  })));
}

/** 等待帧合并后的程序滚动完成。 */
async function flushFrames(frames = 3) {
  for (let i = 0; i < frames; i++) await new Promise(resolve => requestAnimationFrame(() => resolve(null)));
  await nextTick();
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('follow-tail 状态机', () => {
  it('用户上滚暂停跟随、回到底部恢复，暂停期间流式更新不改写 scrollTop', async () => {
    const el = metrics(document.createElement('div'), 1000, 200);
    const tail = useFollowTail();
    tail.attach(el);
    expect(tail.state.value).toBe('FOLLOWING');

    el.scrollTop = 100;
    tail.handleUserScroll();
    expect(tail.state.value).toBe('PAUSED');
    await tail.notifyContentChanged();
    await flushFrames();
    expect(el.scrollTop).toBe(100);

    el.scrollTop = 790; // 距底部 10px，回到阈值内。
    tail.handleUserScroll();
    expect(tail.state.value).toBe('FOLLOWING');
    await tail.notifyContentChanged();
    await flushFrames();
    expect(el.scrollTop).toBe(1000);
    tail.detach();
  });

  it('程序滚动写入不改变用户跟随状态', async () => {
    const el = metrics(document.createElement('div'), 1000, 200);
    const tail = useFollowTail();
    tail.attach(el);
    await tail.notifyContentChanged();
    await flushFrames();
    expect(el.scrollTop).toBe(1000);
    expect(tail.state.value).toBe('FOLLOWING');
    tail.detach();
  });

  it('同帧内多次内容变化只合并为一次滚动写入', async () => {
    const el = metrics(document.createElement('div'), 1000, 200);
    let value = 0; let writes = 0;
    Object.defineProperty(el, 'scrollTop', {
      configurable: true,
      get: () => value,
      set: (next: number) => { writes += 1; value = next; },
    });
    const tail = useFollowTail();
    tail.attach(el);
    void tail.notifyContentChanged();
    void tail.notifyContentChanged();
    await flushFrames();
    expect(writes).toBe(1);
    expect(value).toBe(1000);
    tail.detach();
  });

  it('嵌套滚动区域的状态互不影响', async () => {
    const outer = metrics(document.createElement('div'), 2000, 400);
    const inner = metrics(document.createElement('div'), 1000, 200);
    const outerTail = useFollowTail();
    const innerTail = useFollowTail();
    outerTail.attach(outer); innerTail.attach(inner);

    inner.scrollTop = 100;
    innerTail.handleUserScroll();
    expect(innerTail.state.value).toBe('PAUSED');
    expect(outerTail.state.value).toBe('FOLLOWING');
    expect(outer.scrollTop).toBe(0);

    await innerTail.notifyContentChanged();
    await flushFrames();
    expect(inner.scrollTop).toBe(100);
    expect(outerTail.state.value).toBe('FOLLOWING');
    outerTail.detach(); innerTail.detach();
  });
});

describe('消息区跟随底部', () => {
  it('检索步骤等非回答高度变化仅在位于底部时跟随', async () => {
    installResizeObserver();
    const observers = installResizeObserver();
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] });
    const view = render(ConversationMessages, { props: { compact: true }, global: { plugins: [createTestingPinia(), router] } });
    const store = useConversationStore();
    const area = metrics(view.getByLabelText('聊天消息'), 1000, 200);
    area.scrollTop = 800; // 位于底部。
    await fireEvent.scroll(area);
    await flushFrames();

    metrics(area, 1200, 200);
    for (const observer of observers.slice()) observer.trigger();
    await flushFrames();
    expect(area.scrollTop).toBe(1200);

    // 用户上滚阅读历史后，新的高度变化不得拉回底部。
    area.scrollTop = 100;
    await fireEvent.scroll(area);
    metrics(area, 1500, 200);
    for (const observer of observers.slice()) observer.trigger();
    await flushFrames();
    expect(area.scrollTop).toBe(100);
    view.unmount();
  });
});

describe('思考区展开折叠动画与跟随', () => {
  it('暂停状态跨折叠/展开保留，恢复底部后继续跟随', async () => {
    installMatchMedia(false);
    installResizeObserver();
    const view = render(ThinkingStream, { props: { text: '第一行\n最新行' } });
    const toggle = view.getByRole('button');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');

    await fireEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    const area = metrics(view.getByRole('region'), 1000, 200);
    area.scrollTop = 100;
    await fireEvent.scroll(area);

    await view.rerender({ text: '第一行\n最新行\n更多内容' });
    await flushFrames();
    expect(area.scrollTop).toBe(100);

    await fireEvent.click(toggle); // 折叠
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    await fireEvent.click(toggle); // 重新展开
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(area.scrollTop).toBe(100); // 不强制跳到底部

    await view.rerender({ text: '再一行' });
    await flushFrames();
    expect(area.scrollTop).toBe(100);

    area.scrollTop = 790;
    await fireEvent.scroll(area);
    await view.rerender({ text: '回到底部后的新内容' });
    await flushFrames();
    expect(area.scrollTop).toBe(1000);
    view.unmount();
  });

  it('prefers-reduced-motion 时仅缩短视觉过渡，语义不变', async () => {
    installMatchMedia(true);
    const reduced = render(ThinkingStream, { props: { text: '内容' } });
    await nextTick();
    const root = reduced.container.querySelector('.thinking-stream') as HTMLElement;
    expect(root.style.getPropertyValue('--thinking-duration')).toBe('0ms');
    const toggle = reduced.getByRole('button');
    await fireEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(reduced.getByRole('region')).toBeTruthy();
    reduced.unmount();

    installMatchMedia(false);
    const normal = render(ThinkingStream, { props: { text: '内容' } });
    await nextTick();
    const normalRoot = normal.container.querySelector('.thinking-stream') as HTMLElement;
    const duration = normalRoot.style.getPropertyValue('--thinking-duration');
    expect(duration).not.toBe('0ms');
    expect(duration).not.toBe('');
    normal.unmount();
  });
});
