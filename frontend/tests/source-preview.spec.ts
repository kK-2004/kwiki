import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/vue';
import { defineComponent, h, nextTick, onBeforeUnmount, onMounted } from 'vue';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import type { Pinia } from 'pinia';
import PageReader from '../src/features/wiki/components/PageReader.vue';
import SourcePreview from '../src/features/wiki/components/SourcePreview.vue';
import { createTestingPinia } from './test-pinia';
import { useWikiStore } from '../src/features/wiki/store';
import { useAuthStore } from '../src/features/auth/store';

type SourceSummary = { format: 'PDF' | 'DOCX'; fileName: string; byteSize: number; attachmentUuid: string };

const adapter = vi.hoisted(() => ({ renderSource: vi.fn(async () => ({ destroy: vi.fn() })) }));
const pdfViewer = vi.hoisted(() => ({
  sources: [] as Array<Blob | string>,
  headers: [] as Array<Record<string, string> | undefined>,
  outcomes: [] as Array<'ready' | 'error' | 'hang'>,
  unmounts: vi.fn(),
}));
vi.mock('../src/features/wiki/components/sourceAdapters', () => ({
  renderSource: adapter.renderSource,
  SOURCE_PREVIEW_MAX_BYTES: 20 * 1024 * 1024,
}));
vi.mock('../src/features/wiki/components/PdfSourceViewer.vue', () => ({
  default: defineComponent({
    name: 'PdfSourceViewer',
    props: ['source', 'httpHeaders'],
    emits: ['ready', 'error'],
    setup(props, { emit }) {
      onMounted(() => {
        pdfViewer.sources.push(props.source as Blob | string);
        pdfViewer.headers.push(props.httpHeaders as Record<string, string> | undefined);
        const outcome = pdfViewer.outcomes.shift() ?? 'ready';
        if (outcome === 'ready') emit('ready');
        if (outcome === 'error') emit('error', new TypeError('Failed to fetch'));
      });
      onBeforeUnmount(pdfViewer.unmounts);
      return () => h('div', { 'data-testid': 'pdf-viewer' });
    },
  }),
}));

type FetchResponse = {
  ok: boolean;
  status: number;
  headers?: Headers;
  json?: () => Promise<unknown>;
  blob: () => Promise<Blob>;
};
function stubFetch(handler: (url: string, init?: RequestInit) => Promise<FetchResponse> | FetchResponse) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    return handler(url, init);
  });
  vi.stubGlobal('fetch', fn);
  return { fn, calls };
}
const okBlob = { ok: true, status: 200, blob: async () => new Blob(['%PDF-1.7 mock'], { type: 'application/pdf' }) };

async function flush(times = 16) { for (let i = 0; i < times; i++) await nextTick(); }

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  pdfViewer.sources.length = 0;
  pdfViewer.headers.length = 0;
  pdfViewer.outcomes.length = 0;
});

