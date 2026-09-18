import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/vue';
import { nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import PageReader from '../src/features/wiki/components/PageReader.vue';
import { locateChunk } from '../src/features/wiki/components/locateChunk';
import { createTestingPinia } from './test-pinia';
import { api } from '../src/features/wiki/api';
import { useWikiStore } from '../src/features/wiki/store';
import type { CitationEntry } from '../src/features/wiki/sse';

type HighlightRegistry = Map<string, { ranges: Range[] }>;

function installHighlightApi(): HighlightRegistry {
  const highlights: HighlightRegistry = new Map();
  (globalThis as unknown as { CSS: unknown }).CSS = {
    highlights,
    supports: () => true,
  };
  (globalThis as unknown as { Highlight: unknown }).Highlight = class {
    ranges: Range[];
    constructor(...ranges: Range[]) { this.ranges = ranges; }
  };
  return highlights;
}

function clearHighlightApi() {
  delete (globalThis as unknown as { CSS?: unknown }).CSS;
  delete (globalThis as unknown as { Highlight?: unknown }).Highlight;
}

function citation(overrides: Partial<CitationEntry> = {}): CitationEntry {
  return {
    childChunkKey: 'C/1', parentChunkKey: 'P/1', kbId: 1, resourceType: 'PAGE', resourceId: 11,
    revisionId: 1, headingPath: '', charStart: 2, charEnd: 6, excerpt: '目标命中', ...overrides,
  };
}

afterEach(() => { clearHighlightApi(); vi.restoreAllMocks(); });

describe('locateChunk 参考定位', () => {
  it('成功定位时注册可见高亮并滚动，cleanup 清理注册表', () => {
    const highlights = installHighlightApi();
    const container = document.createElement('div');
    container.innerHTML = '<p>前文</p><p>目标 <strong>命中</strong>内容</p>';
    document.body.append(container);
    const scroll = vi.fn();
    container.querySelectorAll('p').forEach(p => { p.scrollIntoView = scroll; });

    const result = locateChunk(container, { excerpt: '目标\n命中内容', charStart: 2 });
    expect(result.status).toBe('located');
    expect(highlights.get('kwiki-citation')).toBeTruthy();
    expect(scroll).toHaveBeenCalledTimes(1);

    result.cleanup();
    expect(highlights.has('kwiki-citation')).toBe(false);
    container.remove();
  });

  it('超时后自动清理高亮', () => {
    vi.useFakeTimers();
    const highlights = installHighlightApi();
    const container = document.createElement('div');
    container.innerHTML = '<p>目标命中</p>';
    document.body.append(container);
    container.querySelector('p')!.scrollIntoView = vi.fn();

    const result = locateChunk(container, { excerpt: '目标命中', highlightMs: 1000 });
    expect(highlights.has('kwiki-citation')).toBe(true);
    vi.advanceTimersByTime(1000);
    expect(highlights.has('kwiki-citation')).toBe(false);
    result.cleanup();
    container.remove();
    vi.useRealTimers();
  });

  it('缺少 Custom Highlight API 时使用临时 mark 回退并不破坏正文结构', () => {
    clearHighlightApi();
    const container = document.createElement('div');
    container.innerHTML = '<p>前文</p><p>目标 <strong>命中</strong>内容</p>';
    document.body.append(container);
    const originalText = container.textContent;
    container.querySelectorAll('p').forEach(p => { p.scrollIntoView = vi.fn(); });

    const result = locateChunk(container, { excerpt: '目标\n命中内容', charStart: 2 });
    expect(result.status).toBe('located');
    const mark = container.querySelector('mark[data-kwiki-citation]');
    expect(mark).toBeTruthy();
    expect(mark?.textContent).toBe('目标 命中内容');
    expect(container.textContent).toBe(originalText);

    result.cleanup();
    expect(container.querySelector('mark[data-kwiki-citation]')).toBeNull();
    expect(container.textContent).toBe(originalText);
    container.remove();
  });

  it('重复出现的片段无法按字符位置验证时判为歧义，不高亮', () => {
    const highlights = installHighlightApi();
    const container = document.createElement('div');
    container.innerHTML = '<p>重复片段 重复片段 重复片段</p>';
    document.body.append(container);
    const scroll = vi.fn();
    container.querySelector('p')!.scrollIntoView = scroll;

    const result = locateChunk(container, { excerpt: '重复片段', charStart: 5000 });
    expect(result.status).toBe('ambiguous');
    expect(highlights.has('kwiki-citation')).toBe(false);
    expect(scroll).not.toHaveBeenCalled();
    result.cleanup();
    container.remove();
  });

  it('多命中时接受距离 charStart 最近的唯一可验证匹配', () => {
    const highlights = installHighlightApi();
    const container = document.createElement('div');
    container.innerHTML = '<p>开头甲段</p><p>中间目标文本</p><p>结尾乙段 目标文本 重复</p>';
    document.body.append(container);
    container.querySelectorAll('p').forEach(p => { p.scrollIntoView = vi.fn(); });

    const result = locateChunk(container, { excerpt: '目标文本', charStart: 8 });
    expect(result.status).toBe('located');
    const highlight = highlights.get('kwiki-citation');
    expect(highlight).toBeTruthy();
    const text = highlight!.ranges[0]?.toString();
    expect(text).toBe('目标文本');
    // 命中的是“中间”段落（第二个目标文本距离开头更近）。
    const startNode = highlight!.ranges[0]?.startContainer as Text;
    expect(startNode.parentElement?.textContent).toBe('中间目标文本');
    result.cleanup();
    container.remove();
  });

  it('excerpt 不存在时返回 missing', () => {
    installHighlightApi();
    const container = document.createElement('div');
    container.innerHTML = '<p>只有这一段</p>';
    document.body.append(container);
    const result = locateChunk(container, { excerpt: '已删除文本' });
    expect(result.status).toBe('missing');
    result.cleanup();
    container.remove();
  });
});

async function flush(times = 6) { for (let i = 0; i < times; i++) await nextTick(); }

describe('PageReader 引用跳转生命周期', () => {
  function makeReader() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:rest?', name: 'home', component: { template: '<div />' } }],
    });
    const replace = vi.spyOn(router, 'replace').mockResolvedValue(undefined as never);
    const pinia = createTestingPinia();
    const view = render(PageReader, {
      props: { kbId: 1, pageId: 11, chunkKey: 'C/1' },
      global: { plugins: [pinia, router] },
    });
    return { view, router, replace, store: useWikiStore() };
  }

  function setPage(store: ReturnType<typeof useWikiStore>, html: string, extra: Record<string, unknown> = {}) {
    store.page = {
      revisionNo: 3, markdown: '', html, createdBy: 1, createdAt: '2026-01-01T00:00:00Z', ...extra,
    } as typeof store.page;
  }

  it('定位成功后显示高亮并消费 chunk 参数', async () => {
    const highlights = installHighlightApi();
    let resolveCitation: (value: CitationEntry) => void = () => {};
    vi.spyOn(api, 'json').mockImplementation(() => new Promise<CitationEntry>(resolve => { resolveCitation = resolve; }));
    const { view, replace, store } = makeReader();
    setPage(store, '<p>目标命中内容</p>');
    await flush();

    // 未得到最终结果前不消费路由参数、不显示高亮。
    expect(replace).not.toHaveBeenCalled();
    expect(highlights.has('kwiki-citation')).toBe(false);

    resolveCitation(citation());
    await flush();
    expect(highlights.has('kwiki-citation')).toBe(true);
    expect(replace).toHaveBeenCalledWith(expect.objectContaining({ query: {} }));
    view.unmount();
  });

  it('引用失效（拒绝访问）时展示状态并消费 chunk 参数', async () => {
    installHighlightApi();
    vi.spyOn(api, 'json').mockRejectedValue({ status: 404, code: 'not_found' });
    const { view, replace, store } = makeReader();
    setPage(store, '<p>目标命中内容</p>');
    await flush();
    expect(view.getByText(/原片段已失效或无权访问/)).toBeTruthy();
    expect(replace).toHaveBeenCalledWith(expect.objectContaining({ query: {} }));
    view.unmount();
  });

  it('正文更新导致无法定位时提示内容可能已更新，不猜测高亮', async () => {
    const highlights = installHighlightApi();
    vi.spyOn(api, 'json').mockResolvedValue(citation({ excerpt: '已经改变的文本' }));
    const { view, store } = makeReader();
    setPage(store, '<p>目标命中内容</p>');
    await flush();
    expect(view.getByText(/页面内容可能已更新/)).toBeTruthy();
    expect(highlights.has('kwiki-citation')).toBe(false);
    view.unmount();
  });

  it('组件卸载时清理残留高亮', async () => {
    const highlights = installHighlightApi();
    vi.spyOn(api, 'json').mockResolvedValue(citation());
    const { view, store } = makeReader();
    setPage(store, '<p>目标命中内容</p>');
    await flush();
    expect(highlights.has('kwiki-citation')).toBe(true);
    view.unmount();
    expect(highlights.has('kwiki-citation')).toBe(false);
  });

  it('迟到的旧定位任务不能高亮新页面内容', async () => {
    const highlights = installHighlightApi();
    const setCalls: string[] = [];
    const originalSet = highlights.set.bind(highlights);
    highlights.set = (key: string, value: { ranges: Range[] }) => { setCalls.push(key); return originalSet(key, value); };

    let resolveA: (value: CitationEntry) => void = () => {};
    const fetchCitation = vi.spyOn(api, 'json')
      .mockImplementationOnce(() => new Promise<CitationEntry>(resolve => { resolveA = resolve; }))
      .mockImplementationOnce(() => Promise.resolve(citation({ childChunkKey: 'C/2' })));

    const { view, store } = makeReader();
    setPage(store, '<p>页面甲的目标命中内容</p>');
    await flush();

    // 同一阅读器切换到另一个引用目标。
    await view.rerender({ kbId: 1, pageId: 11, chunkKey: 'C/2' });
    await flush();
    expect(fetchCitation).toHaveBeenCalledTimes(2);
    expect(setCalls).toEqual(['kwiki-citation']);

    // 旧引用 C/1 的迟到响应与正文同样匹配，但不得覆盖新目标的定位结果。
    resolveA(citation({ childChunkKey: 'C/1' }));
    await flush();
    expect(setCalls).toEqual(['kwiki-citation']);
    view.unmount();
  });

  it('当前页面内的引用定位不重建阅读容器', async () => {
    installHighlightApi();
    vi.spyOn(api, 'json').mockResolvedValue(citation());
    const { view, store } = makeReader();
    setPage(store, '<p>目标命中内容</p>');
    await flush();
    const content = view.getByTestId('page-content');
    await fireEvent.click(view.getByText('目标命中内容'));
    expect(view.getByTestId('page-content')).toBe(content);
    view.unmount();
  });
});