describe('PageReader 来源页签可见性', () => {
  function makeReader(source?: SourceSummary['format'] | null) {
    const router: Router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:rest?', name: 'home', component: { template: '<div />' } }],
    });
    const pinia = createTestingPinia();
    const view = render(PageReader, { props: { kbId: 1, pageId: 11 }, global: { plugins: [pinia, router] } });
    const store = useWikiStore(pinia);
    store.page = {
      revisionNo: 1, markdown: '', html: '<p>解析后的正文内容</p>', createdBy: 1,
      createdAt: '2026-01-01T00:00:00Z', title: '页面',
      ...(source ? { sourceDocument: { format: source, fileName: source === 'PDF' ? 'manual.pdf' : 'manual.docx', byteSize: 1024, attachmentUuid: 'att-1' } } : {}),
    } as typeof store.page;
    return { view, store };
  }

  it('PDF 导入页显示“PDF 源文件 / 解析文本”页签，默认展示解析文本', async () => {
    stubFetch(() => okBlob);
    const { view } = makeReader('PDF');
    await flush();
    expect(view.getByRole('tab', { name: 'PDF 源文件' })).toBeTruthy();
    const parsedTab = view.getByRole('tab', { name: '解析文本' });
    expect(parsedTab.getAttribute('aria-selected')).toBe('true');
    expect(view.getByTestId('page-content').textContent).toContain('解析后的正文内容');
    expect(view.queryByTestId('source-preview')).toBeNull();
    view.unmount();
  });

  it('DOCX 导入页显示“DOCX 源文件”页签', async () => {
    stubFetch(() => okBlob);
    const { view } = makeReader('DOCX');
    await flush();
    expect(view.getByRole('tab', { name: 'DOCX 源文件' })).toBeTruthy();
    view.unmount();
  });

  it('Markdown 导入页与无来源普通页不显示页签', async () => {
    const markdown = makeReader();
    await flush();
    expect(markdown.view.queryByRole('tab', { name: /源文件/ })).toBeNull();
    expect(markdown.view.queryByRole('tab', { name: '解析文本' })).toBeNull();
    markdown.view.unmount();

    const none = makeReader(null);
    await flush();
    expect(none.view.queryByRole('tablist')).toBeNull();
    none.view.unmount();
  });

  it('切换页签保留解析文本阅读状态并释放源文件预览资源', async () => {
    stubFetch(() => okBlob);
    const { view } = makeReader('PDF');
    await flush();
    const content = view.getByTestId('page-content');

    await fireEvent.click(view.getByRole('tab', { name: 'PDF 源文件' }));
    await flush();
    expect(view.getByRole('tab', { name: 'PDF 源文件' }).getAttribute('aria-selected')).toBe('true');
    expect(view.getByTestId('source-preview')).toBeTruthy();
    expect(pdfViewer.sources).toHaveLength(1);

    // 切回解析文本：阅读容器不被重建，预览资源被释放。
    await fireEvent.click(view.getByRole('tab', { name: '解析文本' }));
    await flush();
    expect(view.getByTestId('page-content')).toBe(content);
    expect(pdfViewer.unmounts).toHaveBeenCalled();
    expect(view.queryByTestId('source-preview')).toBeNull();
    view.unmount();
  });
});

describe('SourcePreview 预览组件', () => {
  const source: SourceSummary = { format: 'PDF', fileName: 'manual.pdf', byteSize: 1024, attachmentUuid: 'att-1' };

  function makePreview(props: Partial<{ kbId: number; pageId: number; source: SourceSummary }> = {}): { view: ReturnType<typeof render>; pinia: Pinia } {
    const pinia = createTestingPinia();
    const view = render(SourcePreview, { props: { kbId: 1, pageId: 11, source, ...props }, global: { plugins: [pinia] } });
    return { view, pinia };
  }

  it('通过页面作用域授权接口获取 Blob 并叠加当前用户水印', async () => {
    const { fn, calls } = stubFetch(url => url.endsWith('/source-preview')
      ? {
          ok: true, status: 200, headers: new Headers({ 'Content-Type': 'application/json' }),
          json: async () => ({ data: { url: 'https://files.example.test/signed-pdf' } }),
          blob: async () => new Blob(),
        }
      : okBlob);
    const { view, pinia } = makePreview();
    const auth = useAuthStore(pinia);
    auth.user = { id: 7, username: 'zhangsan', displayName: '张三', admin: false };
    await flush();
    expect(calls.map(call => call.url)).toEqual([
      '/api/v1/knowledge-bases/1/pages/11/source-preview',
    ]);
    expect(pdfViewer.sources).toEqual(['https://files.example.test/signed-pdf']);
    const watermark = view.getByTestId('source-watermark');
    expect(watermark.getAttribute('aria-hidden')).toBe('true');
    expect(watermark.textContent).toContain('zhangsan');
    expect(fn).toHaveBeenCalledTimes(1);
    view.unmount();
  });

  it('PDF 即使携带下载响应头也读取为 Blob 渲染，不导航文件地址', async () => {
    const { calls } = stubFetch(() => ({
      ...okBlob,
      headers: new Headers({
        'Content-Type': 'application/pdf',
        'Content-Disposition': 'attachment; filename="manual.pdf"',
      }),
    }));
    const { view } = makePreview();
    await flush();
    expect(calls.map(call => call.url)).toEqual([
      '/api/v1/knowledge-bases/1/pages/11/source-preview',
    ]);
    expect(view.container.querySelector('iframe')).toBeNull();
    expect(pdfViewer.sources[0]).toBeInstanceOf(Blob);
    expect(view.getByTestId('source-watermark')).toBeTruthy();
    view.unmount();
  });

  it('内容中心直连被跨域阻止时回退到同源 PDF 内容接口', async () => {
    const { calls } = stubFetch(url => {
      if (url.endsWith('/source-preview')) {
        return {
          ok: true, status: 200, headers: new Headers({ 'Content-Type': 'application/json' }),
          json: async () => ({ data: { url: 'https://files.example.test/cors-blocked' } }),
          blob: async () => new Blob(),
        };
      }
      return okBlob;
    });
    pdfViewer.outcomes.push('error', 'ready');
    const { view } = makePreview();
    await flush();
    expect(calls.map(call => call.url)).toEqual([
      '/api/v1/knowledge-bases/1/pages/11/source-preview',
    ]);
    expect(pdfViewer.sources).toEqual([
      'https://files.example.test/cors-blocked',
      '/api/v1/knowledge-bases/1/pages/11/source-preview/content',
    ]);
    expect(pdfViewer.headers).toHaveLength(2);
    view.unmount();
  });

  it('PDF 签名地址交给 PDF.js，由其按需发起 Range 请求', async () => {
    const { calls } = stubFetch(url => {
      if (url.endsWith('/source-preview')) {
        return {
          ok: true, status: 200, headers: new Headers({ 'Content-Type': 'application/json' }),
          json: async () => ({ data: { url: 'http://minio-local:9000/wiki/manual.pdf?signature=x' } }),
          blob: async () => new Blob(),
        };
      }
      return okBlob;
    });
    const { view } = makePreview();
    await flush();
    expect(calls.map(call => call.url)).toEqual([
      '/api/v1/knowledge-bases/1/pages/11/source-preview',
    ]);
    expect(pdfViewer.sources).toEqual(['http://minio-local:9000/wiki/manual.pdf?signature=x']);
    view.unmount();
  });

  it('加载失败时展示原因与重试入口，重试成功后恢复', async () => {
    let fail = true;
    stubFetch(async () => {
      if (fail) throw new TypeError('network down');
      return okBlob;
    });
    const { view } = makePreview();
    await flush();
    expect(view.getByTestId('source-error').textContent).toContain('预览');
    expect(view.getByTestId('source-retry')).toBeTruthy();
    expect(pdfViewer.sources).toHaveLength(0);

    fail = false;
    await fireEvent.click(view.getByTestId('source-retry'));
    await flush();
    expect(pdfViewer.sources).toHaveLength(1);
    expect(view.queryByTestId('source-error')).toBeNull();
    view.unmount();
  });

  it('解析器无响应时结束加载状态并提供重试入口', async () => {
    vi.useFakeTimers();
    stubFetch(() => okBlob);
    pdfViewer.outcomes.push('hang');
    const { view } = makePreview();
    await flush();
    expect(view.getByTestId('source-loading')).toBeTruthy();

    await vi.advanceTimersByTimeAsync(30_000);
    await flush();
    expect(view.getByTestId('source-error').textContent).toContain('超时');
    expect(view.getByTestId('source-retry')).toBeTruthy();
    view.unmount();
  });

  it('无权访问时进入不可访问状态且不创建预览', async () => {
    stubFetch(() => ({ ok: false, status: 403, blob: async () => new Blob() }));
    const { view } = makePreview();
    await flush();
    expect(view.getByTestId('source-error').textContent).toContain('无权');
    expect(pdfViewer.sources).toHaveLength(0);
    view.unmount();
  });

  it('切换页面目标时中止旧请求并在卸载时释放资源', async () => {
    const { calls } = stubFetch(() => okBlob);
    const { view } = makePreview();
    await flush();
    const firstSignal = calls[0]?.init?.signal;

    await view.rerender({ kbId: 1, pageId: 12, source });
    await flush();
    expect(firstSignal?.aborted).toBe(true);

    view.unmount();
    expect(pdfViewer.unmounts).toHaveBeenCalled();
  });
});
